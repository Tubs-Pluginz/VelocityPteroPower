package de.tubyoub.vpp.api.event;

import java.util.UUID;

public class PlayerPreServerSwitchEvent implements Cancellable {
    private final UUID playerId;
    private final String playerName;
    private final String fromServer; // may be null
    private String targetServer; // can be overridden
    private final String reason; // free-form, e.g., COMMAND, FALLBACK
    private boolean cancelled;

    public PlayerPreServerSwitchEvent(UUID playerId, String playerName, String fromServer, String targetServer, String reason) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.fromServer = fromServer;
        this.targetServer = targetServer;
        this.reason = reason;
    }

    public UUID getPlayerId() { return playerId; }
    public String getPlayerName() { return playerName; }
    public String getFromServer() { return fromServer; }

    public String getTargetServer() { return targetServer; }
    public void setTargetServer(String targetServer) { this.targetServer = targetServer; }

    public String getReason() { return reason; }

    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
}
