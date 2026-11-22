/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.vpp.testbridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.inject.Inject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.permission.PermissionsSetupEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.permission.PermissionFunction;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import de.tubyoub.velocitypteropower.VelocityPteroPower;
import de.tubyoub.velocitypteropower.addon.LoadedAddon;
import de.tubyoub.velocitypteropower.model.PteroServerInfo;
import de.tubyoub.velocitypteropower.service.ServerStateCache;
import de.tubyoub.vpp.api.VPPApi;
import de.tubyoub.vpp.api.VPPApiProvider;
import de.tubyoub.vpp.api.maintenance.MaintenanceClearListener;
import de.tubyoub.vpp.api.maintenance.MaintenanceProvider;
import de.tubyoub.vpp.api.maintenance.MaintenanceQuery;
import de.tubyoub.vpp.api.routing.PlayerRouteContext;
import de.tubyoub.vpp.api.routing.RoutingProvider;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Test-only Velocity plugin loaded during live E2E runs.
 * Exposes an HTTP control/inspection API so automated Minecraft bot suites can:
 *  - inspect VPP runtime state (startingServers, waitingPlayers, ServerStateCache, addons)
 *  - execute /ptero commands with deterministic permissions and read Adventure output
 *  - assign dynamic Velocity permissions to live test bots (Admin, Alice, Bob, Stranger)
 *  - register/toggle synthetic external MaintenanceProvider & RoutingProvider SPI hooks
 *  - trigger clean proxy shutdown to verify shutdownOnProxyExit
 */
public final class VppTestBridgePlugin {

    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final ProxyServer proxyServer;
    private final Logger logger;
    private HttpServer httpServer;

    /** Per-username dynamic permissions for real connected MC test clients. */
    private final Map<String, Set<String>> playerPermissions = new ConcurrentHashMap<>();

    /** Synthetic external MaintenanceProvider registered on demand for SPI tests. */
    private final SyntheticMaintenanceProvider syntheticMaintenance = new SyntheticMaintenanceProvider();
    private volatile boolean syntheticMaintenanceRegistered = false;

    /** Synthetic external RoutingProvider registered on demand for SPI tests. */
    private final SyntheticRoutingProvider syntheticRouting = new SyntheticRoutingProvider();
    private volatile boolean syntheticRoutingRegistered = false;

    @Inject
    public VppTestBridgePlugin(ProxyServer proxyServer, Logger logger) {
        this.proxyServer = proxyServer;
        this.logger = logger;
        // Default test permissions: Admin gets full bypass/staff perms
        Set<String> adminPerms = ConcurrentHashMap.newKeySet();
        adminPerms.add("*");
        playerPermissions.put("admin", adminPerms);
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        int port = parsePort(System.getenv("VPP_TEST_BRIDGE_PORT"), 19090);
        try {
            httpServer = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
            httpServer.setExecutor(Executors.newCachedThreadPool());
            httpServer.createContext("/health", this::handleHealth);
            httpServer.createContext("/state", this::handleState);
            httpServer.createContext("/command", this::handleCommand);
            httpServer.createContext("/permissions", this::handlePermissions);
            httpServer.createContext("/maintenance-provider", this::handleMaintenanceProvider);
            httpServer.createContext("/routing-provider", this::handleRoutingProvider);
            httpServer.createContext("/shutdown", this::handleShutdown);
            httpServer.createContext("/suggest", this::handleSuggest);
            httpServer.createContext("/file", this::handleFile);
            httpServer.start();
            logger.info("VPP Test Bridge listening on 0.0.0.0:{}", port);
        } catch (IOException e) {
            logger.error("Failed to start VPP Test Bridge HTTP server on port {}", port, e);
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }
    }

    @Subscribe
    public void onPermissionsSetup(PermissionsSetupEvent event) {
        if (event.getSubject() instanceof Player player) {
            String key = player.getUsername().toLowerCase(Locale.ROOT);
            PermissionFunction original = event.createFunction(player);
            event.setProvider(subject -> permission -> {
                Set<String> granted = playerPermissions.get(key);
                if (granted != null) {
                    if (granted.contains("*")) {
                        return Tristate.TRUE;
                    }
                    if (granted.contains("-" + permission)) {
                        return Tristate.FALSE;
                    }
                    if (granted.contains(permission)) {
                        return Tristate.TRUE;
                    }
                }
                return original.getPermissionValue(permission);
            });
        }
    }

    private Optional<VelocityPteroPower> vpp() {
        return proxyServer.getPluginManager()
                .getPlugin("velocity-ptero-power")
                .flatMap(PluginContainer::getInstance)
                .filter(VelocityPteroPower.class::isInstance)
                .map(VelocityPteroPower.class::cast);
    }

    private void handleHealth(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, Map.of("error", "GET required"));
            return;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("velocityVersion", proxyServer.getVersion().getVersion());
        out.put("velocityName", proxyServer.getVersion().getName());
        out.put("javaVersion", System.getProperty("java.version"));

        List<String> registered = new ArrayList<>();
        for (RegisteredServer rs : proxyServer.getAllServers()) {
            registered.add(rs.getServerInfo().getName());
        }
        Collections.sort(registered);
        out.put("registeredServers", registered);

        Optional<VelocityPteroPower> opt = vpp();
        out.put("vppLoaded", opt.isPresent());
        if (opt.isPresent()) {
            VelocityPteroPower p = opt.get();
            out.put("apiClientReady", p.getApiClient() != null);
            out.put("panelType", p.getConfigurationManager() != null && p.getConfigurationManager().getPanelType() != null
                    ? p.getConfigurationManager().getPanelType().name()
                    : "unknown");
            out.put("managedServers", p.getServerInfoMap() != null ? new TreeSet<>(p.getServerInfoMap().keySet()) : List.of());
            List<Map<String, Object>> addons = new ArrayList<>();
            if (p.getAddonManager() != null) {
                for (LoadedAddon a : p.getAddonManager().getLoadedAddons()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", a.getMeta() != null ? a.getMeta().id() : "unknown");
                    m.put("version", a.getMeta() != null ? a.getMeta().version() : "");
                    m.put("active", a.isActive());
                    m.put("status", a.getStatus().name());
                    addons.add(m);
                }
            }
            out.put("addons", addons);
        }
        sendJson(ex, 200, out);
    }

    private void handleState(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, Map.of("error", "GET required"));
            return;
        }
        Optional<VelocityPteroPower> opt = vpp();
        if (opt.isEmpty()) {
            sendJson(ex, 503, Map.of("error", "VelocityPteroPower plugin not loaded"));
            return;
        }
        VelocityPteroPower p = opt.get();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("startingServers", new TreeSet<>(p.getStartingServers()));

        Map<String, List<String>> waiting = new TreeMap<>();
        p.getWaitingPlayers().forEach((srv, set) -> {
            List<String> ids = new ArrayList<>();
            if (set != null) {
                set.forEach(u -> ids.add(u.toString()));
            }
            waiting.put(srv, ids);
        });
        out.put("waitingPlayers", waiting);
        out.put("shutdownDeadlines", new TreeMap<>(p.getShutdownDeadlines()));

        if (p.getRateLimitTracker() != null) {
            out.put("rateLimitRemaining", p.getRateLimitTracker().getRemainingRequests());
            out.put("rateLimitMax", p.getRateLimitTracker().getRateLimit());
        }

        if (p.getMaintenanceService() != null) {
            out.put("maintenanceGlobal", p.getMaintenanceService().isGlobal());
            out.put("maintenanceServers", new TreeSet<>(p.getMaintenanceService().getServers()));
        }

        Map<String, String> cacheStates = new TreeMap<>();
        ServerStateCache stateCache = p.getServerStateCache();
        if (stateCache != null && p.getServerInfoMap() != null) {
            for (Map.Entry<String, PteroServerInfo> e : p.getServerInfoMap().entrySet()) {
                ServerStateCache.State st = stateCache.getFreshState(e.getValue().getServerId());
                cacheStates.put(e.getKey(), st != null ? st.name() : "STALE_OR_NONE");
            }
        }
        out.put("serverStateCache", cacheStates);
        out.put("resourceCacheServerIds", resourceCacheKeys(p));
        out.put("messagesLanguage", p.getMessagesManager() != null ? p.getMessagesManager().getLanguage() : null);

        List<Map<String, Object>> players = new ArrayList<>();
        for (Player pl : proxyServer.getAllPlayers()) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("username", pl.getUsername());
            pm.put("uuid", pl.getUniqueId().toString());
            pm.put("server", pl.getCurrentServer()
                    .map(cs -> cs.getServerInfo().getName())
                    .orElse(null));
            players.add(pm);
        }
        out.put("onlinePlayers", players);

        List<Map<String, Object>> addons = new ArrayList<>();
        if (p.getAddonManager() != null) {
            for (LoadedAddon a : p.getAddonManager().getLoadedAddons()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", a.getMeta() != null ? a.getMeta().id() : "unknown");
                m.put("active", a.isActive());
                m.put("status", a.getStatus().name());
                addons.add(m);
            }
        }
        out.put("addons", addons);

        sendJson(ex, 200, out);
    }

    private void handleCommand(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, Map.of("error", "POST required"));
            return;
        }
        JsonObject body = readJsonBody(ex);
        String command = body.has("command") ? body.get("command").getAsString() : "";
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        if (command.isBlank()) {
            sendJson(ex, 400, Map.of("error", "Missing command"));
            return;
        }

        if (body.has("asPlayer") && !body.get("asPlayer").isJsonNull()) {
            String name = body.get("asPlayer").getAsString();
            Optional<Player> playerOpt = proxyServer.getPlayer(name);
            if (playerOpt.isEmpty()) {
                sendJson(ex, 404, Map.of("error", "Player not online: " + name));
                return;
            }
            boolean ok = proxyServer.getCommandManager().executeAsync(playerOpt.get(), command).join();
            sendJson(ex, 200, Map.of("ok", ok, "executedAs", name, "messages", List.of()));
            return;
        }

        boolean allPerms = !body.has("allPermissions") || body.get("allPermissions").getAsBoolean();
        Set<String> perms = new HashSet<>();
        if (body.has("permissions") && body.get("permissions").isJsonArray()) {
            allPerms = false;
            for (JsonElement el : body.getAsJsonArray("permissions")) {
                perms.add(el.getAsString());
            }
        }

        long settleMs = body.has("settleMs") ? body.get("settleMs").getAsLong() : 1500L;
        CapturingCommandSource source = new CapturingCommandSource(allPerms, perms);
        boolean ok = proxyServer.getCommandManager().executeAsync(source, command).join();
        if (settleMs > 0) {
            long deadline = System.currentTimeMillis() + settleMs;
            while (System.currentTimeMillis() < deadline) {
                long quietMs = System.currentTimeMillis() - source.lastMessageAtMs;
                if (source.messages.size() > 1 && quietMs >= 300L) {
                    break;
                }
                if (source.messages.size() <= 1 && quietMs >= 750L) {
                    break;
                }
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        sendJson(ex, 200, Map.of("ok", ok, "messages", new ArrayList<>(source.messages)));
    }

    private void handlePermissions(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, Map.of("error", "POST required"));
            return;
        }
        JsonObject body = readJsonBody(ex);
        if (!body.has("username")) {
            sendJson(ex, 400, Map.of("error", "Missing username"));
            return;
        }
        String user = body.get("username").getAsString().toLowerCase(Locale.ROOT);
        Set<String> set = ConcurrentHashMap.newKeySet();
        if (body.has("permissions") && body.get("permissions").isJsonArray()) {
            JsonArray arr = body.getAsJsonArray("permissions");
            for (JsonElement el : arr) {
                set.add(el.getAsString());
            }
        }
        playerPermissions.put(user, set);
        sendJson(ex, 200, Map.of("ok", true, "username", user, "permissions", new ArrayList<>(set)));
    }

    private void handleMaintenanceProvider(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, Map.of("error", "POST required"));
            return;
        }
        VPPApi api = VPPApiProvider.get();
        if (api == null) {
            sendJson(ex, 503, Map.of("error", "VPPApi not initialized"));
            return;
        }
        JsonObject body = readJsonBody(ex);
        boolean enabled = body.has("enabled") && body.get("enabled").getAsBoolean();
        boolean global = body.has("global") && body.get("global").getAsBoolean();
        String detail = body.has("detail") ? body.get("detail").getAsString() : " (external-spi)";
        Set<String> servers = new HashSet<>();
        if (body.has("servers") && body.get("servers").isJsonArray()) {
            for (JsonElement el : body.getAsJsonArray("servers")) {
                servers.add(el.getAsString().toLowerCase(Locale.ROOT));
            }
        }

        if (!syntheticMaintenanceRegistered) {
            api.registerMaintenanceProvider(syntheticMaintenance);
            syntheticMaintenanceRegistered = true;
        }
        syntheticMaintenance.configure(enabled, global, servers, detail);
        sendJson(ex, 200, Map.of("ok", true, "enabled", enabled, "global", global, "servers", servers));
    }

    private void handleRoutingProvider(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, Map.of("error", "POST required"));
            return;
        }
        VPPApi api = VPPApiProvider.get();
        if (api == null) {
            sendJson(ex, 503, Map.of("error", "VPPApi not initialized"));
            return;
        }
        JsonObject body = readJsonBody(ex);
        boolean enabled = body.has("enabled") && body.get("enabled").getAsBoolean();
        String target = body.has("overrideTarget") && !body.get("overrideTarget").isJsonNull()
                ? body.get("overrideTarget").getAsString()
                : null;
        boolean cancel = body.has("cancel") && body.get("cancel").getAsBoolean();

        syntheticRouting.configure(enabled, target, cancel);
        if (enabled && !syntheticRoutingRegistered) {
            api.registerRoutingProvider(syntheticRouting, 500);
            syntheticRoutingRegistered = true;
        } else if (!enabled && syntheticRoutingRegistered) {
            api.unregisterRoutingProvider(syntheticRouting);
            syntheticRoutingRegistered = false;
        }
        sendJson(ex, 200, Map.of("ok", true, "enabled", enabled, "overrideTarget", String.valueOf(target), "cancel", cancel));
    }

    private void handleShutdown(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, Map.of("error", "POST required"));
            return;
        }
        sendJson(ex, 200, Map.of("ok", true, "shuttingDown", true));
        proxyServer.getScheduler()
                .buildTask(this, () -> proxyServer.shutdown())
                .delay(250, TimeUnit.MILLISECONDS)
                .schedule();
    }

    /** Server ids currently held in the API client's resource-usage cache (warmed by resourcePrefetch). */
    @SuppressWarnings("unchecked")
    private static List<String> resourceCacheKeys(VelocityPteroPower p) {
        try {
            Object client = p.getApiClient();
            // Unwrap EventingPanelAPIClient (or any decorator) to the concrete HTTP client
            for (int depth = 0; depth < 3 && client != null; depth++) {
                java.lang.reflect.Field delegate = null;
                try {
                    delegate = client.getClass().getDeclaredField("delegate");
                } catch (NoSuchFieldException ignored) {
                    break;
                }
                delegate.setAccessible(true);
                client = delegate.get(client);
            }
            for (Class<?> c = client.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField("resourceCache");
                    f.setAccessible(true);
                    return new ArrayList<>(new TreeSet<>(((Map<String, ?>) f.get(client)).keySet()));
                } catch (NoSuchFieldException ignored) {
                    // try superclass
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return List.of();
    }

    /** POST {command, permissions?} → tab-complete suggestions as Velocity would offer them. */
    private void handleSuggest(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, Map.of("error", "POST required"));
            return;
        }
        JsonObject body = readJsonBody(ex);
        String command = body.has("command") ? body.get("command").getAsString() : "";
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        boolean allPerms = !body.has("allPermissions") || body.get("allPermissions").getAsBoolean();
        Set<String> perms = new HashSet<>();
        if (body.has("permissions") && body.get("permissions").isJsonArray()) {
            allPerms = false;
            for (JsonElement el : body.getAsJsonArray("permissions")) {
                perms.add(el.getAsString());
            }
        }
        CapturingCommandSource source = new CapturingCommandSource(allPerms, perms);
        List<String> suggestions = proxyServer.getCommandManager().offerSuggestions(source, command).join();
        sendJson(ex, 200, Map.of("suggestions", suggestions));
    }

    /**
     * GET ?path=rel → file content; POST {path, content} → write. Paths are relative to the VPP
     * data directory (plugins/velocity-ptero-power) and may not escape it. Used by the suite to flip
     * config values before /ptero reload and to rewrite addon configs.
     */
    private void handleFile(HttpExchange ex) throws IOException {
        java.nio.file.Path root = java.nio.file.Paths.get("plugins", "velocity-ptero-power").toAbsolutePath().normalize();
        String rel;
        String content = null;
        if ("GET".equalsIgnoreCase(ex.getRequestMethod())) {
            String query = ex.getRequestURI().getRawQuery();
            rel = query != null && query.startsWith("path=")
                    ? java.net.URLDecoder.decode(query.substring(5), StandardCharsets.UTF_8) : "";
        } else if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
            JsonObject body = readJsonBody(ex);
            rel = body.has("path") ? body.get("path").getAsString() : "";
            content = body.has("content") ? body.get("content").getAsString() : null;
        } else {
            sendJson(ex, 405, Map.of("error", "GET or POST required"));
            return;
        }
        java.nio.file.Path target = root.resolve(rel).normalize();
        if (rel.isBlank() || !target.startsWith(root)) {
            sendJson(ex, 400, Map.of("error", "path must stay inside " + root));
            return;
        }
        if (content == null) {
            if (!java.nio.file.Files.isRegularFile(target)) {
                sendJson(ex, 404, Map.of("error", "not found", "path", rel));
                return;
            }
            sendJson(ex, 200, Map.of("path", rel, "content",
                    java.nio.file.Files.readString(target, StandardCharsets.UTF_8)));
            return;
        }
        java.nio.file.Files.createDirectories(target.getParent());
        java.nio.file.Files.writeString(target, content, StandardCharsets.UTF_8);
        sendJson(ex, 200, Map.of("ok", true, "path", rel, "bytes", content.length()));
    }

    private static JsonObject readJsonBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            if (raw.isEmpty()) {
                return new JsonObject();
            }
            return JsonParser.parseString(raw).getAsJsonObject();
        }
    }

    private static void sendJson(HttpExchange ex, int status, Object payload) throws IOException {
        byte[] bytes = GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static int parsePort(String raw, int fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static final class CapturingCommandSource implements CommandSource {
        private final boolean allPermissions;
        private final Set<String> granted;
        final List<String> messages = new CopyOnWriteArrayList<>();
        volatile long lastMessageAtMs = System.currentTimeMillis();

        CapturingCommandSource(boolean allPermissions, Set<String> granted) {
            this.allPermissions = allPermissions;
            this.granted = granted;
        }

        @Override
        public Tristate getPermissionValue(String permission) {
            if (allPermissions || granted.contains("*") || granted.contains(permission)) {
                return Tristate.TRUE;
            }
            return Tristate.FALSE;
        }

        @Override
        public void sendMessage(@NonNull Component message) {
            messages.add(PLAIN.serialize(message));
            lastMessageAtMs = System.currentTimeMillis();
        }
    }

    private static final class SyntheticMaintenanceProvider implements MaintenanceProvider {
        private final CopyOnWriteArrayList<MaintenanceClearListener> listeners = new CopyOnWriteArrayList<>();
        private volatile boolean active = false;
        private volatile boolean global = false;
        private volatile Set<String> blockedServers = Set.of();
        private volatile String detail = " (external-spi)";

        void configure(boolean enabled, boolean global, Set<String> servers, String detail) {
            boolean wasActive = this.active;
            Set<String> prevServers = this.blockedServers;
            this.active = enabled;
            this.global = global;
            this.blockedServers = Set.copyOf(servers);
            this.detail = detail == null ? "" : detail;

            if (wasActive && !enabled) {
                for (MaintenanceClearListener l : listeners) {
                    l.onMaintenanceCleared(null);
                }
            } else if (wasActive && !global) {
                for (String prev : prevServers) {
                    if (!this.blockedServers.contains(prev)) {
                        for (MaintenanceClearListener l : listeners) {
                            l.onMaintenanceCleared(prev);
                        }
                    }
                }
            }
        }

        @Override
        public String id() {
            return "e2e-synthetic-maintenance";
        }

        @Override
        public int priority() {
            return 200;
        }

        @Override
        public boolean isActive() {
            return active;
        }

        @Override
        public boolean blocks(MaintenanceQuery query) {
            if (!active || query == null || query.serverName() == null) return false;
            return global || blockedServers.contains(query.serverName().toLowerCase(Locale.ROOT));
        }

        @Override
        public String detail(MaintenanceQuery query) {
            return detail;
        }

        @Override
        public void addClearListener(MaintenanceClearListener listener) {
            if (listener != null) listeners.add(listener);
        }

        @Override
        public void removeClearListener(MaintenanceClearListener listener) {
            listeners.remove(listener);
        }
    }

    private static final class SyntheticRoutingProvider implements RoutingProvider {
        private volatile boolean enabled = false;
        private volatile String overrideTarget = null;
        private volatile boolean cancel = false;

        void configure(boolean enabled, String overrideTarget, boolean cancel) {
            this.enabled = enabled;
            this.overrideTarget = overrideTarget;
            this.cancel = cancel;
        }

        @Override
        public boolean selectTarget(PlayerRouteContext ctx) {
            if (!enabled) return false;
            if (cancel) {
                ctx.setCancelled(true);
                return true;
            }
            if (overrideTarget != null && !overrideTarget.isBlank()) {
                ctx.setTargetServer(overrideTarget);
                return true;
            }
            return false;
        }
    }
}
