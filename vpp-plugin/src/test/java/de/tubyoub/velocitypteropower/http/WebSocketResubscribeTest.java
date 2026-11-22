/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.http;

import com.sun.net.httpserver.HttpServer;
import com.velocitypowered.api.proxy.ProxyServer;
import de.tubyoub.velocitypteropower.VelocityPteroPower;
import de.tubyoub.velocitypteropower.manager.ConfigurationManager;
import de.tubyoub.velocitypteropower.model.PteroServerInfo;
import de.tubyoub.velocitypteropower.service.ServerStateCache;
import de.tubyoub.velocitypteropower.util.FilteredComponentLogger;
import de.tubyoub.velocitypteropower.util.RateLimitTracker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.http.WebSocket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** After Wings sends "token expired", the next refresh pass fetches a new token for that server only. */
class WebSocketResubscribeTest {

    private HttpServer panel;
    private final List<String> tokenRequests = new CopyOnWriteArrayList<>();
    private PanelWebSocketService service;

    @BeforeEach
    void setUp() throws Exception {
        panel = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        panel.createContext("/api/client/servers/", ex -> {
            tokenRequests.add(ex.getRequestURI().getPath());
            // Deliberately fail so no real socket is opened; we only care that a new token is requested
            ex.sendResponseHeaders(503, -1);
            ex.close();
        });
        panel.start();

        VelocityPteroPower plugin = mock(VelocityPteroPower.class);
        ConfigurationManager config = mock(ConfigurationManager.class);
        FilteredComponentLogger logger = mock(FilteredComponentLogger.class);
        when(plugin.getFilteredLogger()).thenReturn(logger);
        when(plugin.getConfigurationManager()).thenReturn(config);
        when(plugin.getProxyServer()).thenReturn(mock(ProxyServer.class));
        when(plugin.getServerInfoMap()).thenReturn(Map.of(
                "survival", new PteroServerInfo("srv-1", 30, 0, false),
                "lobby", new PteroServerInfo("srv-2", -1, 0, false)));
        when(config.getPterodactylUrl()).thenReturn("http://127.0.0.1:" + panel.getAddress().getPort() + "/");
        when(config.getPterodactylApiKey()).thenReturn("ptlc_key");
        when(config.getPanelType()).thenReturn(PanelType.pterodactyl);
        when(plugin.getRateLimitTracker()).thenReturn(new RateLimitTracker(logger, config));
        service = new PanelWebSocketService(plugin, new ServerStateCache(30));
    }

    @AfterEach
    void tearDown() {
        panel.stop(0);
    }

    @Test
    void tokenExpiredServerIsResubscribedOnTheNextRefresh() throws Exception {
        WebSocket survivalSocket = mock(WebSocket.class);
        WebSocket lobbySocket = mock(WebSocket.class);
        sockets().put("srv-1", survivalSocket);
        sockets().put("srv-2", lobbySocket);

        Method handle = PanelWebSocketService.class.getDeclaredMethod(
                "handleMessage", WebSocket.class, String.class, String.class);
        handle.setAccessible(true);
        handle.invoke(service, survivalSocket, "srv-1", "{\"event\":\"token expired\"}");

        Method refresh = PanelWebSocketService.class.getDeclaredMethod("ensureSubscriptions");
        refresh.setAccessible(true);
        refresh.invoke(service);

        assertThat(tokenRequests).containsExactly("/api/client/servers/srv-1/websocket");
        assertThat(sockets()).containsKey("srv-2").doesNotContainKey("srv-1");
    }

    @SuppressWarnings("unchecked")
    private Map<String, WebSocket> sockets() throws Exception {
        Field field = PanelWebSocketService.class.getDeclaredField("sockets");
        field.setAccessible(true);
        return (Map<String, WebSocket>) field.get(service);
    }
}
