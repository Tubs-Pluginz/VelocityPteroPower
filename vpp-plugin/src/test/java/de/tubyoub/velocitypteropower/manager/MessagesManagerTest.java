/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.manager;

import de.tubyoub.velocitypteropower.VelocityPteroPower;
import de.tubyoub.velocitypteropower.util.FilteredComponentLogger;
import dev.dejvokep.boostedyaml.YamlDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** languageOverride, auto-detection, fallback, and en_US/de_DE key parity. */
class MessagesManagerTest {

    @TempDir
    Path dataDir;
    private ConfigurationManager config;
    private MessagesManager messages;

    @BeforeEach
    void setUp() {
        VelocityPteroPower plugin = mock(VelocityPteroPower.class);
        config = mock(ConfigurationManager.class);
        when(plugin.getFilteredLogger()).thenReturn(mock(FilteredComponentLogger.class));
        when(plugin.getDataDirectory()).thenReturn(dataDir);
        when(plugin.getConfigurationManager()).thenReturn(config);
        messages = new MessagesManager(plugin);
    }

    @AfterEach
    void clearProperty() {
        System.clearProperty("velocitypteropower.lang");
    }

    @Test
    void languageOverrideLoadsGerman() {
        when(config.getLanguageOverride()).thenReturn("de_DE");
        messages.loadMessages();
        assertThat(messages.getLanguage()).isEqualTo("de_DE");
        assertThat(dataDir.resolve("messages/de_DE.yml")).exists();
        assertThat(messages.raw(MessageKey.COMMAND_NO_PERMISSION))
                .isNotEqualTo(englishRaw(MessageKey.COMMAND_NO_PERMISSION));
    }

    @Test
    void autoUsesTheSystemPropertyAndNormalisesIt() {
        when(config.getLanguageOverride()).thenReturn("auto");
        System.setProperty("velocitypteropower.lang", "de-de");
        messages.loadMessages();
        assertThat(messages.getLanguage()).isEqualTo("de_DE");
    }

    @Test
    void unknownLanguageFallsBackToEnglish() {
        when(config.getLanguageOverride()).thenReturn("xx_YY");
        messages.loadMessages();
        assertThat(messages.getLanguage()).isEqualTo("en_US");
        assertThat(messages.raw(MessageKey.COMMAND_NO_PERMISSION))
                .isEqualTo(englishRaw(MessageKey.COMMAND_NO_PERMISSION));
    }

    @Test
    void everyMessageKeyExistsInEnglishAndGerman() throws Exception {
        for (String lang : List.of("en_US", "de_DE")) {
            YamlDocument doc;
            try (InputStream in = getClass().getResourceAsStream("/messages/" + lang + ".yml")) {
                doc = YamlDocument.create(in);
            }
            List<String> missing = new ArrayList<>();
            for (MessageKey key : MessageKey.values()) {
                if (doc.getString(key.getPath()) == null) missing.add(key.getPath());
            }
            assertThat(missing).as("keys missing from %s.yml", lang).isEmpty();
        }
    }

    private String englishRaw(MessageKey key) {
        try (InputStream in = getClass().getResourceAsStream("/messages/en_US.yml")) {
            return YamlDocument.create(in).getString(key.getPath());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
