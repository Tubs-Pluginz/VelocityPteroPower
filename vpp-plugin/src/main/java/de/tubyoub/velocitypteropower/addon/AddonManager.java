package de.tubyoub.velocitypteropower.addon;

import de.tubyoub.velocitypteropower.VelocityPteroPower;
import de.tubyoub.vpp.api.AddonMeta;
import de.tubyoub.vpp.api.VPPAddon;
import de.tubyoub.vpp.api.VPPApi;
import de.tubyoub.vpp.api.VPPApiProvider;
import dev.dejvokep.boostedyaml.YamlDocument;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Discovers, loads, enables, disables and hot-reloads VPP-managed addon JARs.
 */
public final class AddonManager {
    private final VelocityPteroPower plugin;
    private final Logger logger;
    private final Path addonsDir;
    private final Path stateFile;
    private final Map<String, LoadedAddon> loaded = new ConcurrentHashMap<>();
    private final Map<String, Boolean> enabledState = new ConcurrentHashMap<>();
    private final AddonCatalogService catalogService;

    public AddonManager(VelocityPteroPower plugin) {
        this.plugin = plugin;
        this.logger = plugin.getFilteredLogger();
        this.addonsDir = plugin.getDataDirectory().resolve("addons");
        this.stateFile = addonsDir.resolve("state.yml");
        this.catalogService = new AddonCatalogService(plugin);
    }

    public AddonCatalogService getCatalogService() { return catalogService; }
    public Path getAddonsDir() { return addonsDir; }

    public Collection<LoadedAddon> getLoadedAddons() {
        return Collections.unmodifiableCollection(loaded.values());
    }

    public Optional<LoadedAddon> getAddon(String id) {
        if (id == null) return Optional.empty();
        return Optional.ofNullable(loaded.get(id.toLowerCase(Locale.ROOT)));
    }

    public void loadAllFromDisk() {
        try {
            Files.createDirectories(addonsDir);
            Files.createDirectories(addonsDir.resolve("data"));
            Files.createDirectories(addonsDir.resolve("configs"));
        } catch (IOException e) {
            logger.error("Failed to create addons directory: {}", e.toString());
            return;
        }
        loadState();
        if (!plugin.getConfigurationManager().isAddonAutoLoadOnStartup()) {
            logger.info("Addon auto-load disabled by config (addons.autoLoadOnStartup=false).");
            discoverJarsWithoutEnable();
            return;
        }
        List<Path> jars;
        try (var stream = Files.list(addonsDir)) {
            jars = stream.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            logger.error("Failed to scan addons directory: {}", e.toString());
            return;
        }
        for (Path jar : jars) {
            try {
                loadJar(jar, shouldEnable(jar));
            } catch (Exception e) {
                logger.error("Failed to load addon jar {}: {}", jar.getFileName(), e.toString());
            }
        }
        logger.info("Addon manager ready: {} installed, {} active.",
                loaded.size(),
                loaded.values().stream().filter(LoadedAddon::isActive).count());
    }

    private void discoverJarsWithoutEnable() {
        try (var stream = Files.list(addonsDir)) {
            stream.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .forEach(jar -> {
                        try { loadJar(jar, false); } catch (Exception e) {
                            logger.error("Failed to discover addon {}: {}", jar.getFileName(), e.toString());
                        }
                    });
        } catch (IOException e) {
            logger.error("Failed to scan addons directory: {}", e.toString());
        }
    }

    private boolean shouldEnable(Path jar) {
        // Prefer state by id after probe; default true for unknown
        String guessed = guessIdFromFileName(jar);
        return enabledState.getOrDefault(guessed, true);
    }

    private static String guessIdFromFileName(Path jar) {
        String name = jar.getFileName().toString();
        if (name.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            name = name.substring(0, name.length() - 4);
        }
        int dash = name.lastIndexOf('-');
        if (dash > 0) {
            String maybeVersion = name.substring(dash + 1);
            if (maybeVersion.matches("\\d+.*")) {
                return name.substring(0, dash).toLowerCase(Locale.ROOT);
            }
        }
        return name.toLowerCase(Locale.ROOT);
    }

    public synchronized LoadedAddon loadJar(Path jar, boolean enable) throws Exception {
        if (jar == null || !Files.isRegularFile(jar)) {
            throw new IllegalArgumentException("Addon jar not found: " + jar);
        }
        Path normalized = jar.toAbsolutePath().normalize();
        if (!normalized.startsWith(addonsDir.toAbsolutePath().normalize())) {
            throw new SecurityException("Addon jar must reside under " + addonsDir);
        }

        URLClassLoader cl = new URLClassLoader(
                new URL[]{normalized.toUri().toURL()},
                plugin.getClass().getClassLoader());
        ServiceLoader<VPPAddon> loader = ServiceLoader.load(VPPAddon.class, cl);
        List<VPPAddon> providers = new ArrayList<>();
        for (VPPAddon candidate : loader) {
            providers.add(candidate);
        }
        if (providers.isEmpty()) {
            try { cl.close(); } catch (IOException ignored) {}
            throw new IllegalStateException("No META-INF/services/" + VPPAddon.class.getName() + " entry in " + jar.getFileName());
        }
        if (providers.size() > 1) {
            try { cl.close(); } catch (IOException ignored) {}
            throw new IllegalStateException("Addon jar " + jar.getFileName()
                    + " declares " + providers.size() + " VPPAddon providers; only one per JAR is supported");
        }
        VPPAddon addon = providers.get(0);
        AddonMeta meta = addon.meta();
        if (meta == null || meta.id() == null) {
            try { cl.close(); } catch (IOException ignored) {}
            throw new IllegalStateException("Addon meta/id missing in " + jar.getFileName());
        }
        String id = normalizeId(meta.id());
        // Prefer real id over filename guess for enable-state
        Boolean byId = enabledState.get(id);
        if (byId == null) {
            String guessed = guessIdFromFileName(normalized);
            byId = enabledState.getOrDefault(normalizeId(guessed), enable);
            enabledState.putIfAbsent(id, byId);
        }
        boolean shouldEnable = enable && byId;

        if (!isApiCompatible(meta.apiVersion(), VPPApiProvider.get() != null ? VPPApiProvider.get().getApiVersion() : "1.0.0")) {
            try { cl.close(); } catch (IOException ignored) {}
            throw new IllegalStateException("Addon " + id + " requires API " + meta.apiVersion()
                    + " incompatible with host");
        }

        // Replace existing
        if (loaded.containsKey(id)) {
            disable(id, false);
            unload(id);
        }

        LoadedAddon loadedAddon = new LoadedAddon(normalized, meta, addon, cl, LoadedAddon.Status.DISABLED);
        loaded.put(id, loadedAddon);
        saveState();

        if (shouldEnable) {
            enable(id);
        }
        return loadedAddon;
    }

    public synchronized boolean enable(String id) {
        LoadedAddon addon = loaded.get(normalizeId(id));
        if (addon == null) {
            logger.warn("Cannot enable unknown addon '{}'", id);
            return false;
        }
        if (addon.isActive()) return true;
        VPPApi api = VPPApiProvider.get();
        if (api == null) {
            addon.setStatus(LoadedAddon.Status.ERROR);
            addon.setErrorMessage("VPP API not available");
            logger.error("Cannot enable addon {}: VPP API not available", addon.getMeta().id());
            return false;
        }
        try {
            Path dataDir = addonsDir.resolve("data").resolve(addon.getMeta().id());
            Files.createDirectories(dataDir);
            AddonContextImpl ctx = new AddonContextImpl(
                    api,
                    addon.getMeta(),
                    new Slf4jAddonLogger(logger, addon.getMeta().id()),
                    dataDir);
            addon.getInstance().onEnable(ctx);
            addon.setContext(ctx);
            addon.setStatus(LoadedAddon.Status.ACTIVE);
            addon.setErrorMessage(null);
            enabledState.put(normalizeId(addon.getMeta().id()), true);
            saveState();
            logger.info("Enabled addon {} v{}", addon.getMeta().name(), addon.getMeta().version());
            return true;
        } catch (Throwable t) {
            addon.setStatus(LoadedAddon.Status.ERROR);
            addon.setErrorMessage(t.toString());
            logger.error("Failed to enable addon {}: {}", addon.getMeta().id(), t.toString());
            return false;
        }
    }

    public synchronized boolean disable(String id, boolean persist) {
        LoadedAddon addon = loaded.get(normalizeId(id));
        if (addon == null) return false;
        if (addon.getContext() != null) {
            try { addon.getContext().cleanup(); } catch (Throwable ignored) {}
            addon.setContext(null);
        }
        try { addon.getInstance().onDisable(); } catch (Throwable t) {
            logger.warn("Addon {} onDisable error: {}", addon.getMeta().id(), t.toString());
        }
        if (addon.getStatus() == LoadedAddon.Status.ACTIVE) {
            addon.setStatus(LoadedAddon.Status.DISABLED);
        }
        if (persist) {
            enabledState.put(normalizeId(addon.getMeta().id()), false);
            saveState();
        }
        logger.info("Disabled addon {}", addon.getMeta().id());
        return true;
    }

    public synchronized boolean unload(String id) {
        LoadedAddon addon = loaded.remove(normalizeId(id));
        if (addon == null) return false;
        if (addon.getContext() != null) {
            try { addon.getContext().cleanup(); } catch (Throwable ignored) {}
        }
        try { addon.getClassLoader().close(); } catch (IOException e) {
            logger.debug("Error closing classloader for {}: {}", id, e.toString());
        }
        return true;
    }

    public synchronized boolean reload(String id) {
        LoadedAddon existing = loaded.get(normalizeId(id));
        if (existing == null) {
            logger.warn("Cannot reload unknown addon '{}'", id);
            return false;
        }
        Path jar = existing.getJarPath();
        boolean wasEnabled = existing.isActive()
                || enabledState.getOrDefault(normalizeId(existing.getMeta().id()), true);
        disable(existing.getMeta().id(), false);
        unload(existing.getMeta().id());
        try {
            loadJar(jar, wasEnabled);
            return true;
        } catch (Exception e) {
            logger.error("Reload failed for {}: {}", id, e.toString());
            return false;
        }
    }

    public synchronized void reloadAll() {
        List<String> ids = new ArrayList<>(loaded.keySet());
        for (String id : ids) reload(id);
    }

    /**
     * After the host recreates {@link VPPApi}, re-enable all previously active addons
     * so their registrations bind to the new API instance.
     */
    public synchronized void rebind(VPPApi newApi) {
        if (newApi == null) return;
        List<String> activeIds = loaded.values().stream()
                .filter(a -> a.isActive() || enabledState.getOrDefault(normalizeId(a.getMeta().id()), false))
                .map(a -> normalizeId(a.getMeta().id()))
                .collect(Collectors.toList());
        for (String id : activeIds) {
            disable(id, false);
            enable(id);
        }
        logger.info("Re-bound {} addon(s) to refreshed VPP API.", activeIds.size());
    }

    public synchronized void disableAll() {
        for (String id : new ArrayList<>(loaded.keySet())) {
            disable(id, false);
            unload(id);
        }
    }

    public synchronized boolean uninstall(String id) {
        LoadedAddon addon = loaded.get(normalizeId(id));
        if (addon == null) return false;
        Path jar = addon.getJarPath();
        disable(id, false);
        unload(id);
        enabledState.remove(normalizeId(id));
        saveState();
        try {
            Files.deleteIfExists(jar);
            return true;
        } catch (IOException e) {
            logger.warn("Failed to delete addon jar {}: {}", jar, e.toString());
            return false;
        }
    }

    public synchronized String installFromCatalog(String catalogId) throws Exception {
        AddonCatalogEntry entry = catalogService.find(catalogId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown catalog addon: " + catalogId));
        Path jar = catalogService.download(entry, addonsDir);
        enabledState.put(normalizeId(entry.getId()), true);
        LoadedAddon loadedAddon = loadJar(jar, true);
        return loadedAddon.getMeta().id();
    }

    public synchronized String updateFromCatalog(String id) throws Exception {
        AddonCatalogEntry entry = catalogService.find(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown catalog addon: " + id));
        LoadedAddon current = loaded.get(normalizeId(id));
        if (current != null && Objects.equals(current.getMeta().version(), entry.getLatestVersion())) {
            return "up-to-date";
        }
        if (current != null) {
            disable(id, false);
            unload(id);
        }
        Path jar = catalogService.download(entry, addonsDir);
        enabledState.put(normalizeId(entry.getId()), true);
        loadJar(jar, true);
        return entry.getLatestVersion();
    }

    /** True if a jar for this catalog id is already loaded (catalog install or manual drop). */
    public boolean isInstalled(String catalogOrAddonId) {
        return getAddon(catalogOrAddonId).isPresent();
    }

    static boolean isApiCompatible(String addonApi, String hostApi) {
        if (addonApi == null || hostApi == null) return true;
        int aMajor = major(addonApi);
        int hMajor = major(hostApi);
        return aMajor == hMajor;
    }

    private static int major(String version) {
        try {
            String first = version.split("[.-]")[0];
            return Integer.parseInt(first);
        } catch (Exception e) {
            return 0;
        }
    }

    private static String normalizeId(String id) {
        return id == null ? "" : id.toLowerCase(Locale.ROOT);
    }

    private void loadState() {
        enabledState.clear();
        if (!Files.isRegularFile(stateFile)) return;
        try {
            YamlDocument doc = YamlDocument.create(stateFile.toFile());
            var section = doc.getSection("enabled");
            if (section != null) {
                for (Object keyObj : section.getKeys()) {
                    String key = String.valueOf(keyObj);
                    enabledState.put(key.toLowerCase(Locale.ROOT), section.getBoolean(key, true));
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to load addon state.yml: {}", e.toString());
        }
    }

    private void saveState() {
        try {
            Files.createDirectories(addonsDir);
            YamlDocument doc = Files.isRegularFile(stateFile)
                    ? YamlDocument.create(stateFile.toFile())
                    : YamlDocument.create(stateFile.toFile());
            doc.remove("enabled");
            for (Map.Entry<String, Boolean> e : enabledState.entrySet()) {
                doc.set("enabled." + e.getKey(), e.getValue());
            }
            doc.save();
        } catch (Exception e) {
            logger.warn("Failed to save addon state.yml: {}", e.toString());
        }
    }
}
