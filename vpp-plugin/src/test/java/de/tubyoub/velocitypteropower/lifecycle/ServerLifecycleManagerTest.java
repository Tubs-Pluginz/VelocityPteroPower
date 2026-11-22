/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.lifecycle;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import com.velocitypowered.api.scheduler.Scheduler;
import de.tubyoub.velocitypteropower.VelocityPteroPower;
import de.tubyoub.velocitypteropower.handler.PlayerConnectionHandler;
import de.tubyoub.velocitypteropower.http.PanelAPIClient;
import de.tubyoub.velocitypteropower.http.PowerSignal;
import de.tubyoub.velocitypteropower.manager.ConfigurationManager;
import de.tubyoub.velocitypteropower.manager.ConfigurationManager.ServerCheckMethod;
import de.tubyoub.velocitypteropower.manager.MessageKey;
import de.tubyoub.velocitypteropower.manager.MessagesManager;
import de.tubyoub.velocitypteropower.model.PteroServerInfo;
import de.tubyoub.velocitypteropower.service.ServerGroupService;
import de.tubyoub.velocitypteropower.util.FilteredComponentLogger;
import de.tubyoub.velocitypteropower.util.RateLimitTracker;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ServerLifecycleManagerTest {

    private VelocityPteroPower plugin;
    private ProxyServer proxy;
    private Scheduler scheduler;
    private Scheduler.TaskBuilder taskBuilder;
    private ConfigurationManager config;
    private MessagesManager messages;
    private PanelAPIClient apiClient;
    private RateLimitTracker rateLimit;
    private PlayerConnectionHandler pch;
    private ServerGroupService groupService;
    private Map<String, Long> deadlines;

    @BeforeEach
    void setUp() {
        plugin = mock(VelocityPteroPower.class);
        proxy = mock(ProxyServer.class);
        scheduler = mock(Scheduler.class);
        taskBuilder = mock(Scheduler.TaskBuilder.class);
        config = mock(ConfigurationManager.class);
        messages = mock(MessagesManager.class);
        apiClient = mock(PanelAPIClient.class);
        rateLimit = mock(RateLimitTracker.class);
        pch = mock(PlayerConnectionHandler.class);
        groupService = mock(ServerGroupService.class);
        deadlines = new ConcurrentHashMap<>();

        when(plugin.getFilteredLogger()).thenReturn(mock(FilteredComponentLogger.class));
        when(plugin.getConfigurationManager()).thenReturn(config);
        when(plugin.getMessagesManager()).thenReturn(messages);
        when(plugin.getApiClient()).thenReturn(apiClient);
        when(plugin.getRateLimitTracker()).thenReturn(rateLimit);
        when(plugin.getServerInfoMap()).thenReturn(Map.of(
                "survival", new PteroServerInfo("srv-1", 30, 5, false),
                "limbo", new PteroServerInfo("srv-limbo", 30, 0, false)
        ));
        when(plugin.getShutdownDeadlines()).thenReturn(deadlines);
        when(plugin.getPlayerConnectionHandler()).thenReturn(pch);
        when(plugin.getServerGroupService()).thenReturn(groupService);

        when(proxy.getScheduler()).thenReturn(scheduler);
        when(scheduler.buildTask(eq(plugin), any(Runnable.class))).thenReturn(taskBuilder);
        when(taskBuilder.delay(anyLong(), any(TimeUnit.class))).thenReturn(taskBuilder);
        when(taskBuilder.schedule()).thenReturn(mock(ScheduledTask.class));

        when(messages.mm(any(MessageKey.class), anyMap())).thenReturn(Component.text("msg"));
        when(config.getAlwaysOnlineList()).thenReturn(List.of("limbo"));
        when(config.getShutdownRetryDelay()).thenReturn(60);
        when(config.getShutdownRetries()).thenReturn(3);
        when(rateLimit.canMakeRequest()).thenReturn(true);
        when(pch.isSafeToIdleStop(anyString())).thenReturn(true);
        when(groupService.groupHasPlayers(anyString())).thenReturn(false);
    }

    @Test
    void skipsAlwaysOnlineServersAndRespectsGroupPlayers() {
        ServerLifecycleManager mgr = new ServerLifecycleManager(proxy, plugin);
        PteroServerInfo limboInfo = new PteroServerInfo("srv-limbo", 30, 0, false);

        assertThat(mgr.scheduleServerShutdown("limbo", "srv-limbo", 30)).isNull();

        PteroServerInfo survivalInfo = new PteroServerInfo("srv-1", 30, 5, false);
        when(apiClient.isServerOnline("survival", "srv-1")).thenReturn(true);
        when(apiClient.isServerEmpty("survival")).thenReturn(true);
        when(groupService.groupHasPlayers("survival")).thenReturn(true);

        mgr.checkAndScheduleShutdownIfNeeded("survival", survivalInfo);
        assertThat(mgr.hasScheduledShutdown("survival")).isFalse();
    }

    @Test
    void schedulesStopsAndCancelsCleanlyOnPlayerRejoin() {
        ServerLifecycleManager mgr = new ServerLifecycleManager(proxy, plugin);
        PteroServerInfo survivalInfo = new PteroServerInfo("srv-1", 30, 5, false);
        when(apiClient.isServerOnline("survival", "srv-1")).thenReturn(true);
        when(apiClient.isServerEmpty("survival")).thenReturn(true);

        ScheduledTask scheduled = mock(ScheduledTask.class);
        when(taskBuilder.schedule()).thenReturn(scheduled);

        mgr.checkAndScheduleShutdownIfNeeded("survival", survivalInfo);
        assertThat(mgr.hasScheduledShutdown("survival")).isTrue();
        assertThat(deadlines).containsKey("survival");

        // Cancel on player rejoin
        mgr.cancelScheduledShutdown("survival", "player joined");
        verify(scheduled).cancel();
        assertThat(mgr.hasScheduledShutdown("survival")).isFalse();
        assertThat(deadlines).doesNotContainKey("survival");

        // Re-schedule and fire the runnable to verify STOP signal is dispatched
        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        mgr.checkAndScheduleShutdownIfNeeded("survival", survivalInfo);
        verify(scheduler, atLeastOnce()).buildTask(eq(plugin), runnableCaptor.capture());

        runnableCaptor.getValue().run();
        verify(apiClient).powerServer("srv-1", PowerSignal.STOP);
    }

    @Test
    void shutdownConfirmationRetriesStopWhileTheServerStaysOnline() {
        when(apiClient.isServerOnline("survival", "srv-1")).thenReturn(true);
        when(apiClient.isServerEmpty("survival")).thenReturn(true);
        when(config.getShutdownRetries()).thenReturn(2);
        when(config.getShutdownRetryDelay()).thenReturn(15);

        List<Runnable> tasks = new ArrayList<>();
        when(scheduler.buildTask(eq(plugin), any(Runnable.class))).thenAnswer(inv -> {
            tasks.add(inv.getArgument(1));
            return taskBuilder;
        });

        ServerLifecycleManager mgr = new ServerLifecycleManager(proxy, plugin);
        PteroServerInfo survivalInfo = new PteroServerInfo("srv-1", 30, 5, false);
        mgr.checkAndScheduleShutdownIfNeeded("survival", survivalInfo);
        assertThat(tasks).hasSize(1);

        tasks.get(0).run();
        verify(apiClient, times(1)).powerServer("srv-1", PowerSignal.STOP);
        assertThat(tasks).hasSize(2);

        tasks.get(1).run();
        verify(apiClient, times(2)).powerServer("srv-1", PowerSignal.STOP);
    }

    @Test
    void panelApiCountReturnsUnknownWhenRateLimitedAndHybridStillCounts() {
        when(config.getServerCheckMethod()).thenReturn(ServerCheckMethod.PANEL_API);
        when(rateLimit.canMakeRequest()).thenReturn(false);
        ServerLifecycleManager mgr = new ServerLifecycleManager(proxy, plugin);

        assertThat(mgr.countOnlineServersExcluding(Set.of())).isEqualTo(-1);

        when(rateLimit.canMakeRequest()).thenReturn(true);
        when(config.getServerCheckMethod()).thenReturn(ServerCheckMethod.HYBRID);
        when(apiClient.isServerOnline(anyString(), anyString())).thenReturn(true);
        assertThat(mgr.countOnlineServersExcluding(Set.of("limbo"))).isEqualTo(1);
    }

    @Test
    void scheduledShutdownIsSkippedWhenAGroupPeerGainedPlayers() {
        ServerLifecycleManager mgr = new ServerLifecycleManager(proxy, plugin);
        PteroServerInfo survivalInfo = new PteroServerInfo("srv-1", 30, 5, false);
        when(apiClient.isServerOnline("survival", "srv-1")).thenReturn(true);
        when(apiClient.isServerEmpty("survival")).thenReturn(true);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);

        mgr.checkAndScheduleShutdownIfNeeded("survival", survivalInfo);
        verify(scheduler).buildTask(eq(plugin), task.capture());

        // Scheduled while the group was empty; a peer has players by the time it fires
        when(groupService.groupHasPlayers("survival")).thenReturn(true);
        task.getValue().run();

        verify(apiClient, never()).powerServer("srv-1", PowerSignal.STOP);
    }
}
