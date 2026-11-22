/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.http;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.tubyoub.velocitypteropower.VelocityPteroPower;
import de.tubyoub.velocitypteropower.model.PteroServerInfo;
import de.tubyoub.velocitypteropower.service.ServerStateCache;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Subscribes to Pterodactyl/Pelican/Calagopus Wings websockets for live server state.
 * Falls back gracefully when unavailable; McServerSoft is unsupported.
 */
public class PanelWebSocketService {

    private final VelocityPteroPower plugin;
    private final ComponentLogger logger;
    private final ServerStateCache stateCache;
    private final HttpClient httpClient;
    private final Map<String, WebSocket> sockets = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    public PanelWebSocketService(VelocityPteroPower plugin, ServerStateCache stateCache) {
        this.plugin = plugin;
        this.logger = plugin.getFilteredLogger();
        this.stateCache = stateCache;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public void start() {
        if (!plugin.getConfigurationManager().isPanelWebSocketEnabled()) {
            logger.info("Panel WebSocket live state disabled by config.");
            return;
        }
        PanelType type = plugin.getConfigurationManager().getPanelType();
        if (type == PanelType.mcServerSoft || type == PanelType.error) {
            logger.info("Panel WebSocket not supported for panel type {}.", type);
            return;
        }
        running.set(true);
        scheduleRefresh();
        logger.info("Panel WebSocket live-state service started.");
    }

    public void stop() {
        running.set(false);
        for (WebSocket ws : sockets.values()) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown").join();
            } catch (Exception ignored) {}
        }
        sockets.clear();
        stateCache.clear();
    }

    private void scheduleRefresh() {
        if (!running.get()) return;
        plugin.getProxyServer().getScheduler()
                .buildTask(plugin, this::ensureSubscriptions)
                .delay(5, TimeUnit.SECONDS)
                .schedule();
    }

    private void ensureSubscriptions() {
        try {
            Map<String, PteroServerInfo> map = plugin.getServerInfoMap();
            if (map == null) return;
            for (Map.Entry<String, PteroServerInfo> e : map.entrySet()) {
                String serverId = e.getValue().getServerId();
                if (serverId == null || sockets.containsKey(serverId)) continue;
                trySubscribe(e.getKey(), serverId);
            }
        } catch (Exception ex) {
            logger.debug("WebSocket ensureSubscriptions error: {}", ex.toString());
        } finally {
            if (running.get()) {
                plugin.getProxyServer().getScheduler()
                        .buildTask(plugin, this::ensureSubscriptions)
                        .delay(Math.max(30, plugin.getConfigurationManager().getPanelWebSocketRefreshSeconds()), TimeUnit.SECONDS)
                        .schedule();
            }
        }
    }

    private void trySubscribe(String serverName, String serverId) {
        try {
            String base = plugin.getConfigurationManager().getPterodactylUrl();
            String apiKey = plugin.getConfigurationManager().getPterodactylApiKey();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(base + "api/client/servers/" + serverId + "/websocket"))
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();
            plugin.getRateLimitTracker().consumeOne();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            plugin.getRateLimitTracker().updateRateLimitInfo(resp);
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                logger.debug("WebSocket token fetch for {} failed: HTTP {}", serverName, resp.statusCode());
                return;
            }
            String body = resp.body();
            WsCredentials creds = parseCredentials(body);
            if (creds == null || creds.token == null || creds.socket == null) {
                logger.debug("WebSocket credentials missing in response for {}.", serverName);
                return;
            }
            String token = creds.token;
            String socket = normalizeWebSocketUrl(creds.socket, base);
            if (socket == null) {
                logger.warn("Invalid WebSocket URL '{}' returned for {}.", creds.socket, serverName);
                return;
            }
            String origin = resolveOriginHeader(base);
            WebSocket.Listener listener = new WebSocket.Listener() {
                private final StringBuilder buf = new StringBuilder();

                @Override
                public void onOpen(WebSocket webSocket) {
                    webSocket.request(1);
                    webSocket.sendText("{\"event\":\"auth\",\"args\":[\"" + token + "\"]}", true);
                }

                @Override
                public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                    buf.append(data);
                    if (last) {
                        handleMessage(webSocket, serverId, buf.toString());
                        buf.setLength(0);
                    }
                    webSocket.request(1);
                    return null;
                }

                @Override
                public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                    sockets.remove(serverId, webSocket);
                    return null;
                }

                @Override
                public void onError(WebSocket webSocket, Throwable error) {
                    logger.debug("WebSocket error for {}: {}", serverName, error.toString());
                    sockets.remove(serverId, webSocket);
                }
            };
            WebSocket.Builder wsBuilder = httpClient.newWebSocketBuilder();
            if (origin != null) {
                wsBuilder.header("Origin", origin);
            }
            try {
                WebSocket ws = wsBuilder
                        .buildAsync(URI.create(socket), listener)
                        .join();
                sockets.put(serverId, ws);
                logger.debug("Subscribed to Wings websocket for {} ({})", serverName, serverId);
            } catch (Exception wsEx) {
                logger.warn("Failed to connect Wings websocket for {} ({}): {}", serverName, socket, wsEx.toString());
            }
        } catch (Exception ex) {
            plugin.getRateLimitTracker().restoreOne();
            logger.debug("Failed to subscribe websocket for {}: {}", serverName, ex.toString());
        }
    }

    private void handleMessage(WebSocket webSocket, String serverId, String message) {
        try {
            JsonElement rootEl = JsonParser.parseString(message);
            if (!rootEl.isJsonObject()) return;
            JsonObject obj = rootEl.getAsJsonObject();
            String event = obj.has("event") && !obj.get("event").isJsonNull()
                    ? obj.get("event").getAsString()
                    : null;
            if (event == null) return;

            if ("auth success".equalsIgnoreCase(event)) {
                // Ask Wings to immediately push current server state/stats even when offline
                // (empty args array works on both Pterodactyl/Pelican Go Wings and Calagopus wings-rs)
                try {
                    webSocket.sendText("{\"event\":\"send stats\",\"args\":[]}", true);
                } catch (Exception ignored) {}
                return;
            }
            if ("token expired".equalsIgnoreCase(event)) {
                sockets.remove(serverId, webSocket);
                try { webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "token expired"); } catch (Exception ignored) {}
                return;
            }
            if ("status".equalsIgnoreCase(event)) {
                String stateStr = firstArgAsString(obj);
                updateStateFromString(serverId, stateStr);
            } else if ("stats".equalsIgnoreCase(event)) {
                String rawStats = firstArgAsString(obj);
                if (rawStats != null) {
                    try {
                        JsonElement statsEl = JsonParser.parseString(rawStats);
                        if (statsEl.isJsonObject() && statsEl.getAsJsonObject().has("state")) {
                            updateStateFromString(serverId, statsEl.getAsJsonObject().get("state").getAsString());
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
    }

    private void updateStateFromString(String serverId, String stateStr) {
        if (stateStr == null) return;
        String lower = stateStr.trim().toLowerCase();
        if ("running".equals(lower)) {
            stateCache.put(serverId, ServerStateCache.State.RUNNING);
        } else if ("starting".equals(lower)) {
            stateCache.put(serverId, ServerStateCache.State.STARTING);
        } else if ("stopping".equals(lower)) {
            stateCache.put(serverId, ServerStateCache.State.STOPPING);
        } else if ("offline".equals(lower) || "installing".equals(lower)) {
            stateCache.put(serverId, ServerStateCache.State.OFFLINE);
        }
    }

    private static String firstArgAsString(JsonObject obj) {
        if (!obj.has("args") || !obj.get("args").isJsonArray()) return null;
        JsonArray arr = obj.getAsJsonArray("args");
        if (arr.isEmpty() || arr.get(0).isJsonNull()) return null;
        JsonElement first = arr.get(0);
        return first.isJsonPrimitive() ? first.getAsString() : first.toString();
    }

    private static final class WsCredentials {
        final String token;
        final String socket;
        WsCredentials(String token, String socket) {
            this.token = token;
            this.socket = socket;
        }
    }

    private static WsCredentials parseCredentials(String json) {
        try {
            JsonElement el = JsonParser.parseString(json);
            if (!el.isJsonObject()) return null;
            JsonObject root = el.getAsJsonObject();
            JsonObject target = (root.has("data") && root.get("data").isJsonObject())
                    ? root.getAsJsonObject("data")
                    : root;
            String token = target.has("token") && !target.get("token").isJsonNull()
                    ? target.get("token").getAsString()
                    : null;
            // Pterodactyl/Pelican use "socket"; Calagopus OpenAPI returns "url"
            String socket = null;
            if (target.has("socket") && !target.get("socket").isJsonNull()) {
                socket = target.get("socket").getAsString();
            } else if (target.has("url") && !target.get("url").isJsonNull()) {
                socket = target.get("url").getAsString();
            }
            return new WsCredentials(token, socket);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String normalizeWebSocketUrl(String rawSocket, String panelBaseUrl) {
        if (rawSocket == null || rawSocket.isBlank()) return null;
        String trimmed = rawSocket.trim();
        try {
            if (trimmed.startsWith("/")) {
                URI base = URI.create(panelBaseUrl);
                String wsScheme = "https".equalsIgnoreCase(base.getScheme()) ? "wss" : "ws";
                return wsScheme + "://" + base.getAuthority() + trimmed;
            }
            if (trimmed.startsWith("http://")) {
                return "ws://" + trimmed.substring("http://".length());
            }
            if (trimmed.startsWith("https://")) {
                return "wss://" + trimmed.substring("https://".length());
            }
            return trimmed;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String resolveOriginHeader(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) return null;
        try {
            URI u = URI.create(baseUrl);
            if (u.getScheme() == null || u.getAuthority() == null) return null;
            return u.getScheme() + "://" + u.getAuthority();
        } catch (Exception ignored) {
            return null;
        }
    }
}
