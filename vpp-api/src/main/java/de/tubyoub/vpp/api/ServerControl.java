package de.tubyoub.vpp.api;

/**
 * Control facade for starting/stopping managed servers via VPP.
 * Implementations must route through the core so events and rate limiting apply.
 */
public interface ServerControl {
    /**
     * Request a start for the managed server by its Velocity name.
     * No-op if the server name is unknown, rate-limited, in maintenance, or over the online cap.
     */
    void startByName(String serverName);

    /**
     * Request a stop for the managed server by its Velocity name.
     * No-op if the server name is unknown or rate-limited.
     */
    void stopByName(String serverName);

    /**
     * Request a restart for the managed server by its Velocity name.
     * No-op if the server name is unknown, rate-limited, or in maintenance.
     */
    default void restartByName(String serverName) {
        stopByName(serverName);
        startByName(serverName);
    }

    /**
     * Best-effort online check for the managed server by name.
     */
    boolean isOnline(String serverName);
}
