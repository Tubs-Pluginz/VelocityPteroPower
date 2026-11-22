/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Addon configs live in addons/configs/&lt;id&gt;.yml and must pick up hand edits on reload. */
class AddonConfigImplTest {

    @TempDir
    Path dataDir;

    @Test
    void reloadPicksUpExternalEditsAndSaveDoesNotClobberThem() throws Exception {
        AddonConfigImpl cfg = new AddonConfigImpl("vpp-scheduler", dataDir);
        cfg.ensureDefaults(Map.of("checkIntervalSeconds", 30));
        cfg.save();
        Path file = dataDir.resolve("addons/configs/vpp-scheduler.yml");
        assertThat(file).exists();

        Files.writeString(file, "checkIntervalSeconds: 5\n");
        cfg.reload();
        assertThat(cfg.getInt("checkIntervalSeconds", 30)).isEqualTo(5);

        // What an addon's seedDefaults() does on enable: must keep the edited value
        cfg.ensureDefaults(Map.of("checkIntervalSeconds", 30, "timezone", "UTC"));
        cfg.save();
        assertThat(Files.readString(file)).contains("checkIntervalSeconds: 5").contains("timezone");
    }

    @Test
    void legacyConfigNextToJarsIsMigrated() throws Exception {
        Files.createDirectories(dataDir.resolve("addons"));
        Files.writeString(dataDir.resolve("addons/vpp-webhooks.yml"), "format: discord\n");
        AddonConfigImpl cfg = new AddonConfigImpl("vpp-webhooks", dataDir);
        assertThat(cfg.getString("format", "auto")).isEqualTo("discord");
        assertThat(dataDir.resolve("addons/configs/vpp-webhooks.yml")).exists();
        assertThat(dataDir.resolve("addons/vpp-webhooks.yml")).doesNotExist();
    }
}
