/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.service;

import de.tubyoub.velocitypteropower.VelocityPteroPower;
import de.tubyoub.velocitypteropower.http.PanelAPIClient;
import de.tubyoub.velocitypteropower.http.PowerSignal;
import de.tubyoub.velocitypteropower.manager.ConfigurationManager;
import de.tubyoub.velocitypteropower.model.PteroServerInfo;
import de.tubyoub.velocitypteropower.util.FilteredComponentLogger;
import de.tubyoub.velocitypteropower.util.RateLimitTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** linkedServers (one-way links), group membership, and serverGroups: lists. */
class ServerGroupServiceTest {

    private VelocityPteroPower plugin;
    private ConfigurationManager config;
    private PanelAPIClient api;
    private RateLimitTracker rateLimit;
    private MaintenanceCoordinator maintenance;
    private final Map<String, PteroServerInfo> servers = new HashMap<>();
    private ServerGroupService service;

    private static PteroServerInfo info(String id, String group, List<String> linked) {
        return new PteroServerInfo(id, 30, 0, false, null, null, null, null, group, linked, null);
    }

    @BeforeEach
    void setUp() {
        plugin = mock(VelocityPteroPower.class);
        config = mock(ConfigurationManager.class);
        api = mock(PanelAPIClient.class);
        rateLimit = mock(RateLimitTracker.class);
        maintenance = mock(MaintenanceCoordinator.class);
        when(plugin.getFilteredLogger()).thenReturn(mock(FilteredComponentLogger.class));
        when(plugin.getConfigurationManager()).thenReturn(config);
        when(plugin.getApiClient()).thenReturn(api);
        when(plugin.getRateLimitTracker()).thenReturn(rateLimit);
        when(plugin.getMaintenanceCoordinator()).thenReturn(maintenance);
        when(plugin.getServerInfoMap()).thenReturn(servers);
        when(rateLimit.canMakeRequest()).thenReturn(true);
        when(api.isServerEmpty(anyString())).thenReturn(true);

        servers.put("survival", info("srv-survival", null, List.of("nether", "ghost")));
        servers.put("nether", info("srv-nether", null, List.of()));
        servers.put("skywars", info("srv-sky", "minigames", List.of()));
        servers.put("bedwars", info("srv-bed", "minigames", List.of()));
        servers.put("duels", info("srv-duels", null, List.of()));
        when(config.getServerGroupMembers("minigames")).thenReturn(List.of("skywars", "bedwars", "duels"));
        service = new ServerGroupService(plugin);
    }

    @Test
    void linkedServersAreOneWayAndIgnoreUnknownNames() {
        assertThat(service.peersOf("survival")).containsExactly("nether");
        assertThat(service.peersOf("nether")).isEmpty();
    }

    @Test
    void groupMembersIncludeExplicitServerGroupsEntries() {
        assertThat(service.peersOf("skywars")).containsExactlyInAnyOrder("bedwars", "duels");
    }

    @Test
    void startLinkedPeersSkipsOnlineAndMaintenancePeers() {
        when(api.isServerOnline("bedwars", "srv-bed")).thenReturn(true);
        when(maintenance.isBlocked("duels")).thenReturn(false);
        service.startLinkedPeers("skywars");
        verify(api).powerServer("srv-duels", PowerSignal.START);
        verify(api, never()).powerServer(eq("srv-bed"), any());

        reset(api);
        when(maintenance.isBlocked("nether")).thenReturn(true);
        service.startLinkedPeers("survival");
        verify(api, never()).powerServer(anyString(), any());
    }

    @Test
    void startLinkedPeersRespectsTheRateLimit() {
        when(rateLimit.canMakeRequest()).thenReturn(false);
        service.startLinkedPeers("survival");
        verify(api, never()).powerServer(anyString(), any());
    }

    @Test
    void groupHasPlayersChecksSelfAndPeers() {
        assertThat(service.groupHasPlayers("survival")).isFalse();
        when(api.isServerEmpty("nether")).thenReturn(false);
        assertThat(service.groupHasPlayers("survival")).isTrue();
        // One-way: nether does not see survival's players through the link
        when(api.isServerEmpty("nether")).thenReturn(true);
        when(api.isServerEmpty("survival")).thenReturn(false);
        assertThat(service.groupHasPlayers("nether")).isFalse();
    }
}
