/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import de.tubyoub.velocitypteropower.VelocityPteroPower;
import de.tubyoub.velocitypteropower.manager.ConfigurationManager;
import de.tubyoub.velocitypteropower.manager.ConfigurationManager.ServerCheckMethod;
import de.tubyoub.velocitypteropower.model.PteroServerInfo;
import de.tubyoub.velocitypteropower.util.FilteredComponentLogger;
import de.tubyoub.velocitypteropower.util.RateLimitTracker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * serverStatusCheckMethod VELOCITY_PING / PANEL_API / HYBRID (+ per-server override) against
 * a real Pterodactyl client, and how a live HTTP 429 from the panel gates later requests.
 */
class StatusCheckAndRateLimitTest {

    private HttpServer server;
    private ConfigurationManager config;
    private ProxyServer proxy;
    private RateLimitTracker tracker;
    private VelocityPteroPower plugin;
    private final Map<String, PteroServerInfo> servers = new HashMap<>();
    private final AtomicReference<String> panelState = new AtomicReference<>("running");
    private final AtomicInteger panelCalls = new AtomicInteger();
    private final AtomicInteger powerCalls = new AtomicInteger();
    private final AtomicInteger nextStatus = new AtomicInteger(200);
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private PterodactylAPIClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/client/", this::handle);
        server.start();

        plugin = mock(VelocityPteroPower.class);
        config = mock(ConfigurationManager.class);
        proxy = mock(ProxyServer.class);
        FilteredComponentLogger logger = mock(FilteredComponentLogger.class);
        when(plugin.getFilteredLogger()).thenReturn(logger);
        when(plugin.getConfigurationManager()).thenReturn(config);
        when(plugin.getProxyServer()).thenReturn(proxy);
        when(plugin.getServerInfoMap()).thenReturn(servers);
        when(config.getPterodactylUrl()).thenReturn("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        when(config.getPterodactylApiKey()).thenReturn("ptlc_key");
        when(config.getPanelType()).thenReturn(PanelType.pterodactyl);
        when(config.getApiThreads()).thenReturn(2);
        when(config.getPingTimeout()).thenReturn(500);
        when(config.getRateLimitProbeFallbackSeconds()).thenReturn(60);
        when(config.resolveCheckMethod(any())).thenAnswer(inv -> {
            PteroServerInfo info = inv.getArgument(0);
            return info != null && info.getCheckMethodOverride() != null
                    ? info.getCheckMethodOverride() : config.getServerCheckMethod();
        });
        tracker = new RateLimitTracker(logger, config);
        when(plugin.getRateLimitTracker()).thenReturn(tracker);

        servers.put("survival", new PteroServerInfo("srv-1", 30, 0, false));
        client = new PterodactylAPIClient(plugin);
    }

    @AfterEach
    void tearDown() {
        client.shutdown();
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        paths.add(path);
        int status = nextStatus.get();
        if (status == 429) {
            ex.getResponseHeaders().set("Retry-After", "30");
            ex.getResponseHeaders().set("X-RateLimit-Limit", "240");
            send(ex, 429, "{\"errors\":[{\"code\":\"TooManyRequestsHttpException\"}]}");
            return;
        }
        ex.getResponseHeaders().set("X-RateLimit-Limit", "240");
        ex.getResponseHeaders().set("X-RateLimit-Remaining", "200");
        if (path.endsWith("/resources")) {
            panelCalls.incrementAndGet();
            send(ex, 200, "{\"attributes\":{\"current_state\":\"" + panelState.get() + "\",\"resources\":{}}}");
        } else if (path.endsWith("/power")) {
            powerCalls.incrementAndGet();
            ex.sendResponseHeaders(204, -1);
            ex.close();
        } else {
            send(ex, 404, "{}");
        }
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void velocityPing(boolean reachable) {
        RegisteredServer rs = mock(RegisteredServer.class);
        CompletableFuture<ServerPing> ping = reachable
                ? CompletableFuture.completedFuture(mock(ServerPing.class))
                : CompletableFuture.failedFuture(new IOException("connection refused"));
        when(rs.ping()).thenReturn(ping);
        when(proxy.getServer("survival")).thenReturn(Optional.of(rs));
    }

    @Test
    void velocityPingNeverCallsThePanel() {
        when(config.getServerCheckMethod()).thenReturn(ServerCheckMethod.VELOCITY_PING);
        velocityPing(true);
        assertThat(client.isServerOnline("survival", "srv-1")).isTrue();
        assertThat(panelCalls).hasValue(0);
    }

    @Test
    void velocityPingReportsOfflineWhenUnreachableEvenIfPanelSaysRunning() {
        when(config.getServerCheckMethod()).thenReturn(ServerCheckMethod.VELOCITY_PING);
        velocityPing(false);
        assertThat(client.isServerOnline("survival", "srv-1")).isFalse();
        assertThat(panelCalls).hasValue(0);
    }

    @Test
    void panelApiIgnoresVelocityPing() {
        when(config.getServerCheckMethod()).thenReturn(ServerCheckMethod.PANEL_API);
        velocityPing(false);
        assertThat(client.isServerOnline("survival", "srv-1")).isTrue();
        assertThat(panelCalls).hasValue(1);
        verify(proxy, never()).getServer("survival");

        panelState.set("starting");
        assertThat(client.isServerOnline("survival", "srv-1")).isFalse();
    }

    @Test
    void hybridNeedsBothPanelRunningAndAPing() {
        when(config.getServerCheckMethod()).thenReturn(ServerCheckMethod.HYBRID);
        velocityPing(false);
        assertThat(client.isServerOnline("survival", "srv-1")).isFalse();

        panelState.set("offline");
        assertThat(client.isServerOnline("survival", "srv-1")).isFalse();
    }

    @Test
    void perServerOverrideBeatsTheGlobalMethod() {
        when(config.getServerCheckMethod()).thenReturn(ServerCheckMethod.HYBRID);
        servers.put("survival", new PteroServerInfo("srv-1", 30, 0, false,
                null, null, ServerCheckMethod.VELOCITY_PING, null, null, List.of(), null));
        velocityPing(true);
        panelState.set("offline");
        assertThat(client.isServerOnline("survival", "srv-1")).isTrue();
        assertThat(panelCalls).hasValue(0);
    }

    @Test
    void live429BlocksFurtherRequestsUntilRetryAfter() {
        when(config.getServerCheckMethod()).thenReturn(ServerCheckMethod.PANEL_API);
        assertThat(tracker.canMakeRequest()).isTrue();

        nextStatus.set(429);
        assertThat(client.isServerOnline("survival", "srv-1")).isFalse();
        assertThat(tracker.getRemainingRequests()).isZero();
        assertThat(tracker.secondsUntilReset()).isBetween(25L, 30L);

        // Panel recovers, but VPP must not hammer it before Retry-After elapses
        nextStatus.set(200);
        int before = paths.size();
        assertThat(tracker.canMakeRequest()).isFalse();
        client.powerServer("srv-1", PowerSignal.START);
        assertThat(client.isServerOnline("survival", "srv-1")).isFalse();
        assertThat(paths).hasSize(before);
        assertThat(powerCalls).hasValue(0);
    }

    @Test
    void live429OnPowerDoesNotThrowAndRecordsTheWindow() {
        nextStatus.set(429);
        client.powerServer("srv-1", PowerSignal.START);
        assertThat(tracker.getRemainingRequests()).isZero();
        assertThat(tracker.canMakeRequest(de.tubyoub.velocitypteropower.util.PanelRequestPriority.BACKGROUND)).isFalse();
    }

    @Test
    void staleOnlinePingIsReverifiedBeforeSayingYes() throws Exception {
        when(config.getServerCheckMethod()).thenReturn(ServerCheckMethod.VELOCITY_PING);
        velocityPing(true);
        assertThat(client.isServerOnline("survival", "srv-1")).isTrue();

        Thread.sleep(2_100); // past the 2s ping cache TTL
        velocityPing(false); // server was stopped meanwhile
        assertThat(client.isServerOnline("survival", "srv-1")).isFalse();
    }
}
