package de.tubyoub.velocitypteropower.service;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Value object to capture a player's limbo status and why it happened.
 */
public final class PlayerLimboRecord {
    private final UUID playerId;
    private final String playerName;
    private final String limboServer;
    private final LimboReason reason;
    private final String context; // e.g., requested target server name
    private final long timestamp; // epoch millis

    public PlayerLimboRecord(UUID playerId, String playerName, String limboServer, LimboReason reason, String context) {
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.playerName = Objects.requireNonNull(playerName, "playerName");
        this.limboServer = Objects.requireNonNull(limboServer, "limboServer");
        this.reason = Objects.requireNonNull(reason, "reason");
        this.context = context;
        this.timestamp = Instant.now().toEpochMilli();
    }

    public UUID getPlayerId() { return playerId; }
    public String getPlayerName() { return playerName; }
    public String getLimboServer() { return limboServer; }
    public LimboReason getReason() { return reason; }
    public String getContext() { return context; }
    public long getTimestamp() { return timestamp; }
}
