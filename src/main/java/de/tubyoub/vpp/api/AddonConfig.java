package de.tubyoub.vpp.api;

import java.util.List;
import java.util.Map;

/**
 * Lightweight per-addon configuration handle.
 * Backed by a YAML file stored separately from the main VPP config.
 */
public interface AddonConfig {
    // Basic typed getters with defaults
    boolean getBoolean(String path, boolean def);
    int getInt(String path, int def);
    double getDouble(String path, double def);
    String getString(String path, String def);
    List<String> getStringList(String path);

    // Mutations
    void set(String path, Object value);

    // Lifecycle
    void save();
    void reload();

    /**
     * Ensure defaults (only sets keys that are currently absent).
     */
    void ensureDefaults(Map<String, Object> defaults);

    /**
     * Returns the addon id this config belongs to.
     */
    String getAddonId();
}
