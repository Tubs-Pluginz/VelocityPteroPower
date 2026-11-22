/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.manager;

import de.tubyoub.velocitypteropower.VelocityPteroPower;
import de.tubyoub.velocitypteropower.manager.ConfigurationManager.ForcedHostOfflineBehavior;
import de.tubyoub.velocitypteropower.manager.ConfigurationManager.ServerCheckMethod;
import de.tubyoub.velocitypteropower.model.PteroServerInfo;
import de.tubyoub.velocitypteropower.util.FilteredComponentLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Loads real config.yml files from disk: fileversion migration, profiles, linkedServers, enums. */
class ConfigurationManagerTest {

    @TempDir
    Path dataDir;
    private ConfigurationManager manager;

    @BeforeEach
    void setUp() {
        VelocityPteroPower plugin = mock(VelocityPteroPower.class);
        when(plugin.getFilteredLogger()).thenReturn(mock(FilteredComponentLogger.class));
        when(plugin.getDataDirectory()).thenReturn(dataDir);
        manager = new ConfigurationManager(plugin);
    }

    private void write(String yaml) throws Exception {
        Files.writeString(dataDir.resolve("config.yml"), yaml, StandardCharsets.UTF_8);
    }

    private String saved() throws Exception {
        return Files.readString(dataDir.resolve("config.yml"), StandardCharsets.UTF_8);
    }

    @Test
    void migratesAnOldFileversionWithoutLosingServersOrUserValues() throws Exception {
        write("""
                fileversion: 10
                checkUpdate: false
                playerStartCooldown: 42
                limboServer: "holding"
                pterodactyl:
                  url: "https://panel.example"
                  apiKey: 123456789
                servers:
                  holding:
                    id: "abcd1234"
                    timeout: -1
                  survival:
                    id: "efgh5678"
                    timeout: 120
                    startupJoinDelay: 4
                    profile: "survivalish"
                profiles:
                  survivalish:
                    whitelist: true
                serverGroups:
                  overworld:
                    - survival
                    - holding
                """);

        manager.loadConfig();

        String out = saved();
        assertThat(out).containsPattern("fileversion: '?17'?");
        // New keys introduced after v10 are merged in with defaults
        assertThat(out).contains("softRequeueGraceSeconds:", "persistentQueueEnabled:", "lobbyBalancer:");
        // Legacy limboServer is moved into lobbyBalancer.limbos and removed
        assertThat(out).doesNotContain("limboServer:");
        assertThat(manager.getBalancerLimbos()).contains("holding");
        // User values survive
        assertThat(manager.isCheckUpdate()).isFalse();
        assertThat(manager.getPlayerCommandCooldown()).isEqualTo(42);
        assertThat(manager.getPterodactylUrl()).isEqualTo("https://panel.example/");
        // Unquoted numeric API key is coerced to a string and written back quoted
        assertThat(manager.getPterodactylApiKey()).isEqualTo("123456789");
        assertThat(out).containsPattern("apiKey: ['\"]123456789['\"]");
        // Servers section is not overwritten by the bundled example
        assertThat(manager.getServerInfoMap()).containsOnlyKeys("holding", "survival");
        assertThat(manager.getServerInfoMap().get("survival").getJoinDelay()).isEqualTo(4);
        assertThat(out).doesNotContain("1234abcd");
        // Profiles and serverGroups are user-owned too and must survive the upgrade
        assertThat(manager.getServerInfoMap().get("survival").isWhitelistEnabled()).isTrue();
        assertThat(manager.getServerGroupMembers("overworld")).containsExactly("survival", "holding");
    }

    @Test
    void everyOlderFileversionKeepsTheServersSection() throws Exception {
        for (int v = 5; v <= 17; v++) {
            write("fileversion: " + v + "\npterodactyl:\n  url: \"https://x\"\n  apiKey: \"ptlc_a\"\n"
                    + "servers:\n  survival:\n    id: \"efgh5678\"\n");
            manager.loadConfig();
            assertThat(manager.getServerInfoMap()).as("fileversion %d", v).containsOnlyKeys("survival");
        }
    }

    @Test
    void profilesSupplyDefaultsAndServerFieldsWin() throws Exception {
        write("""
                fileversion: 17
                pterodactyl:
                  url: "https://panel.example/"
                  apiKey: "ptlc_abc"
                profiles:
                  minigame:
                    timeout: 45
                    startupJoinDelay: 7
                    startupTimeoutSeconds: 99
                    serverStatusCheckMethod: "PANEL_API"
                    group: "games"
                    linkedServers:
                      - lobby
                    startPassword: "from-profile"
                servers:
                  lobby:
                    id: "lobby001"
                  skywars:
                    id: "sky00001"
                    profile: "minigame"
                  bedwars:
                    id: "bed00001"
                    profile: "minigame"
                    timeout: 10
                    linkedServers:
                      - skywars
                    startPassword: "own-pass"
                """);

        manager.loadConfig();

        PteroServerInfo sky = manager.getServerInfoMap().get("skywars");
        assertThat(sky.getProfileName()).isEqualTo("minigame");
        assertThat(sky.getTimeout()).isEqualTo(45);
        assertThat(sky.getJoinDelay()).isEqualTo(7);
        assertThat(sky.getStartupTimeoutSeconds()).isEqualTo(99);
        assertThat(sky.getCheckMethodOverride()).isEqualTo(ServerCheckMethod.PANEL_API);
        assertThat(sky.getGroup()).isEqualTo("games");
        assertThat(sky.getLinkedServers()).containsExactly("lobby");
        assertThat(sky.getStartPassword()).isEqualTo("from-profile");

        PteroServerInfo bed = manager.getServerInfoMap().get("bedwars");
        assertThat(bed.getTimeout()).isEqualTo(10);
        assertThat(bed.getJoinDelay()).isEqualTo(7);
        assertThat(bed.getLinkedServers()).containsExactly("skywars");
        assertThat(bed.getStartPassword()).isEqualTo("own-pass");

        assertThat(manager.resolveStartupTimeout(sky)).isEqualTo(99);
        assertThat(manager.resolveCheckMethod(sky)).isEqualTo(ServerCheckMethod.PANEL_API);
        assertThat(manager.resolveCheckMethod(manager.getServerInfoMap().get("lobby")))
                .isEqualTo(ServerCheckMethod.HYBRID);
    }

    @Test
    void parsesEnumsAndFallsBackOnInvalidValues() throws Exception {
        write("""
                fileversion: 17
                serverStatusCheckMethod: "velocity_ping"
                startupTimeoutPolicy: "keep_starting"
                languageOverride: "de_DE"
                queueWhenMaxOnline: false
                maxOnlineReservations: 1
                countLobbiesInMaxOnline: true
                lobbyBalancer:
                  strategy: "LEAST_PLAYERS"
                  forcedHostOfflineBehavior: "limbo_only"
                pterodactyl:
                  url: "https://panel.example/"
                  apiKey: "ptlc_abc"
                servers:
                  lobby:
                    id: "lobby001"
                """);
        manager.loadConfig();
        assertThat(manager.getServerCheckMethod()).isEqualTo(ServerCheckMethod.VELOCITY_PING);
        assertThat(manager.getStartupTimeoutPolicy()).isEqualTo(ConfigurationManager.StartupTimeoutPolicy.KEEP_STARTING);
        assertThat(manager.getLanguageOverride()).isEqualTo("de_DE");
        assertThat(manager.isQueueWhenMaxOnline()).isFalse();
        assertThat(manager.getMaxOnlineReservations()).isEqualTo(1);
        assertThat(manager.isCountLobbiesInMaxOnline()).isTrue();
        assertThat(manager.getBalancerStrategyName()).isEqualTo("LEAST_PLAYERS");
        assertThat(manager.getForcedHostOfflineBehavior()).isEqualTo(ForcedHostOfflineBehavior.LIMBO_ONLY);

        write(saved()
                .replace("velocity_ping", "carrier_pigeon")
                .replace("limbo_only", "teleport"));
        manager.loadConfig();
        assertThat(manager.getServerCheckMethod()).isEqualTo(ServerCheckMethod.HYBRID);
        assertThat(manager.getForcedHostOfflineBehavior()).isEqualTo(ForcedHostOfflineBehavior.LOBBY_OR_LIMBO);
    }

    @Test
    void createsTheBundledDefaultOnFirstStart() throws Exception {
        manager.loadConfig();
        assertThat(Files.exists(dataDir.resolve("config.yml"))).isTrue();
        assertThat(saved()).containsPattern("fileversion: '?17'?");
        assertThat(manager.isCheckUpdate()).isTrue();
        assertThat(manager.isResourcePrefetchEnabled()).isTrue();
        // The bundled placeholder server id is ignored
        assertThat(manager.getServerInfoMap()).doesNotContainKey("example");
    }
}
