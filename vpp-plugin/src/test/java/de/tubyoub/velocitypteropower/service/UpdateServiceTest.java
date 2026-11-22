/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.service;

import com.sun.net.httpserver.HttpServer;
import de.tubyoub.velocitypteropower.manager.ConfigurationManager;
import de.tubyoub.velocitypteropower.util.VersionChecker;
import de.tubyoub.velocitypteropower.util.VersionChecker.UpdateUrgency;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** checkUpdate on/off and version comparison against a local Modrinth-shaped API. */
class UpdateServiceTest {

    private HttpServer modrinth;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> body = new AtomicReference<>("[]");
    private ConfigurationManager config;

    @BeforeEach
    void setUp() throws Exception {
        modrinth = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        modrinth.createContext("/v2/project/", ex -> {
            requests.add(ex.getRequestURI().getPath());
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        });
        modrinth.start();
        System.setProperty("velocitypteropower.modrinthApi", "http://127.0.0.1:" + modrinth.getAddress().getPort());
        config = mock(ConfigurationManager.class);
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("velocitypteropower.modrinthApi");
        modrinth.stop(0);
    }

    private static String version(String number, String type, String changelog) {
        return "[{\"version_number\":\"" + number + "\",\"version_type\":\"" + type
                + "\",\"changelog\":\"" + changelog + "\"}]";
    }

    @Test
    void checkUpdateFalseNeverContactsModrinth() {
        when(config.isCheckUpdate()).thenReturn(false);
        UpdateService svc = new UpdateService(mock(ComponentLogger.class), config, "0.9.6-beta", "vpp");
        svc.performUpdateCheck();
        assertThat(requests).isEmpty();
        assertThat(svc.getLastCheckedVersionInfo()).isNull();
    }

    @Test
    void checkUpdateTrueReportsANewerRelease() {
        when(config.isCheckUpdate()).thenReturn(true);
        body.set(version("0.9.7", "release", "Bug fixes"));
        UpdateService svc = new UpdateService(mock(ComponentLogger.class), config, "0.9.6-beta", "vpp");
        svc.performUpdateCheck();

        assertThat(requests).containsExactly("/v2/project/vpp/version");
        VersionChecker.VersionInfo info = svc.getLastCheckedVersionInfo();
        assertThat(info.isNewVersionAvailable).isTrue();
        assertThat(info.latestVersion).isEqualTo("0.9.7");
        assertThat(info.urgency).isEqualTo(UpdateUrgency.NORMAL);
    }

    @Test
    void securityChangelogIsCritical() {
        body.set(version("1.0.0", "beta", "Critical security fix"));
        assertThat(VersionChecker.isNewVersionAvailable("0.9.6", "vpp").urgency).isEqualTo(UpdateUrgency.CRITICAL);
        body.set(version("1.0.0", "release", "Major rework"));
        assertThat(VersionChecker.isNewVersionAvailable("0.9.6", "vpp").urgency).isEqualTo(UpdateUrgency.HIGH);
    }

    @Test
    void sameOrOlderVersionIsNotAnUpdate() {
        body.set(version("0.9.6", "release", "x"));
        assertThat(VersionChecker.isNewVersionAvailable("0.9.6", "vpp").isNewVersionAvailable).isFalse();
        body.set(version("0.9.5", "release", "x"));
        assertThat(VersionChecker.isNewVersionAvailable("0.9.6", "vpp").isNewVersionAvailable).isFalse();
        body.set(version("0.10.0", "release", "x"));
        assertThat(VersionChecker.isNewVersionAvailable("0.9.6", "vpp").isNewVersionAvailable).isTrue();
    }
}
