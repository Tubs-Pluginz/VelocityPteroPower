/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import de.tubyoub.velocitypteropower.VelocityPteroPower;
import de.tubyoub.velocitypteropower.manager.ConfigurationManager;
import de.tubyoub.velocitypteropower.manager.WhitelistManager;
import de.tubyoub.velocitypteropower.model.PteroServerInfo;
import de.tubyoub.velocitypteropower.model.ServerResourceUsage;
import de.tubyoub.velocitypteropower.util.FilteredComponentLogger;
import de.tubyoub.velocitypteropower.util.RateLimitTracker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

/**
 * Contract tests exercising VPP's real Pterodactyl, Pelican, and Calagopus HTTP clients
 * against an in-process localhost HTTP server (MCSS intentionally skipped).
 */
class PanelClientsContractTest {

    private HttpServer server;
    private String baseUrl;
    private VelocityPteroPower plugin;
    private ConfigurationManager config;
    private ProxyServer proxy;
    private RateLimitTracker rateLimitTracker;

    private final List<String> recordedBodies = new CopyOnWriteArrayList<>();
    private final List<String> recordedPaths = new CopyOnWriteArrayList<>();
    private final AtomicInteger resourceCalls = new AtomicInteger(0);

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/";

        plugin = mock(VelocityPteroPower.class);
        config = mock(ConfigurationManager.class);
        proxy = mock(ProxyServer.class);
        FilteredComponentLogger logger = mock(FilteredComponentLogger.class);

        when(plugin.getFilteredLogger()).thenReturn(logger);
        when(plugin.getConfigurationManager()).thenReturn(config);
        when(plugin.getProxyServer()).thenReturn(proxy);
        when(config.getPterodactylUrl()).thenReturn(baseUrl);
        when(config.getApiThreads()).thenReturn(4);
        when(config.getResourceCacheSeconds()).thenReturn(10);
        when(config.getServerCheckMethod()).thenReturn(ConfigurationManager.ServerCheckMethod.PANEL_API);
        when(config.resolveCheckMethod(any())).thenReturn(ConfigurationManager.ServerCheckMethod.PANEL_API);
        when(config.getRateLimitProbeFallbackSeconds()).thenReturn(60);
        when(config.isPrintRateLimit()).thenReturn(false);

        rateLimitTracker = new RateLimitTracker(logger, config);
        when(plugin.getRateLimitTracker()).thenReturn(rateLimitTracker);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void pterodactylClientHandlesAuthPowerResourcesCachingAndWhitelist() throws Exception {
        when(config.getPanelType()).thenReturn(PanelType.pterodactyl);
        when(config.getPterodactylApiKey()).thenReturn("ptlc_test_key_12345");

        server.createContext("/api/client/", ex -> {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            if (!"Bearer ptlc_test_key_12345".equals(auth)) {
                respond(ex, 401, "{\"errors\":[{\"detail\":\"Unauthorized\"}]}");
                return;
            }
            String path = ex.getRequestURI().getPath();
            String query = ex.getRequestURI().getQuery();
            recordedPaths.add(path + (query != null ? "?" + query : ""));

            if (path.endsWith("/power")) {
                recordedBodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                respond(ex, 204, "");
            } else if (path.endsWith("/resources")) {
                resourceCalls.incrementAndGet();
                respond(ex, 200, """
                        {"object":"stats","attributes":{"current_state":"running","is_suspended":false,
                        "resources":{"memory_bytes":536870912,"cpu_absolute":22.5,"disk_bytes":1048576,
                        "network_rx_bytes":100,"network_tx_bytes":200,"uptime":42000}}}""");
            } else if (path.endsWith("/files/contents")) {
                respond(ex, 200, "[{\"uuid\":\"00000000-0000-0000-0000-000000000001\",\"name\":\"Alice\"}]");
            } else if (path.endsWith("/servers/srv-ptero")) {
                respond(ex, 200, "{\"attributes\":{\"limits\":{\"memory\":2048,\"disk\":10240,\"cpu\":200}}}");
            } else {
                respond(ex, 200, "{\"object\":\"list\",\"data\":[]}");
            }
        });
        server.start();

        PterodactylAPIClient client = new PterodactylAPIClient(plugin);
        try {
            assertThat(client.isApiKeyValid("ptlc_test_key_12345")).isTrue();
            assertThat(client.isServerOnline("survival", "srv-ptero")).isTrue();

            client.powerServer("srv-ptero", PowerSignal.START);
            assertThat(recordedBodies).anyMatch(b -> b.contains("\"signal\"") && b.contains("\"start\""));

            ServerResourceUsage usage1 = client.fetchServerResources("srv-ptero").get(5, TimeUnit.SECONDS);
            ServerResourceUsage usage2 = client.fetchServerResources("srv-ptero").get(5, TimeUnit.SECONDS);
            assertThat(usage1.isAvailable()).isTrue();
            assertThat(usage1.getCurrentState()).isEqualTo("running");
            assertThat(usage1.getMemoryLimitBytes()).isEqualTo(2048L * 1024 * 1024);
            assertThat(usage1.getCpuAbsolute()).isEqualTo(22.5);
            assertThat(usage2.getMemoryBytes()).isEqualTo(usage1.getMemoryBytes());
            // 1 call for isServerOnline + 1 call for fetchServerResources (second call served from cache)
            assertThat(resourceCalls.get()).isEqualTo(2);

            // Whitelist integration
            Map<String, PteroServerInfo> map = Map.of("survival", new PteroServerInfo("srv-ptero", 30, 5, true));
            when(plugin.getServerInfoMap()).thenReturn(map);
            when(plugin.getApiClient()).thenReturn(client);
            when(config.getWhitelistCheckInterval()).thenReturn(0);
            when(config.isWhitelistAllowBypass()).thenReturn(true);
            when(proxy.getPlayer(anyString())).thenReturn(Optional.empty());

            WhitelistManager wl = new WhitelistManager(proxy, plugin);
            try {
                wl.initialize();
                await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
                    assertThat(wl.isPlayerWhitelisted("survival", "Alice")).isTrue();
                    assertThat(wl.isPlayerWhitelisted("survival", "Stranger")).isFalse();
                });

                // Bypass permission check
                Player admin = mock(Player.class);
                UUID adminId = UUID.randomUUID();
                when(admin.getUniqueId()).thenReturn(adminId);
                when(admin.hasPermission("ptero.bypass")).thenReturn(true);
                when(proxy.getPlayer("Admin")).thenReturn(Optional.of(admin));
                when(proxy.getPlayer(adminId)).thenReturn(Optional.of(admin));
                assertThat(wl.isPlayerWhitelisted("survival", "Admin")).isTrue();
            } finally {
                wl.shutdown();
            }
        } finally {
            client.shutdown();
        }
    }

    @Test
    void pelicanClientValidatesKeyParsesResourcesAndRejects401() throws Exception {
        when(config.getPanelType()).thenReturn(PanelType.pelican);
        when(config.getPterodactylApiKey()).thenReturn("pacc_valid_pelican_token");

        server.createContext("/api/client/", ex -> {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            if (!"Bearer pacc_valid_pelican_token".equals(auth)) {
                respond(ex, 401, "{\"error\":\"unauthorized\"}");
                return;
            }
            String path = ex.getRequestURI().getPath();
            if (path.endsWith("/power")) {
                recordedBodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                respond(ex, 204, "");
            } else if (path.endsWith("/resources")) {
                respond(ex, 200, """
                        {"attributes":{"current_state":"running","is_suspended":false,
                        "resources":{"memory_bytes":268435456,"cpu_absolute":14.2,"disk_bytes":2048,
                        "network_rx_bytes":10,"network_tx_bytes":20,"uptime":5000}}}""");
            } else if (path.endsWith("/servers/srv-pelican")) {
                respond(ex, 200, "{\"attributes\":{\"limits\":{\"memory\":1024,\"disk\":5120,\"cpu\":100}}}");
            } else {
                respond(ex, 200, "{\"data\":[]}");
            }
        });
        server.start();

        PelicanAPIClient client = new PelicanAPIClient(plugin);
        try {
            assertThat(client.isApiKeyValid("pacc_valid_pelican_token")).isTrue();
            assertThat(client.isServerOnline("lobby", "srv-pelican")).isTrue();
            client.powerServer("srv-pelican", PowerSignal.STOP);
            assertThat(recordedBodies).anyMatch(b -> b.contains("\"signal\"") && b.contains("\"stop\""));

            ServerResourceUsage usage = client.fetchServerResources("srv-pelican").get(5, TimeUnit.SECONDS);
            assertThat(usage.isAvailable()).isTrue();
            assertThat(usage.getMemoryLimitBytes()).isEqualTo(1024L * 1024 * 1024);

            // Invalid key rejected with 401
            when(config.getPterodactylApiKey()).thenReturn("pacc_invalid");
            assertThat(client.isApiKeyValid("pacc_invalid")).isFalse();
        } finally {
            client.shutdown();
        }
    }

    @Test
    void calagopusClientUsesActionFieldStateFieldAndWhitelistSlashFallback() throws Exception {
        when(config.getPanelType()).thenReturn(PanelType.calagopus);
        when(config.getPterodactylApiKey()).thenReturn("c7sp_abcdefghijklmnopqrstuvwxyz0123456789abcdefg");

        server.createContext("/api/client/", ex -> {
            String path = ex.getRequestURI().getPath();
            String query = ex.getRequestURI().getRawQuery();
            recordedPaths.add(path + (query != null ? "?" + query : ""));

            if (path.equals("/api/client/account")) {
                respond(ex, 200, "{\"user\":{\"username\":\"admin\"}}");
            } else if (path.endsWith("/power")) {
                recordedBodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                respond(ex, 200, "{}");
            } else if (path.endsWith("/resources")) {
                respond(ex, 200, """
                        {"resources":{"state":"running","is_suspended":false,"memory_bytes":134217728,
                        "memory_limit_bytes":1073741824,"cpu_absolute":9.5,"cpu_limit_absolute":200.0,
                        "disk_bytes":4096,"rx_bytes":50,"tx_bytes":60,"uptime":9999}}""");
            } else if (path.endsWith("/files/contents")) {
                // First request tries %2Fwhitelist.json -> return 404 to test fallback to whitelist.json!
                if (query != null && query.contains("%2F")) {
                    respond(ex, 404, "{\"error\":\"not found\"}");
                } else {
                    respond(ex, 200, "[{\"name\":\"Bob\"}]");
                }
            } else if (path.endsWith("/servers/uuid-calag-1")) {
                respond(ex, 200, "{\"server\":{\"limits\":{\"disk\":4096}}}");
            } else {
                respond(ex, 404, "{}");
            }
        });
        server.start();

        CalagopusAPIClient client = new CalagopusAPIClient(plugin);
        try {
            assertThat(client.isApiKeyValid(config.getPterodactylApiKey())).isTrue();
            assertThat(client.isServerOnline("survival", "uuid-calag-1")).isTrue();

            // Calagopus must send {"action": "start"} instead of {"signal": "start"}
            client.powerServer("uuid-calag-1", PowerSignal.START);
            assertThat(recordedBodies).anyMatch(b -> b.contains("\"action\"") && !b.contains("\"signal\""));

            ServerResourceUsage usage = client.fetchServerResources("uuid-calag-1").get(5, TimeUnit.SECONDS);
            assertThat(usage.isAvailable()).isTrue();
            assertThat(usage.getCurrentState()).isEqualTo("running");
            assertThat(usage.getMemoryBytes()).isEqualTo(134217728L);
            assertThat(usage.getCpuLimitPercent()).isEqualTo(200.0);

            String whitelist = client.fetchWhitelistFile("uuid-calag-1").get(5, TimeUnit.SECONDS);
            assertThat(whitelist).contains("Bob");
            assertThat(recordedPaths).anyMatch(p -> p.contains("file=%2Fwhitelist.json"));
            assertThat(recordedPaths).anyMatch(p -> p.contains("file=whitelist.json"));
        } finally {
            client.shutdown();
        }
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.getResponseHeaders().set("X-RateLimit-Limit", "240");
        ex.getResponseHeaders().set("X-RateLimit-Remaining", "239");
        if (status == 204) {
            ex.sendResponseHeaders(204, -1);
            ex.close();
            return;
        }
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
