package de.tubyoub.vpp.api.event;

import java.util.UUID;

public class PlayerDisconnectEvent implements VPPEvent {
    private final UUID playerId;
    private final String playerName;
    private final String lastServer; // may be null

    public PlayerDisconnectEvent(UUID playerId, String playerName, String lastServer) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.lastServer = lastServer;
    }

    public UUID getPlayerId() { return playerId; }
    public String getPlayerName() { return playerName; }
    public String getLastServer() { return lastServer; }
}
