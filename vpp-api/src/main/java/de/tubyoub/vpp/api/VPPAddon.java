package de.tubyoub.vpp.api;

/**
 * Entry point for VPP-managed addons.
 * <p>
 * Discoverable via {@code META-INF/services/de.tubyoub.vpp.api.VPPAddon}.
 * Prefer registering commands/events/routing through {@link AddonContext}
 * so they are cleaned up automatically on disable/reload.
 */
public interface VPPAddon {
    /** Static metadata for this addon (id, version, etc.). */
    AddonMeta meta();

    /** Called when the addon is enabled. */
    void onEnable(AddonContext context) throws Exception;

    /** Called when the addon is disabled or reloaded. */
    default void onDisable() throws Exception {}
}
