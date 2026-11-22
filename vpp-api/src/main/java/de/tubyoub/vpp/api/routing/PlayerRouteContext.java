package de.tubyoub.vpp.api.routing;

import java.util.UUID;

/**
 * Context passed to a RoutingProvider to decide a target server for a player.
 * Velocity-agnostic: only UUID and Strings are used.
 */
public final class PlayerRouteContext {
    private final UUID playerId;
    private final String playerName;
    private final String requestedServer; // the server Velocity is about to route to (may be null on initial join)
    private final String reason; // free-form, e.g., INITIAL, SWITCH, COMMAND, FALLBACK

    private String targetServer; // provider may set/override the final target by Velocity name
    private boolean cancelled; // provider may cancel the routing (deny connection)

    public PlayerRouteContext(UUID playerId, String playerName, String requestedServer, String reason) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.requestedServer = requestedServer;
        this.reason = reason;
        this.targetServer = requestedServer;
    }

    public UUID getPlayerId() { return playerId; }
    public String getPlayerName() { return playerName; }
    public String getRequestedServer() { return requestedServer; }
    public String getReason() { return reason; }

    public String getTargetServer() { return targetServer; }
    public void setTargetServer(String targetServer) { this.targetServer = targetServer; }

    public boolean isCancelled() { return cancelled; }
    public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
}
