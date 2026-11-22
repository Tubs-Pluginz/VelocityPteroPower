package de.tubyoub.vpp.api.event;

import java.util.UUID;

public class PlayerPreConnectEvent implements Cancellable {
    private final UUID playerId;
    private final String playerName;
    private final String requestedServer; // may be null
    private String targetServer; // may be set/overridden by listeners
    private boolean cancelled;

    public PlayerPreConnectEvent(UUID playerId, String playerName, String requestedServer, String targetServer) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.requestedServer = requestedServer;
        this.targetServer = targetServer;
    }

    public UUID getPlayerId() { return playerId; }
    public String getPlayerName() { return playerName; }
    public String getRequestedServer() { return requestedServer; }

    public String getTargetServer() { return targetServer; }
    public void setTargetServer(String targetServer) { this.targetServer = targetServer; }

    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
}
