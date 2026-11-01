package de.tubyoub.vpp.api.event;

/**
 * Fired before VPP sends a power signal to a server via the configured panel.
 * Listeners may cancel or modify the signal.
 */
public class ServerPowerSignalEvent implements Cancellable {
    private final String serverName; // Velocity-registered server name if known (may be null)
    private final String serverId;   // Panel server ID
    private String signal;           // START, STOP, RESTART, KILL
    private boolean cancelled;

    public ServerPowerSignalEvent(String serverName, String serverId, String signal) {
        this.serverName = serverName;
        this.serverId = serverId;
        this.signal = signal;
    }

    public String getServerName() { return serverName; }
    public String getServerId() { return serverId; }

    public String getSignal() { return signal; }
    public void setSignal(String signal) { this.signal = signal; }

    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
}
