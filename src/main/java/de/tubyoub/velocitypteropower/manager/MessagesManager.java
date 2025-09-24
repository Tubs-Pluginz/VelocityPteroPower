/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.manager;

import de.tubyoub.velocitypteropower.VelocityPteroPower;
import dev.dejvokep.boostedyaml.YamlDocument;
import dev.dejvokep.boostedyaml.dvs.versioning.BasicVersioning;
import dev.dejvokep.boostedyaml.settings.dumper.DumperSettings;
import dev.dejvokep.boostedyaml.settings.general.GeneralSettings;
import dev.dejvokep.boostedyaml.settings.loader.LoaderSettings;
import dev.dejvokep.boostedyaml.settings.updater.MergeRule;
import dev.dejvokep.boostedyaml.settings.updater.UpdaterSettings;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * MessagesManager provides:
 * - Multi-language messages under data/messages/<lang>.yml with auto-creation from bundled defaults
 * - Fallback to bundled en_US for missing keys
 * - Backward compatibility with legacy data/messages.yml if present
 * - Placeholder formatting helpers
 * - Language detection and runtime reload
 */
public class MessagesManager {
    private static final String DEFAULT_LANG = "en_US";
    private static final String VERSION_KEY = "fileversion";

    private final VelocityPteroPower plugin;
    private final Logger logger;

    // Active messages (selected language) and default messages (en_US)
    private YamlDocument messages;
    private YamlDocument defaultMessages;

    private boolean legacyMode = false;
    private String currentLanguage = DEFAULT_LANG;

    public MessagesManager(VelocityPteroPower plugin) {
        this.plugin = plugin;
        this.logger = plugin.getFilteredLogger();
    }

    /**
     * Load messages using detected language.
     */
    public void loadMessages() {
        String lang = detectLanguage();
        loadMessages(lang);
    }

    /**
     * Load messages for a specific language.
     * Keeps backward compatibility with legacy messages.yml if it exists.
     */
    public void loadMessages(String language) {
        this.currentLanguage = language == null || language.isBlank() ? DEFAULT_LANG : language;
        File dataDir = plugin.getDataDirectory().toFile();
        File legacyFile = new File(dataDir, "messages.yml");
        File messagesDir = new File(dataDir, "messages");

        try {
            if (legacyFile.exists()) {
                // Legacy file detected: do NOT use it; warn and continue with the new system
                legacyMode = false;
                logger.warn("Detected a legacy messages.yml in the plugin data folder.");
                logger.warn("It is deprecated and will be ignored. The plugin will use per-language files in the 'messages/' directory (e.g., messages/{}.yml).", currentLanguage);
                logger.warn("A default messages file will be created if missing. Please edit the new file to customize messages.");
            }

            // New, localized setup
            legacyMode = false;
            if (!messagesDir.exists() && !messagesDir.mkdirs()) {
                logger.warn("Could not create messages directory at: {}", messagesDir.getAbsolutePath());
            }

            // Always ensure default (en_US) exists for fallback
            defaultMessages = YamlDocument.create(
                    new File(messagesDir, DEFAULT_LANG + ".yml"),
                    Objects.requireNonNull(getClass().getResourceAsStream("/messages/" + DEFAULT_LANG + ".yml")),
                    GeneralSettings.DEFAULT,
                    LoaderSettings.builder().setAutoUpdate(true).build(),
                    DumperSettings.DEFAULT,
                    updaterSettings()
            );

            // Try to load requested language, fallback to en_US if bundle missing
            InputStream langDefaults = getClass().getResourceAsStream("/messages/" + currentLanguage + ".yml");
            if (langDefaults == null) {
                logger.warn("No bundled defaults found for language '{}', falling back to {}.", currentLanguage, DEFAULT_LANG);
                currentLanguage = DEFAULT_LANG;
                langDefaults = Objects.requireNonNull(getClass().getResourceAsStream("/messages/" + DEFAULT_LANG + ".yml"));
            }

            File langFile = new File(messagesDir, currentLanguage + ".yml");
            boolean langFileExisted = langFile.exists();
            messages = YamlDocument.create(
                    langFile,
                    langDefaults,
                    GeneralSettings.DEFAULT,
                    LoaderSettings.builder().setAutoUpdate(true).build(),
                    DumperSettings.DEFAULT,
                    updaterSettings()
            );
            if (!langFileExisted) {
                logger.info("Created default messages file at {}. Please review and customize it.", langFile.getAbsolutePath());
            }

            // If current language isn't default, ensure defaultMessages is loaded (may be same instance if en_US)
            if (defaultMessages == null || !DEFAULT_LANG.equals(currentLanguage)) {
                defaultMessages = YamlDocument.create(
                        new File(messagesDir, DEFAULT_LANG + ".yml"),
                        Objects.requireNonNull(getClass().getResourceAsStream("/messages/" + DEFAULT_LANG + ".yml")),
                        GeneralSettings.DEFAULT,
                        LoaderSettings.builder().setAutoUpdate(true).build(),
                        DumperSettings.DEFAULT,
                        updaterSettings()
                );
            }

            logger.info("Loaded messages for language '{}'.", currentLanguage);
        } catch (IOException e) {
            logger.error("Error creating/loading messages: {}", e.getMessage(), e);
        }
    }

    /**
     * Reload the current language files from disk.
     */
    public void reload() {
        if (legacyMode) {
            loadMessages(currentLanguage);
        } else {
            loadMessages(currentLanguage);
        }
    }

    /**
     * Change language at runtime and reload.
     */
    public void setLanguage(String language) {
        loadMessages(language);
    }

    public String getLanguage() {
        return currentLanguage;
    }

    /**
     * Retrieve a raw message by key with fallback to default language.
     */
    public String getMessage(String key) {
        String resolved = null;
        if (messages != null) {
            resolved = messages.getString(key);
        }
        if (resolved == null && defaultMessages != null) {
            resolved = defaultMessages.getString(key);
        }
        return resolved != null ? resolved : "Message not found: " + key;
    }

    /**
     * Retrieve a raw message by enum key.
     */
    public String getMessage(MessageKey key) {
        return getMessage(key.getPath());
    }

    /**
     * Retrieve a message and replace placeholders in the form {placeholder}.
     */
    public String getMessage(String key, Map<String, String> placeholders) {
        return applyPlaceholders(getMessage(key), placeholders);
    }

    /**
     * Retrieve a message by enum key and replace placeholders.
     */
    public String getMessage(MessageKey key, Map<String, String> placeholders) {
        return getMessage(key.getPath(), placeholders);
    }

    /**
     * Retrieve a message and replace placeholders using alternating key/value varargs.
     * Example: getMessage("greeting", "name", "Alex", "count", "3")
     */
    public String getMessage(String key, String... placeholderKeyValuePairs) {
        return applyPlaceholders(getMessage(key), Placeholder.mapOf(placeholderKeyValuePairs));
    }

    /**
     * Return the message prefixed with the configured prefix key if present.
     * Will not duplicate prefix if the message already starts with it.
     */
    public String getPrefixedMessage(String key) {
        String prefix = getMessage(MessageKey.PREFIX);
        String msg = getMessage(key);
        if (prefix == null || prefix.isBlank()) return msg;
        if (msg != null && msg.startsWith(prefix)) return msg;
        return prefix + " " + msg;
    }

    public String getPrefixedMessage(MessageKey key) {
        return getPrefixedMessage(key.getPath());
    }

    public String getPrefixedMessage(String key, Map<String, String> placeholders) {
        return applyPlaceholders(getPrefixedMessage(key), placeholders);
    }

    public String getPrefixedMessage(MessageKey key, Map<String, String> placeholders) {
        return getPrefixedMessage(key.getPath(), placeholders);
    }

    public String getPrefixedMessage(String key, String... placeholderKeyValuePairs) {
        return applyPlaceholders(getPrefixedMessage(key), Placeholder.mapOf(placeholderKeyValuePairs));
    }

    public String getPrefixedMessage(MessageKey key, String... placeholderKeyValuePairs) {
        return getPrefixedMessage(key.getPath(), placeholderKeyValuePairs);
    }

    // -------------------- Helpers --------------------

    private void ensureDefaultMessages(File messagesDir) throws IOException {
        if (!messagesDir.exists() && !messagesDir.mkdirs()) {
            logger.warn("Could not create messages directory at: {}", messagesDir.getAbsolutePath());
        }
        if (defaultMessages == null) {
            defaultMessages = YamlDocument.create(
                    new File(messagesDir, DEFAULT_LANG + ".yml"),
                    Objects.requireNonNull(getClass().getResourceAsStream("/messages/" + DEFAULT_LANG + ".yml")),
                    GeneralSettings.DEFAULT,
                    LoaderSettings.builder().setAutoUpdate(true).build(),
                    DumperSettings.DEFAULT,
                    updaterSettings()
            );
        }
    }

    private UpdaterSettings updaterSettings() {
        return UpdaterSettings.builder()
                .setVersioning(new BasicVersioning(VERSION_KEY))
                .setOptionSorting(UpdaterSettings.OptionSorting.SORT_BY_DEFAULTS)
                .setMergeRule(MergeRule.MAPPINGS, true)
                .setMergeRule(MergeRule.MAPPING_AT_SECTION, true)
                .setMergeRule(MergeRule.SECTION_AT_MAPPING, true)
                .setKeepAll(true)
                .build();
    }

    private String detectLanguage() {
        // Priority: config override -> system property -> env var -> system locale -> default
        try {
            java.lang.reflect.Method m = plugin.getClass().getMethod("getConfigurationManager");
            Object cfg = m.invoke(plugin);
            if (cfg != null) {
                try {
                    java.lang.reflect.Method gm = cfg.getClass().getMethod("getLanguageOverride");
                    Object val = gm.invoke(cfg);
                    if (val instanceof String s && !s.isBlank() && !"auto".equalsIgnoreCase(s)) {
                        return normalizeLocale(s);
                    }
                } catch (NoSuchMethodException ignored) {
                    // No language override available in configuration
                }
            }
        } catch (ReflectiveOperationException ignored) {
            // Configuration manager not accessible
        }

        String sysProp = System.getProperty("velocitypteropower.lang");
        if (sysProp != null && !sysProp.isBlank()) return normalizeLocale(sysProp);

        String env = System.getenv("VPP_LANG");
        if (env != null && !env.isBlank()) return normalizeLocale(env);

        Locale def = Locale.getDefault();
        String fromLocale = def.getLanguage() + (def.getCountry().isEmpty() ? "" : "_" + def.getCountry());
        return normalizeLocale(fromLocale);
    }

    private String normalizeLocale(String lang) {
        String trimmed = lang.trim();
        if (trimmed.contains("-")) trimmed = trimmed.replace('-', '_');
        // Normalize casing: ll_CC (e.g., en_US)
        String[] parts = trimmed.split("_", 2);
        if (parts.length == 2) {
            return parts[0].toLowerCase(Locale.ROOT) + "_" + parts[1].toUpperCase(Locale.ROOT);
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private String applyPlaceholders(String message, Map<String, String> placeholders) {
        if (message == null || placeholders == null || placeholders.isEmpty()) {
            return message;
        }
        String result = message;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            String token = "{" + entry.getKey() + "}";
            result = result.replace(token, entry.getValue() == null ? "null" : entry.getValue());
        }
        return result;
    }

    /**
     * Tiny helper for placeholder maps.
     */
    public static final class Placeholder {
        private Placeholder() {}

        public static Map<String, String> mapOf(String... keyValuePairs) {
            if (keyValuePairs == null || keyValuePairs.length == 0) return java.util.Collections.emptyMap();
            if (keyValuePairs.length % 2 != 0) {
                throw new IllegalArgumentException("Placeholders must be provided as alternating key/value pairs.");
            }
            java.util.Map<String, String> map = new java.util.HashMap<>(keyValuePairs.length / 2);
            for (int i = 0; i < keyValuePairs.length; i += 2) {
                map.put(keyValuePairs[i], keyValuePairs[i + 1]);
            }
            return map;
        }
    }
}