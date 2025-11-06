package de.tubyoub.velocitypteropower.api;

import de.tubyoub.vpp.api.AddonConfig;
import dev.dejvokep.boostedyaml.YamlDocument;
import dev.dejvokep.boostedyaml.settings.dumper.DumperSettings;
import dev.dejvokep.boostedyaml.settings.general.GeneralSettings;
import dev.dejvokep.boostedyaml.settings.loader.LoaderSettings;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class AddonConfigImpl implements AddonConfig {
    private final String addonId;
    private final Path baseDir;
    private YamlDocument yaml;

    public AddonConfigImpl(String addonId, Path dataDirectory) {
        this.addonId = addonId;
        this.baseDir = dataDirectory.resolve("addons");
        ensureLoaded();
    }

    private synchronized void ensureLoaded() {
        try {
            Files.createDirectories(baseDir);
            File file = baseDir.resolve(addonId + ".yml").toFile();
            if (!file.exists()) {
                file.createNewFile();
            }
            if (yaml == null) {
                yaml = YamlDocument.create(
                        file,
                        null,
                        GeneralSettings.DEFAULT,
                        LoaderSettings.builder().setAutoUpdate(false).build(),
                        DumperSettings.DEFAULT,
                        null
                );
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load addon config '" + addonId + "': " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized boolean getBoolean(String path, boolean def) {
        Object v = yaml.get(path, def);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return Boolean.parseBoolean((String) v);
        return def;
        }

    @Override
    public synchronized int getInt(String path, int def) {
        Object v = yaml.get(path, def);
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(String.valueOf(v)); } catch (Exception ignored) {}
        return def;
    }

    @Override
    public synchronized double getDouble(String path, double def) {
        Object v = yaml.get(path, def);
        if (v instanceof Number) return ((Number) v).doubleValue();
        try { return Double.parseDouble(String.valueOf(v)); } catch (Exception ignored) {}
        return def;
    }

    @Override
    public synchronized String getString(String path, String def) {
        String v = yaml.getString(path);
        return v != null ? v : def;
    }

    @Override
    public synchronized List<String> getStringList(String path) {
        List<String> list = yaml.getStringList(path);
        return list != null ? list : java.util.Collections.emptyList();
    }

    @Override
    public synchronized void set(String path, Object value) {
        yaml.set(path, value);
    }

    @Override
    public synchronized void save() {
        try {
            yaml.save();
        } catch (IOException e) {
            throw new RuntimeException("Failed to save addon config '" + addonId + "': " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized void reload() {
        try {
            yaml.reload();
        } catch (IOException e) {
            throw new RuntimeException("Failed to reload addon config '" + addonId + "': " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized void ensureDefaults(Map<String, Object> defaults) {
        if (defaults == null || defaults.isEmpty()) return;
        for (Map.Entry<String, Object> e : defaults.entrySet()) {
            Object cur = yaml.get(e.getKey());
            if (cur == null) yaml.set(e.getKey(), e.getValue());
        }
    }

    @Override
    public String getAddonId() {
        return addonId;
    }
}
