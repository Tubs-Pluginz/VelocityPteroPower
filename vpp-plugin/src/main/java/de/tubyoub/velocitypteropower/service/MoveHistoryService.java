package de.tubyoub.velocitypteropower.service;

import com.velocitypowered.api.proxy.Player;
import de.tubyoub.velocitypteropower.VelocityPteroPower;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * In-memory per-session player move history (server switches), optional via config.
 * No disk persistence by design (as requested).
 */
public class MoveHistoryService {

    public static final class MoveRecord {
        private final long timestamp;
        private final String fromServer; // may be null or "-" on first join
        private final String toServer;   // may be special tokens like "DISCONNECT"
        private final UUID playerId;
        private final String playerName;

        public MoveRecord(UUID playerId, String playerName, String fromServer, String toServer) {
            this.timestamp = Instant.now().toEpochMilli();
            this.playerId = playerId;
            this.playerName = playerName;
            this.fromServer = fromServer == null ? "-" : fromServer;
            this.toServer = toServer == null ? "-" : toServer;
        }

        public long getTimestamp() { return timestamp; }
        public String getFromServer() { return fromServer; }
        public String getToServer() { return toServer; }
        public UUID getPlayerId() { return playerId; }
        public String getPlayerName() { return playerName; }
    }

    private final VelocityPteroPower plugin;
    private final int maxEntriesPerPlayer;
    private final Map<UUID, Deque<MoveRecord>> history = new ConcurrentHashMap<>();

    public MoveHistoryService(VelocityPteroPower plugin, int maxEntriesPerPlayer) {
        this.plugin = plugin;
        this.maxEntriesPerPlayer = Math.max(1, maxEntriesPerPlayer);
    }

    public void record(Player player, String fromServer, String toServer) {
        UUID id = player.getUniqueId();
        String name = player.getUsername();
        Deque<MoveRecord> deque = history.computeIfAbsent(id, k -> new ConcurrentLinkedDeque<>());
        deque.addFirst(new MoveRecord(id, name, fromServer, toServer));
        // trim
        while (deque.size() > maxEntriesPerPlayer) {
            deque.removeLast();
        }
    }

    public void record(UUID playerId, String playerName, String fromServer, String toServer) {
        Deque<MoveRecord> deque = history.computeIfAbsent(playerId, k -> new ConcurrentLinkedDeque<>());
        deque.addFirst(new MoveRecord(playerId, playerName, fromServer, toServer));
        while (deque.size() > maxEntriesPerPlayer) {
            deque.removeLast();
        }
    }

    public List<MoveRecord> getHistory(UUID playerId) {
        Deque<MoveRecord> deque = history.get(playerId);
        if (deque == null) return Collections.emptyList();
        return new ArrayList<>(deque);
    }

    public Optional<String> findLastKnownName(UUID playerId) {
        Deque<MoveRecord> deque = history.get(playerId);
        if (deque == null || deque.isEmpty()) return Optional.empty();
        return Optional.ofNullable(deque.peekFirst().getPlayerName());
    }

    public void clear(UUID playerId) {
        history.remove(playerId);
    }

    public static Component renderPretty(List<MoveRecord> records, Locale locale) {
        MiniMessage mm = MiniMessage.miniMessage();
        if (records == null || records.isEmpty()) {
            return mm.deserialize("<gray>No move history recorded.</gray>");
        }
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withLocale(locale).withZone(ZoneId.systemDefault());
        StringBuilder sb = new StringBuilder();
        sb.append("<gradient:#00e1ff:#6a11cb>Move History</gradient> <gray>(latest first)</gray>\n");
        for (MoveRecord r : records) {
            String ts = fmt.format(Instant.ofEpochMilli(r.getTimestamp()));
            sb.append("<gray>[").append(ts).append("]</gray> ")
              .append("<yellow>").append(escape(r.getFromServer())).append("</yellow>")
              .append(" <gray>→</gray> ")
              .append("<green>").append(escape(r.getToServer())).append("</green>")
              .append("\n");
        }
        return mm.deserialize(sb.toString());
    }

    private static String escape(String s) {
        if (s == null) return "-";
        // very small sanitization for MM
        return s.replace("<", "").replace(">", "");
    }
}
