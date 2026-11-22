package de.tubyoub.vpp.api.event;

import java.util.UUID;

public class PlayerPostConnectEvent implements VPPEvent {
    private final UUID playerId;
    private final String playerName;
    private final String targetServer;

    public PlayerPostConnectEvent(UUID playerId, String playerName, String targetServer) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.targetServer = targetServer;
    }

    public UUID getPlayerId() { return playerId; }
    public String getPlayerName() { return playerName; }
    public String getTargetServer() { return targetServer; }
}
