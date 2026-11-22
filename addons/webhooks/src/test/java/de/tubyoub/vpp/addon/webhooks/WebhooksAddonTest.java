/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.vpp.addon.webhooks;

import com.sun.net.httpserver.HttpServer;
import de.tubyoub.vpp.api.AddonConfig;
import de.tubyoub.vpp.api.AddonContext;
import de.tubyoub.vpp.api.AddonLogger;
import de.tubyoub.vpp.api.event.ServerPowerSignalEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WebhooksAddonTest {

    private HttpServer sink;
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private WebhooksAddon addon;

    @BeforeEach
    void setUp() throws IOException {
        sink = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        sink.createContext("/hook", ex -> {
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        sink.start();
        addon = new WebhooksAddon();
    }

    @AfterEach
    void tearDown() {
        if (addon != null) addon.onDisable();
        if (sink != null) sink.stop(0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void postsGenericJsonPayloadAndSkipsCancelledPowerEvents() {
        AddonContext ctx = mock(AddonContext.class);
        AddonConfig cfg = mock(AddonConfig.class);
        AddonLogger logger = mock(AddonLogger.class);

        String url = "http://127.0.0.1:" + sink.getAddress().getPort() + "/hook";
        when(ctx.getConfig()).thenReturn(cfg);
        when(ctx.getLogger()).thenReturn(logger);
        when(cfg.getString(eq("format"), anyString())).thenReturn("generic");
        when(cfg.getString(eq("webhookUrl"), anyString())).thenReturn(url);
        when(cfg.getStringList("webhookUrls")).thenReturn(List.of());
        when(cfg.getBoolean(eq("enabled"), anyBoolean())).thenReturn(true);
        when(cfg.getBoolean(eq("events.power"), anyBoolean())).thenReturn(true);
        when(cfg.getBoolean(eq("skipCancelledPower"), anyBoolean())).thenReturn(true);

        final Consumer<ServerPowerSignalEvent>[] powerListener = new Consumer[1];
        doAnswer(inv -> {
            powerListener[0] = inv.getArgument(1);
            return null;
        }).when(ctx).subscribe(eq(ServerPowerSignalEvent.class), any());

        addon.onEnable(ctx);
        assertThat(powerListener[0]).isNotNull();

        ServerPowerSignalEvent cancelled = new ServerPowerSignalEvent("survival", "srv-1", "start");
        cancelled.setCancelled(true);
        powerListener[0].accept(cancelled);

        ServerPowerSignalEvent active = new ServerPowerSignalEvent("survival", "srv-1", "start");
        powerListener[0].accept(active);

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(bodies).hasSize(1);
            assertThat(bodies.get(0))
                    .contains("\"event\":\"power\"")
                    .contains("\"serverName\":\"survival\"")
                    .contains("\"signal\":\"start\"");
        });
    }
}
