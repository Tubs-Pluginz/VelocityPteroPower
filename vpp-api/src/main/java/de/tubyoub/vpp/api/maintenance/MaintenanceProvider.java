package de.tubyoub.vpp.api.maintenance;

/**
 * Pluggable maintenance gate for VelocityPteroPower.
 *
 * <p>VPP ships a built-in provider ({@code /ptero maintenance}). Networks that already
 * use another plugin to own the maintenance cycle (e.g. kennytv/Maintenance) — or an
 * addon that wraps their own system — can register an additional provider. VPP blocks
 * a cold-start when <strong>any</strong> active provider says the target is blocked.
 *
 * <p>Providers must not depend on Velocity types in this interface.
 */
public interface MaintenanceProvider {
    /** Stable id for logs / config (e.g. {@code "vpp-builtin"}, {@code "kennytv-maintenance"}). */
    String id();

    /**
     * Higher priority providers are consulted first for the human-readable detail string.
     * Blocking is still OR'd across all active providers.
     */
    default int priority() {
        return 0;
    }

    /** {@code false} when the backing plugin is missing or the provider is disabled. */
    boolean isActive();

    /**
     * @return {@code true} if VPP must not power-on / auto-connect this server for the query
     */
    boolean blocks(MaintenanceQuery query);

    /** Short detail appended to player messages, e.g. {@code " (network)"} or {@code " (external)"}. */
    default String detail(MaintenanceQuery query) {
        return "";
    }

    /**
     * Optional: notify VPP when this provider's maintenance lifts so waiting players can retry.
     * Built-in and kennytv providers use this for limbo auto-reconnect.
     */
    default void addClearListener(MaintenanceClearListener listener) {}

    default void removeClearListener(MaintenanceClearListener listener) {}
}
