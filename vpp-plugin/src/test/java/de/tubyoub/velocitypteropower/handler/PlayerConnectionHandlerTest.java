/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.handler;

import com.velocitypowered.api.proxy.ProxyServer;
import de.tubyoub.velocitypteropower.VelocityPteroPower;
import de.tubyoub.velocitypteropower.http.PanelAPIClient;
import de.tubyoub.velocitypteropower.manager.ConfigurationManager;
import de.tubyoub.velocitypteropower.manager.ConfigurationManager.StartupTimeoutPolicy;
import de.tubyoub.velocitypteropower.manager.MessagesManager;
import de.tubyoub.velocitypteropower.model.PteroServerInfo;
import de.tubyoub.velocitypteropower.util.FilteredComponentLogger;
import de.tubyoub.velocitypteropower.util.RateLimitTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlayerConnectionHandlerTest {

    private VelocityPteroPower plugin;
    private ConfigurationManager config;
    private Set<String> starting;
    private Map<String, Set<UUID>> waiting;
    private Map<String, Long> since;
    private PlayerConnectionHandler handler;
    private Method handleTimeout;

    @BeforeEach
    void setUp() throws Exception {
        plugin = mock(VelocityPteroPower.class);
        ProxyServer proxy = mock(ProxyServer.class);
        config = mock(ConfigurationManager.class);
        starting = ConcurrentHashMap.newKeySet();
        waiting = new ConcurrentHashMap<>();
        since = new ConcurrentHashMap<>();
        Map<String, UUID> initiators = new ConcurrentHashMap<>();

        when(plugin.getFilteredLogger()).thenReturn(mock(FilteredComponentLogger.class));
        when(plugin.getConfigurationManager()).thenReturn(config);
        when(plugin.getMessagesManager()).thenReturn(mock(MessagesManager.class));
        when(plugin.getApiClient()).thenReturn(mock(PanelAPIClient.class));
        when(plugin.getRateLimitTracker()).thenReturn(mock(RateLimitTracker.class));
        when(plugin.getServerInfoMap()).thenReturn(Map.of(
                "survival", new PteroServerInfo("srv-1", 30, 5, false)));
        when(plugin.getStartingServers()).thenReturn(starting);
        when(plugin.getWaitingPlayers()).thenReturn(waiting);
        when(plugin.getPlayerCooldowns()).thenReturn(new ConcurrentHashMap<>());
        when(plugin.getStartInitiators()).thenReturn(initiators);
        when(plugin.getStartingServersSince()).thenReturn(since);
        when(config.resolveStartupTimeout(any())).thenReturn(90);
        when(proxy.getPlayer(any(UUID.class))).thenReturn(Optional.empty());

        handler = new PlayerConnectionHandler(proxy, plugin);
        handleTimeout = PlayerConnectionHandler.class.getDeclaredMethod(
                "handleStartupTimeout", String.class, Set.class);
        handleTimeout.setAccessible(true);
    }

    @Test
    void cancelPolicyClearsTheColdStart() throws Exception {
        when(config.getStartupTimeoutPolicy()).thenReturn(StartupTimeoutPolicy.CANCEL);
        starting.add("survival");
        since.put("survival", 1L);
        Set<UUID> queued = ConcurrentHashMap.newKeySet();
        queued.add(UUID.randomUUID());
        waiting.put("survival", queued);

        handleTimeout.invoke(handler, "survival", queued);

        assertThat(starting).doesNotContain("survival");
        assertThat(since).doesNotContainKey("survival");
        assertThat(queued).isEmpty();
    }

    @Test
    void keepStartingPolicyLeavesTheColdStartInProgress() throws Exception {
        when(config.getStartupTimeoutPolicy()).thenReturn(StartupTimeoutPolicy.KEEP_STARTING);
        starting.add("survival");
        since.put("survival", 1L);
        Set<UUID> queued = ConcurrentHashMap.newKeySet();
        queued.add(UUID.randomUUID());
        waiting.put("survival", queued);

        handleTimeout.invoke(handler, "survival", queued);

        assertThat(starting).contains("survival");
        assertThat(waiting).doesNotContainKey("survival");
        assertThat(queued).isEmpty();
    }
}
