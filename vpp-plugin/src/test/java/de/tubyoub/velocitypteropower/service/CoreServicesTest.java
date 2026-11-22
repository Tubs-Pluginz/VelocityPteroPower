/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.service;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.Scheduler;
import de.tubyoub.velocitypteropower.VelocityPteroPower;
import de.tubyoub.velocitypteropower.http.PanelAPIClient;
import de.tubyoub.velocitypteropower.http.PowerSignal;
import de.tubyoub.velocitypteropower.manager.ConfigurationManager;
import de.tubyoub.velocitypteropower.model.PteroServerInfo;
import de.tubyoub.velocitypteropower.util.FilteredComponentLogger;
import de.tubyoub.velocitypteropower.util.RateLimitTracker;
import de.tubyoub.vpp.api.maintenance.MaintenanceClearListener;
import de.tubyoub.vpp.api.maintenance.MaintenanceDecision;
import de.tubyoub.vpp.api.maintenance.MaintenanceProvider;
import de.tubyoub.vpp.api.maintenance.MaintenanceQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CoreServicesTest {

    @TempDir
    Path tempDir;

    private VelocityPteroPower plugin;
    private FilteredComponentLogger logger;
    private ConfigurationManager config;
    private PanelAPIClient apiClient;
    private RateLimitTracker rateLimitTracker;

    @BeforeEach
    void setUp() {
        plugin = mock(VelocityPteroPower.class);
        logger = mock(FilteredComponentLogger.class);
        config = mock(ConfigurationManager.class);
        apiClient = mock(PanelAPIClient.class);
        rateLimitTracker = mock(RateLimitTracker.class);

        when(plugin.getFilteredLogger()).thenReturn(logger);
        when(plugin.getConfigurationManager()).thenReturn(config);
        when(plugin.getApiClient()).thenReturn(apiClient);
        when(plugin.getRateLimitTracker()).thenReturn(rateLimitTracker);
        when(plugin.getDataDirectory()).thenReturn(tempDir);
        when(rateLimitTracker.canMakeRequest()).thenReturn(true);
    }

    @Test
    void serverStateCacheTracksFreshStatesAndClears() {
        ServerStateCache cache = new ServerStateCache(30);
        assertThat(cache.getFreshState("srv-1")).isNull();

        cache.put("srv-1", ServerStateCache.State.STARTING);
        assertThat(cache.isStartingFresh("srv-1")).isTrue();
        assertThat(cache.isRunningFresh("srv-1")).isFalse();

        cache.put("srv-1", ServerStateCache.State.RUNNING);
        assertThat(cache.isRunningFresh("srv-1")).isTrue();
        assertThat(cache.getFreshState("srv-1")).isEqualTo(ServerStateCache.State.RUNNING);

        cache.clear();
        assertThat(cache.getFreshState("srv-1")).isNull();
    }

    @Test
    void serverGroupServiceStartsLinkedPeersAndProtectsOccupiedGroupsFromIdleStop() {
        Map<String, PteroServerInfo> servers = new LinkedHashMap<>();
        servers.put("skywars", new PteroServerInfo("id-sw", 30, 5, false, null, null, null, null, "minigames", List.of("lobby-db"), null));
        servers.put("bedwars", new PteroServerInfo("id-bw", 30, 5, false, null, null, null, null, "minigames", List.of(), null));
        servers.put("lobby-db", new PteroServerInfo("id-db", 30, 5, false, null, null, null, null, null, List.of(), null));
        when(plugin.getServerInfoMap()).thenReturn(servers);
        when(config.getServerGroupMembers("minigames")).thenReturn(List.of("skywars", "bedwars"));
        when(apiClient.isServerOnline("bedwars", "id-bw")).thenReturn(false);
        when(apiClient.isServerOnline("lobby-db", "id-db")).thenReturn(true);

        ServerGroupService groups = new ServerGroupService(plugin);
        assertThat(groups.peersOf("skywars")).containsExactlyInAnyOrder("bedwars", "lobby-db");

        groups.startLinkedPeers("skywars");
        verify(apiClient).powerServer("id-bw", PowerSignal.START);
        verify(apiClient, never()).powerServer("id-db", PowerSignal.START);

        when(apiClient.isServerEmpty("skywars")).thenReturn(true);
        when(apiClient.isServerEmpty("bedwars")).thenReturn(false);
        when(apiClient.isServerEmpty("lobby-db")).thenReturn(true);
        assertThat(groups.groupHasPlayers("skywars")).isTrue();

        when(apiClient.isServerEmpty("bedwars")).thenReturn(true);
        assertThat(groups.groupHasPlayers("skywars")).isFalse();
    }

    @Test
    void startPasswordServiceIsSingleUseAndRespectsBypass() {
        Map<String, PteroServerInfo> servers = Map.of(
                "survival", new PteroServerInfo("id-1", 30, 5, false, null, null, null, null, null, null, "secret-123")
        );
        when(plugin.getServerInfoMap()).thenReturn(servers);

        StartPasswordService svc = new StartPasswordService(plugin);
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();

        assertThat(svc.requiresPassword("survival")).isTrue();
        assertThat(svc.unlock(alice, "survival", "wrong")).isEqualTo(StartPasswordService.UnlockResult.INVALID);
        assertThat(svc.isAuthorized(alice, "survival")).isFalse();

        assertThat(svc.unlock(alice, "survival", "secret-123")).isEqualTo(StartPasswordService.UnlockResult.SUCCESS);
        assertThat(svc.isAuthorized(alice, "survival")).isTrue();
        // Consumed after single unlock
        assertThat(svc.requiresPassword("survival")).isFalse();
        assertThat(svc.unlock(bob, "survival", "secret-123")).isEqualTo(StartPasswordService.UnlockResult.NOT_REQUIRED);

        Player admin = mock(Player.class);
        when(admin.hasPermission("ptero.password.bypass")).thenReturn(true);
        assertThat(svc.canBypass(admin)).isTrue();
    }

    @Test
    void persistentQueuePersistsAcrossRestartsAndClearsEntries() {
        UUID alice = UUID.randomUUID();
        PersistentQueueService writer = new PersistentQueueService(plugin, 600);
        writer.remember(alice, "survival");

        // Reload fresh instance from same tempDir
        PersistentQueueService reader = new PersistentQueueService(plugin, 600);
        PersistentQueueService.QueueEntry entry = reader.get(alice);
        assertThat(entry).isNotNull();
        assertThat(entry.targetServer).isEqualTo("survival");

        reader.clear(alice);
        PersistentQueueService afterClear = new PersistentQueueService(plugin, 600);
        assertThat(afterClear.get(alice)).isNull();
    }

    @Test
    void maintenanceCoordinatorCombinesBuiltinAndExternalSpiWithPriority() {
        ProxyServer proxy = mock(ProxyServer.class);
        Scheduler scheduler = mock(Scheduler.class);
        when(plugin.getProxyServer()).thenReturn(proxy);
        when(proxy.getScheduler()).thenReturn(scheduler);

        MaintenanceService service = new MaintenanceService(plugin);
        BuiltinMaintenanceProvider builtin = new BuiltinMaintenanceProvider(service);
        service.setClearNotifier(builtin::notifyCleared);

        MaintenanceCoordinator coord = new MaintenanceCoordinator(plugin);
        coord.register(builtin);

        assertThat(coord.isBlocked("survival")).isFalse();
        service.setServer("survival", true);
        assertThat(coord.isBlocked("survival")).isTrue();
        assertThat(coord.evaluate("survival").sourceId()).isEqualTo("vpp-builtin");

        // Register higher-priority external provider
        AtomicReference<Boolean> extBlocked = new AtomicReference<>(true);
        List<MaintenanceClearListener> extListeners = new CopyOnWriteArrayList<>();
        MaintenanceProvider external = new MaintenanceProvider() {
            @Override public String id() { return "custom-maint"; }
            @Override public int priority() { return 250; }
            @Override public boolean isActive() { return true; }
            @Override public boolean blocks(MaintenanceQuery query) { return extBlocked.get(); }
            @Override public String detail(MaintenanceQuery query) { return " (ext)"; }
            @Override public void addClearListener(MaintenanceClearListener l) { extListeners.add(l); }
            @Override public void removeClearListener(MaintenanceClearListener l) { extListeners.remove(l); }
        };
        coord.register(external);

        MaintenanceDecision decision = coord.evaluate("survival");
        assertThat(decision.isBlocked()).isTrue();
        assertThat(decision.sourceId()).isEqualTo("custom-maint");
        assertThat(decision.detail()).isEqualTo(" (ext)");

        // Disabling external providers falls back to built-in
        coord.setExternalProvidersEnabled(false);
        assertThat(coord.evaluate("survival").sourceId()).isEqualTo("vpp-builtin");

        service.setServer("survival", false);
        assertThat(coord.isBlocked("survival")).isFalse();
    }
}
