/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.lobby;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import de.tubyoub.velocitypteropower.VelocityPteroPower;
import de.tubyoub.velocitypteropower.http.PanelAPIClient;
import de.tubyoub.velocitypteropower.manager.ConfigurationManager;
import de.tubyoub.velocitypteropower.model.PteroServerInfo;
import de.tubyoub.velocitypteropower.model.ServerResourceUsage;
import de.tubyoub.velocitypteropower.util.FilteredComponentLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class LobbyBalancerManagerTest {

    private VelocityPteroPower plugin;
    private ProxyServer proxy;
    private ConfigurationManager config;
    private PanelAPIClient apiClient;
    private RegisteredServer lobby1;
    private RegisteredServer lobby2;
    private RegisteredServer limbo;

    @BeforeEach
    void setUp() {
        plugin = mock(VelocityPteroPower.class);
        proxy = mock(ProxyServer.class);
        config = mock(ConfigurationManager.class);
        apiClient = mock(PanelAPIClient.class);
        FilteredComponentLogger logger = mock(FilteredComponentLogger.class);

        lobby1 = mock(RegisteredServer.class);
        lobby2 = mock(RegisteredServer.class);
        limbo = mock(RegisteredServer.class);

        Map<String, PteroServerInfo> servers = Map.of(
                "lobby-1", new PteroServerInfo("id-l1", -1, 0, false),
                "lobby-2", new PteroServerInfo("id-l2", -1, 0, false),
                "limbo", new PteroServerInfo("id-limbo", -1, 0, false)
        );

        when(plugin.getProxyServer()).thenReturn(proxy);
        when(plugin.getFilteredLogger()).thenReturn(logger);
        when(plugin.getConfigurationManager()).thenReturn(config);
        when(plugin.getApiClient()).thenReturn(apiClient);
        when(plugin.getServerInfoMap()).thenReturn(servers);

        when(config.getBalancerLobbies()).thenReturn(List.of("lobby-1", "lobby-2"));
        when(config.getBalancerLimbos()).thenReturn(List.of("limbo"));
        when(config.getBalancerLobbiesToUse()).thenReturn(0);

        when(proxy.getServer("lobby-1")).thenReturn(Optional.of(lobby1));
        when(proxy.getServer("lobby-2")).thenReturn(Optional.of(lobby2));
        when(proxy.getServer("limbo")).thenReturn(Optional.of(limbo));

        when(apiClient.isServerOnline("lobby-1", "id-l1")).thenReturn(true);
        when(apiClient.isServerOnline("lobby-2", "id-l2")).thenReturn(true);
        when(apiClient.isServerOnline("limbo", "id-limbo")).thenReturn(true);
    }

    @Test
    void roundRobinAlternatesAcrossOnlineLobbies() {
        when(config.getBalancerStrategyName()).thenReturn("ROUND_ROBIN");
        LobbyBalancerManager balancer = new LobbyBalancerManager(plugin);

        assertThat(balancer.pickLobby()).contains(lobby1);
        assertThat(balancer.pickLobby()).contains(lobby2);
        assertThat(balancer.pickLobby()).contains(lobby1);
    }

    @Test
    void leastPlayersSelectsQuieterLobby() {
        when(config.getBalancerStrategyName()).thenReturn("LEAST_PLAYERS");
        when(lobby1.getPlayersConnected()).thenReturn(List.of(mock(Player.class), mock(Player.class)));
        when(lobby2.getPlayersConnected()).thenReturn(List.of());

        LobbyBalancerManager balancer = new LobbyBalancerManager(plugin);
        assertThat(balancer.pickLobby()).contains(lobby2);
    }

    @Test
    void leastCpuSelectsLowerCpuLobbyAndPickHoldingPrefersLobbyOverLimbo() {
        when(config.getBalancerStrategyName()).thenReturn("LEAST_CPU");
        when(apiClient.fetchServerResources("id-l1")).thenReturn(CompletableFuture.completedFuture(
                new ServerResourceUsage("running", false, 0, 0, 75.0, 100.0, 0, 0, 0, 0, 1000, true)));
        when(apiClient.fetchServerResources("id-l2")).thenReturn(CompletableFuture.completedFuture(
                new ServerResourceUsage("running", false, 0, 0, 12.0, 100.0, 0, 0, 0, 0, 1000, true)));

        LobbyBalancerManager balancer = new LobbyBalancerManager(plugin);
        assertThat(balancer.pickLobby()).contains(lobby2);
        assertThat(balancer.pickHoldingServer()).contains(lobby2);

        // If lobbies are offline, holding falls back to limbo
        when(apiClient.isServerOnline("lobby-1", "id-l1")).thenReturn(false);
        when(apiClient.isServerOnline("lobby-2", "id-l2")).thenReturn(false);
        assertThat(balancer.pickHoldingServer()).contains(limbo);
    }
}
