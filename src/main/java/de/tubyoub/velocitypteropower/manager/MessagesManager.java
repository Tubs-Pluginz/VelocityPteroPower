package de.tubyoub.velocitypteropower.manager;

import de.tubyoub.velocitypteropower.VelocityPteroPower;
import dev.dejvokep.boostedyaml.YamlDocument;
import dev.dejvokep.boostedyaml.dvs.versioning.BasicVersioning;
import dev.dejvokep.boostedyaml.settings.dumper.DumperSettings;
import dev.dejvokep.boostedyaml.settings.general.GeneralSettings;
import dev.dejvokep.boostedyaml.settings.loader.LoaderSettings;
import dev.dejvokep.boostedyaml.settings.updater.MergeRule;
import dev.dejvokep.boostedyaml.settings.updater.UpdaterSettings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * MiniMessage-based MessagesManager.
 * - Loads localized YAML files under data/messages/<lang>.yml
 * - No legacy placeholder system. Use MiniMessage with <placeholders>
 * - Provides mm() and prefixed() helpers to render Components
 */
public class MessagesManager {
  private static final String DEFAULT_LANG = "en_US";
  private static final String VERSION_KEY = "fileversion";

  private final VelocityPteroPower plugin;
  private final ComponentLogger logger;

  private YamlDocument messages;
  private YamlDocument defaultMessages;

  private String currentLanguage = DEFAULT_LANG;
  private final MiniMessage mini = MiniMessage.miniMessage();

  public MessagesManager(VelocityPteroPower plugin) {
    this.plugin = plugin;
    this.logger = plugin.getFilteredLogger();
  }

  public void loadMessages() {
    String lang = detectLanguage();
    loadMessages(lang);
  }

  public void loadMessages(String language) {
    this.currentLanguage = language == null || language.isBlank() ? DEFAULT_LANG : language;
    File dataDir = plugin.getDataDirectory().toFile();
    File messagesDir = new File(dataDir, "messages");

    try {
      if (!messagesDir.exists() && !messagesDir.mkdirs()) {
        logger.warn("Could not create messages directory at: {}", messagesDir.getAbsolutePath());
      }
      File legacyMessages = new File(dataDir, "messages.yml");
      if (legacyMessages.exists()) {
        logger.error("Detected legacy file '{}' which is no longer used. Please remove it and use the new language files in the 'messages' folder (e.g., en_US.yml, de_DE.yml).",
            legacyMessages.getAbsolutePath());
      }

      defaultMessages =
          YamlDocument.create(
              new File(messagesDir, DEFAULT_LANG + ".yml"),
              Objects.requireNonNull(getClass().getResourceAsStream("/messages/" + DEFAULT_LANG + ".yml")),
              GeneralSettings.DEFAULT,
              LoaderSettings.builder().setAutoUpdate(true).build(),
              DumperSettings.DEFAULT,
              updaterSettings());

      InputStream langDefaults = getClass().getResourceAsStream("/messages/" + currentLanguage + ".yml");
      if (langDefaults == null) {
        logger.warn("No bundled defaults found for language '{}', falling back to {}.", currentLanguage, DEFAULT_LANG);
        currentLanguage = DEFAULT_LANG;
        langDefaults =
            Objects.requireNonNull(getClass().getResourceAsStream("/messages/" + DEFAULT_LANG + ".yml"));
      }

      File langFile = new File(messagesDir, currentLanguage + ".yml");
      boolean existed = langFile.exists();
      messages =
          YamlDocument.create(
              langFile,
              langDefaults,
              GeneralSettings.DEFAULT,
              LoaderSettings.builder().setAutoUpdate(true).build(),
              DumperSettings.DEFAULT,
              updaterSettings());
      if (!existed) {
        logger.info(
            "Created default messages file at {}. Please review and customize it.",
            langFile.getAbsolutePath());
      }

      logger.info("Loaded messages for language '{}'.", currentLanguage);
    } catch (IOException e) {
      logger.error("Error creating/loading messages: {}", e.getMessage(), e);
    }
  }

  public void reload() {
    loadMessages(currentLanguage);
  }

  public void setLanguage(String language) {
    loadMessages(language);
  }

  public String getLanguage() {
    return currentLanguage;
  }

  public String raw(String key) {
    String resolved = null;
    if (messages != null) {
      resolved = messages.getString(key);
    }
    if (resolved == null && defaultMessages != null) {
      resolved = defaultMessages.getString(key);
    }
    return resolved != null ? resolved : "<gray>Message not found: " + key + "</gray>";
  }

  public String raw(MessageKey key) {
    return raw(key.getPath());
  }

  public Component mm(String key, TagResolver... resolvers) {
    return mini.deserialize(raw(key), TagResolver.resolver(resolvers));
  }

  public Component mm(MessageKey key, TagResolver... resolvers) {
    return mm(key.getPath(), resolvers);
  }

  public Component mm(String key, Map<String, String> placeholders) {
    return mini.deserialize(raw(key), toResolvers(placeholders));
  }

  public Component mm(MessageKey key, Map<String, String> placeholders) {
    return mm(key.getPath(), placeholders);
  }

  public Component prefixed(MessageKey key) {
    return prefixed(key.getPath(), TagResolver.empty());
  }

  public Component prefixed(String key) {
    return prefixed(key, TagResolver.empty());
  }

  public Component prefixed(String key, TagResolver... resolvers) {
    Component prefix = mini.deserialize(raw(MessageKey.PREFIX.getPath()));
    Component body = mini.deserialize(raw(key), TagResolver.resolver(resolvers));
    return Component.empty().append(prefix).append(Component.space()).append(body);
  }

  public Component prefixed(MessageKey key, TagResolver... resolvers) {
    return prefixed(key.getPath(), resolvers);
  }

  public Component prefixed(String key, Map<String, String> placeholders) {
    return prefixed(key, toResolvers(placeholders));
  }

  public Component prefixed(MessageKey key, Map<String, String> placeholders) {
    return prefixed(key.getPath(), placeholders);
  }

  public Component prefixed(String key, String... keyValuePairs) {
    return prefixed(key, toResolvers(toMap(keyValuePairs)));
  }

  public Component prefixed(MessageKey key, String... keyValuePairs) {
    return prefixed(key.getPath(), keyValuePairs);
  }

  private TagResolver toResolvers(Map<String, String> placeholders) {
    if (placeholders == null || placeholders.isEmpty()) return TagResolver.empty();
    TagResolver.Builder b = TagResolver.builder();
    for (Map.Entry<String, String> e : placeholders.entrySet()) {
      b = b.resolver(Placeholder.parsed(e.getKey(), e.getValue() == null ? "null" : e.getValue()));
    }
    return b.build();
  }

  private Map<String, String> toMap(String... keyValuePairs) {
    if (keyValuePairs == null || keyValuePairs.length == 0)
      return java.util.Collections.emptyMap();
    if (keyValuePairs.length % 2 != 0) {
      throw new IllegalArgumentException("Placeholders must be alternating key/value pairs.");
    }
    java.util.Map<String, String> map = new java.util.HashMap<>(keyValuePairs.length / 2);
    for (int i = 0; i < keyValuePairs.length; i += 2) {
      map.put(keyValuePairs[i], keyValuePairs[i + 1]);
    }
    return map;
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
    try {
      var cfg = plugin.getConfigurationManager();
      if (cfg != null) {
        String val = cfg.getLanguageOverride();
        if (val != null && !val.isBlank() && !"auto".equalsIgnoreCase(val)) {
          return normalizeLocale(val);
        }
      }
    } catch (Exception ignored) {
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
    String[] parts = trimmed.split("_", 2);
    if (parts.length == 2) {
      return parts[0].toLowerCase(Locale.ROOT) + "_" + parts[1].toUpperCase(Locale.ROOT);
    }
    return trimmed.toLowerCase(Locale.ROOT);
  }
}