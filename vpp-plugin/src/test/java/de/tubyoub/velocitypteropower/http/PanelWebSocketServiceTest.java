/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.http;

import de.tubyoub.velocitypteropower.VelocityPteroPower;
import de.tubyoub.velocitypteropower.service.ServerStateCache;
import de.tubyoub.velocitypteropower.util.FilteredComponentLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.http.WebSocket;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PanelWebSocketServiceTest {

    private ServerStateCache cache;
    private PanelWebSocketService service;
    private Method handleMessage;
    private Method parseCredentials;
    private Method normalizeUrl;

    @BeforeEach
    void setUp() throws Exception {
        VelocityPteroPower plugin = mock(VelocityPteroPower.class);
        when(plugin.getFilteredLogger()).thenReturn(mock(FilteredComponentLogger.class));
        cache = new ServerStateCache(30);
        service = new PanelWebSocketService(plugin, cache);
        handleMessage = PanelWebSocketService.class.getDeclaredMethod(
                "handleMessage", WebSocket.class, String.class, String.class);
        handleMessage.setAccessible(true);
        parseCredentials = PanelWebSocketService.class.getDeclaredMethod("parseCredentials", String.class);
        parseCredentials.setAccessible(true);
        normalizeUrl = PanelWebSocketService.class.getDeclaredMethod(
                "normalizeWebSocketUrl", String.class, String.class);
        normalizeUrl.setAccessible(true);
    }

    @Test
    void statusAndStatsEventsUpdateTheLiveCache() throws Exception {
        WebSocket socket = mock(WebSocket.class);
        handleMessage.invoke(service, socket, "srv-1", "{\"event\":\"status\",\"args\":[\"running\"]}");
        assertThat(cache.getFreshState("srv-1")).isEqualTo(ServerStateCache.State.RUNNING);

        handleMessage.invoke(service, socket, "srv-1",
                "{\"event\":\"stats\",\"args\":[\"{\\\"state\\\":\\\"stopping\\\"}\"]}");
        assertThat(cache.getFreshState("srv-1")).isEqualTo(ServerStateCache.State.STOPPING);
    }

    @Test
    void tokenExpiredDropsTheSocketAndClosesIt() throws Exception {
        WebSocket socket = mock(WebSocket.class);
        sockets().put("srv-1", socket);

        handleMessage.invoke(service, socket, "srv-1", "{\"event\":\"token expired\"}");

        assertThat(sockets()).doesNotContainKey("srv-1");
        verify(socket).sendClose(WebSocket.NORMAL_CLOSURE, "token expired");
    }

    @Test
    void parsesPterodactylAndCalagopusSocketPayloads() throws Exception {
        Object ptero = parseCredentials.invoke(null,
                "{\"data\":{\"token\":\"ptlc-token\",\"socket\":\"wss://wings.example/api/servers/abc/ws\"}}");
        assertThat(field(ptero, "token")).isEqualTo("ptlc-token");
        assertThat(field(ptero, "socket")).isEqualTo("wss://wings.example/api/servers/abc/ws");

        Object calagopus = parseCredentials.invoke(null,
                "{\"token\":\"c7-token\",\"url\":\"https://wings.example/ws\"}");
        assertThat(field(calagopus, "token")).isEqualTo("c7-token");
        assertThat(normalizeUrl.invoke(null, field(calagopus, "socket"), "https://panel.example"))
                .isEqualTo("wss://wings.example/ws");
        assertThat(normalizeUrl.invoke(null, "/api/servers/abc/ws", "http://panel.example:8080"))
                .isEqualTo("ws://panel.example:8080/api/servers/abc/ws");
    }

    @SuppressWarnings("unchecked")
    private Map<String, WebSocket> sockets() throws Exception {
        Field field = PanelWebSocketService.class.getDeclaredField("sockets");
        field.setAccessible(true);
        return (Map<String, WebSocket>) field.get(service);
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
