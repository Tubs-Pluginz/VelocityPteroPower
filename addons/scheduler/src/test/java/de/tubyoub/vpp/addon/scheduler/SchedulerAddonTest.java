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

    @Test
    void tickFiresADueCronOnceAndSkipsDisabledSchedules() throws Exception {
        addon = new SchedulerAddon();
        AddonContext ctx = mock(AddonContext.class);
        AddonConfig cfg = mock(AddonConfig.class);
        VPPApi api = mock(VPPApi.class);
        ServerControl control = mock(ServerControl.class);
        when(ctx.getConfig()).thenReturn(cfg);
        when(ctx.getLogger()).thenReturn(mock(AddonLogger.class));
        when(ctx.getApi()).thenReturn(api);
        when(api.getServerControl()).thenReturn(control);
        when(cfg.getString(eq("timezone"), anyString())).thenReturn("UTC");
        when(cfg.getString(eq("cronHelpUrl"), anyString())).thenReturn("https://crontab.guru/");
        when(cfg.getInt(eq("checkIntervalSeconds"), anyInt())).thenReturn(60);
        when(cfg.getBoolean(eq("enabled"), anyBoolean())).thenReturn(true);
        when(cfg.getMapList("schedules")).thenReturn(List.of(
                Map.of("name", "every-minute-start", "enabled", true, "cron", "* * * * *",
                        "action", "start", "servers", List.of("skywars", "bedwars")),
                Map.of("name", "every-minute-stop", "enabled", true, "cron", "* * * * *",
                        "action", "stop", "servers", "survival"),
                Map.of("name", "disabled", "enabled", false, "cron", "* * * * *",
                        "action", "start", "servers", List.of("limbo")),
                Map.of("name", "far-away", "enabled", true, "cron", "0 0 1 1 *",
                        "action", "start", "servers", List.of("lobby"))
        ));
        addon.onEnable(ctx);

        java.lang.reflect.Method tick = SchedulerAddon.class.getDeclaredMethod("tick");
        tick.setAccessible(true);
        tick.invoke(addon);
        tick.invoke(addon); // same cron minute: must not fire twice

        verify(control, times(1)).startByName("skywars");
        verify(control, times(1)).startByName("bedwars");
        verify(control, times(1)).stopByName("survival");
        verify(control, never()).startByName("limbo");
        verify(control, never()).startByName("lobby");
    }

    @Test
    void tickDoesNothingWhenTheAddonIsDisabledInConfig() throws Exception {
        addon = new SchedulerAddon();
        AddonContext ctx = mock(AddonContext.class);
        AddonConfig cfg = mock(AddonConfig.class);
        VPPApi api = mock(VPPApi.class);
        ServerControl control = mock(ServerControl.class);
        when(ctx.getConfig()).thenReturn(cfg);
        when(ctx.getLogger()).thenReturn(mock(AddonLogger.class));
        when(ctx.getApi()).thenReturn(api);
        when(api.getServerControl()).thenReturn(control);
        when(cfg.getString(eq("timezone"), anyString())).thenReturn("UTC");
        when(cfg.getString(eq("cronHelpUrl"), anyString())).thenReturn("https://crontab.guru/");
        when(cfg.getInt(eq("checkIntervalSeconds"), anyInt())).thenReturn(60);
        when(cfg.getBoolean(eq("enabled"), anyBoolean())).thenReturn(false);
        when(cfg.getMapList("schedules")).thenReturn(List.of(
                Map.of("name", "every-minute", "enabled", true, "cron", "* * * * *",
                        "action", "start", "servers", List.of("skywars"))));
        addon.onEnable(ctx);

        java.lang.reflect.Method tick = SchedulerAddon.class.getDeclaredMethod("tick");
        tick.setAccessible(true);
        tick.invoke(addon);
        verifyNoInteractions(control);
    }
}
