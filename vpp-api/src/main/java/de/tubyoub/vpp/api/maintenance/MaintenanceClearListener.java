package de.tubyoub.vpp.api.maintenance;

/**
 * Notified when an external (or built-in) maintenance cycle clears for a server.
 *
 * @param serverName Velocity server name, or {@code null} when network-wide maintenance cleared
 */
@FunctionalInterface
public interface MaintenanceClearListener {
    void onMaintenanceCleared(String serverName);
}
