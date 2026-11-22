package de.tubyoub.velocitypteropower.addon;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.tubyoub.velocitypteropower.VelocityPteroPower;
import de.tubyoub.velocitypteropower.manager.ConfigurationManager;
import org.slf4j.Logger;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.*;

/**
 * Fetches and merges the official catalog with operator extras.
 */
public final class AddonCatalogService {
    private final VelocityPteroPower plugin;
    private final Logger logger;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private volatile List<AddonCatalogEntry> cached = List.of();
    private volatile long cachedAtMs = 0L;
    private static final long CACHE_TTL_MS = 60_000L;

    public AddonCatalogService(VelocityPteroPower plugin) {
        this.plugin = plugin;
        this.logger = plugin.getFilteredLogger();
    }

    public List<AddonCatalogEntry> getCatalog(boolean forceRefresh) {
        long now = System.currentTimeMillis();
        if (!forceRefresh && !cached.isEmpty() && (now - cachedAtMs) < CACHE_TTL_MS) {
            return cached;
        }
        Map<String, AddonCatalogEntry> byId = new LinkedHashMap<>();

        ConfigurationManager cfg = plugin.getConfigurationManager();
        String primary = cfg.getAddonCatalogUrl();
        if (primary != null && !primary.isBlank()) {
            merge(byId, fetchUrl(primary));
        }
        for (String url : cfg.getAddonExtraCatalogUrls()) {
            if (url != null && !url.isBlank()) merge(byId, fetchUrl(url));
        }
        for (AddonCatalogEntry entry : cfg.getAddonExtraEntries()) {
            if (entry != null && entry.getId() != null && !entry.getId().isBlank()) {
                byId.put(entry.getId().toLowerCase(Locale.ROOT), entry);
            }
        }

        List<AddonCatalogEntry> list = new ArrayList<>(byId.values());
        cached = List.copyOf(list);
        cachedAtMs = now;
        return cached;
    }

    public Optional<AddonCatalogEntry> find(String id) {
        if (id == null) return Optional.empty();
        String key = id.toLowerCase(Locale.ROOT);
        return getCatalog(false).stream()
                .filter(e -> e.getId() != null && e.getId().equalsIgnoreCase(key))
                .findFirst();
    }

    public Path download(AddonCatalogEntry entry, Path addonsDir) throws Exception {
        if (entry == null || entry.getDownloadUrl() == null || entry.getDownloadUrl().isBlank()) {
            throw new IllegalArgumentException("Catalog entry has no downloadUrl");
        }
        Files.createDirectories(addonsDir);
        String fileName = sanitizeFileName(entry.getId()) + "-" + sanitizeFileName(entry.getLatestVersion()) + ".jar";
        Path target = addonsDir.resolve(fileName);
        Path temp = addonsDir.resolve(fileName + ".tmp");

        HttpRequest request = HttpRequest.newBuilder(URI.create(entry.getDownloadUrl()))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .header("User-Agent", "VelocityPteroPower-AddonCatalog")
                .build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Download failed HTTP " + response.statusCode() + " for " + entry.getId());
        }
        try (InputStream in = response.body()) {
            Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
        }
        // Remove older jars for same addon id prefix
        try (var stream = Files.list(addonsDir)) {
            stream.filter(p -> {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                return n.startsWith(entry.getId().toLowerCase(Locale.ROOT) + "-") && n.endsWith(".jar") && !p.equals(target);
            }).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
        }
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return target;
    }

    private List<AddonCatalogEntry> fetchUrl(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .header("User-Agent", "VelocityPteroPower-AddonCatalog")
                    .header("Accept", "application/json")
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                logger.warn("Addon catalog fetch failed (HTTP {}) for {}", response.statusCode(), url);
                return List.of();
            }
            AddonCatalogDocument doc = mapper.readValue(response.body(), AddonCatalogDocument.class);
            return doc.getAddons() != null ? doc.getAddons() : List.of();
        } catch (Exception e) {
            logger.warn("Failed to fetch addon catalog from {}: {}", url, e.toString());
            return List.of();
        }
    }

    private void merge(Map<String, AddonCatalogEntry> byId, List<AddonCatalogEntry> entries) {
        if (entries == null) return;
        for (AddonCatalogEntry e : entries) {
            if (e == null || e.getId() == null || e.getId().isBlank()) continue;
            byId.put(e.getId().toLowerCase(Locale.ROOT), e);
        }
    }

    private static String sanitizeFileName(String raw) {
        if (raw == null || raw.isBlank()) return "addon";
        return raw.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
