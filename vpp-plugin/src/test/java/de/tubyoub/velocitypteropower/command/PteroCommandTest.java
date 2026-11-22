/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import de.tubyoub.velocitypteropower.VelocityPteroPower;
import de.tubyoub.velocitypteropower.http.PanelAPIClient;
import de.tubyoub.velocitypteropower.http.PowerSignal;
import de.tubyoub.velocitypteropower.manager.ConfigurationManager;
import de.tubyoub.velocitypteropower.manager.MessagesManager;
import de.tubyoub.velocitypteropower.model.PteroServerInfo;
import de.tubyoub.velocitypteropower.util.FilteredComponentLogger;
import de.tubyoub.velocitypteropower.util.RateLimitTracker;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PteroCommandTest {

    private VelocityPteroPower plugin;
    private PanelAPIClient api;
    private PteroCommand command;
    private Player staff;

    @BeforeEach
    void setUp() {
        plugin = mock(VelocityPteroPower.class);
        api = mock(PanelAPIClient.class);
        RateLimitTracker rateLimit = mock(RateLimitTracker.class);
        MessagesManager messages = mock(MessagesManager.class, inv ->
                inv.getMethod().getReturnType() == Component.class ? Component.text("msg") : null);
        Map<String, PteroServerInfo> servers = new LinkedHashMap<>();
        servers.put("limbo", new PteroServerInfo("srv-limbo", -1, 0, false));
        servers.put("survival", new PteroServerInfo("srv-survival", 30, 0, false));

        when(plugin.getProxyServer()).thenReturn(mock(ProxyServer.class));
        when(plugin.getFilteredLogger()).thenReturn(mock(FilteredComponentLogger.class));
        when(plugin.getApiClient()).thenReturn(api);
        when(plugin.getRateLimitTracker()).thenReturn(rateLimit);
        when(plugin.getConfigurationManager()).thenReturn(mock(ConfigurationManager.class));
        when(plugin.getMessagesManager()).thenReturn(messages);
        when(plugin.getServerInfoMap()).thenReturn(servers);
        when(rateLimit.canMakeRequest()).thenReturn(true);

        command = new PteroCommand(plugin);
        staff = mock(Player.class);
        when(staff.getUniqueId()).thenReturn(UUID.randomUUID());
        when(staff.hasPermission(anyString())).thenReturn(true);
    }

    private void run(CommandSource source, String... args) {
        SimpleCommand.Invocation inv = mock(SimpleCommand.Invocation.class);
        when(inv.source()).thenReturn(source);
        when(inv.arguments()).thenReturn(args);
        command.execute(inv);
    }

    private List<String> suggest(CommandSource source, String... args) {
        SimpleCommand.Invocation inv = mock(SimpleCommand.Invocation.class);
        when(inv.source()).thenReturn(source);
        when(inv.arguments()).thenReturn(args);
        return command.suggest(inv);
    }

    @Test
    void playerForceStopNeedsItsOwnPromptBeforeConfirm() {
        run(staff, "forcestopall", "confirm");
        verify(api, never()).powerServer(anyString(), any());

        run(staff, "forcestopall");
        verify(api, never()).powerServer(anyString(), any());

        run(staff, "forcestopall", "confirm");
        verify(api).powerServer("srv-limbo", PowerSignal.STOP);
        verify(api).powerServer("srv-survival", PowerSignal.STOP);

        // Prompt is single-use
        run(staff, "forcestopall", "confirm");
        verify(api, times(2)).powerServer(anyString(), eq(PowerSignal.STOP));
    }

    @Test
    void forceStopPromptDoesNotUnlockForceStart() {
        run(staff, "forcestopall");
        run(staff, "forcestartall", "confirm");
        verify(api, never()).powerServer(anyString(), eq(PowerSignal.START));

        run(staff, "forcestartall");
        run(staff, "forcestartall", "confirm");
        verify(api).powerServer("srv-limbo", PowerSignal.START);
        verify(api).powerServer("srv-survival", PowerSignal.START);
        verify(api, never()).powerServer(anyString(), eq(PowerSignal.STOP));
    }

    @Test
    void consoleConfirmRunsImmediately() {
        CommandSource console = mock(CommandSource.class);
        when(console.hasPermission(anyString())).thenReturn(true);
        run(console, "forcestopall", "confirm");
        verify(api, times(2)).powerServer(anyString(), eq(PowerSignal.STOP));
    }

    @Test
    void tabCompleteRespectsPermissionsAndServerNames() {
        assertThat(suggest(staff, "st")).contains("start", "stop", "stopidle");
        assertThat(suggest(staff, "start", "sur")).containsExactly("survival");
        assertThat(suggest(staff, "forcestopall", "c")).containsExactly("confirm");
        assertThat(suggest(staff, "maintenance", "g")).containsExactly("global");

        Player guest = mock(Player.class);
        when(guest.hasPermission(anyString())).thenReturn(false);
        assertThat(suggest(guest, "")).doesNotContain("start", "stop", "forcestopall", "reload")
                .contains("queue", "unlock");
        assertThat(suggest(guest, "start", "")).isEmpty();
    }
}
