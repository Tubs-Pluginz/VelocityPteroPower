/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.vpp.addon.scheduler;

import de.tubyoub.vpp.api.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SchedulerAddonTest {

    private SchedulerAddon addon;

    @AfterEach
    void tearDown() {
        if (addon != null) addon.onDisable();
    }

    @Test
    void loadsValidUnixCronSchedulesAndRespondsToListCommand() {
        addon = new SchedulerAddon();
        AddonContext ctx = mock(AddonContext.class);
        AddonConfig cfg = mock(AddonConfig.class);
        AddonLogger logger = mock(AddonLogger.class);
        VPPApi api = mock(VPPApi.class);
        ServerControl control = mock(ServerControl.class);

        when(ctx.getConfig()).thenReturn(cfg);
        when(ctx.getLogger()).thenReturn(logger);
        when(ctx.getApi()).thenReturn(api);
        when(api.getServerControl()).thenReturn(control);
        when(cfg.getString(eq("timezone"), anyString())).thenReturn("UTC");
        when(cfg.getString(eq("cronHelpUrl"), anyString())).thenReturn("https://crontab.guru/");
        when(cfg.getInt(eq("checkIntervalSeconds"), anyInt())).thenReturn(60);
        when(cfg.getBoolean(eq("enabled"), anyBoolean())).thenReturn(true);
        when(cfg.getMapList("schedules")).thenReturn(List.of(
                Map.of(
                        "name", "nightly-stop",
                        "enabled", true,
                        "cron", "0 4 * * *",
                        "action", "stop",
                        "servers", List.of("survival")
                ),
                Map.of(
                        "name", "broken-cron",
                        "enabled", true,
                        "cron", "not-a-cron",
                        "action", "start",
                        "servers", List.of("survival")
                )
        ));

        ArgumentCaptor<AddonCommand> cmdCaptor = ArgumentCaptor.forClass(AddonCommand.class);
        addon.onEnable(ctx);
        verify(ctx).registerCommand(cmdCaptor.capture());

        CommandActor actor = mock(CommandActor.class);
        cmdCaptor.getValue().execute(actor, new String[]{"list"});

        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(actor, atLeastOnce()).sendMessage(msgCaptor.capture());
        assertThat(msgCaptor.getAllValues()).anyMatch(m -> m.contains("nightly-stop") && m.contains("survival"));
        assertThat(msgCaptor.getAllValues()).noneMatch(m -> m.contains("broken-cron"));
    }
}
