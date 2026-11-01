package de.tubyoub.vpp.addon.movehistory;

import de.tubyoub.vpp.api.AddonCommand;
import de.tubyoub.vpp.api.AddonConfig;
import de.tubyoub.vpp.api.AddonContext;
import de.tubyoub.vpp.api.AddonMeta;
import de.tubyoub.vpp.api.CommandActor;
import de.tubyoub.vpp.api.VPPAddon;
import de.tubyoub.vpp.api.event.PlayerDisconnectEvent;
import de.tubyoub.vpp.api.event.PlayerPostConnectEvent;
import de.tubyoub.vpp.api.event.PlayerPreServerSwitchEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Persistent player move/connect audit with moderation commands ({@code /ptero audit}).
 * Independent of the core in-memory {@code /ptero history}.
 */
public final class MoveHistoryAddon implements VPPAddon {
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private AddonContext ctx;
    private AddonConfig cfg;
    private Path storeFile;
    private Path dataDir;
    private final Map<UUID, Deque<AuditRecord>> history = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastKnownNames = new ConcurrentHashMap<>();
    private ScheduledExecutorService purgeExecutor;

    @Override
    public AddonMeta meta() {
        return AddonMeta.builder("vpp-move-history")
                .name("VPP Move History")
                .version("1.0.0")
                .authors("TubYoub")
                .description("Persistent move/connect audit history with moderation tools")
                .apiVersion("1.0.0")
                .build();
    }

    @Override
    public void onEnable(AddonContext context) {
        this.ctx = context;
        this.cfg = context.getConfig();
        this.dataDir = context.getDataDirectory();
        this.storeFile = dataDir.resolve("history.json");
        seedDefaults();
        load();

        ctx.subscribe(PlayerPostConnectEvent.class, this::onConnect);
        ctx.subscribe(PlayerDisconnectEvent.class, this::onDisconnect);
        ctx.subscribe(PlayerPreServerSwitchEvent.class, this::onSwitch);
        ctx.registerCommand(new AuditCmd());

        purgeExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "vpp-move-history-purge");
            t.setDaemon(true);
            return t;
        });
        purgeExecutor.scheduleAtFixedRate(() -> {
            try {
                int removed = applyRetention();
                if (removed > 0) {
                    save();
                    ctx.getLogger().debug("Periodic retention purge removed " + removed + " entries");
                }
            } catch (Exception e) {
                ctx.getLogger().warn("Retention purge failed: " + e.getMessage());
            }
        }, 5, 30, TimeUnit.MINUTES);

        ctx.getLogger().info("Move-history addon enabled (players tracked=" + history.size()
                + ", maxEntriesPerPlayer=" + cfg.getInt("maxEntriesPerPlayer", 100)
                + ", retentionDays=" + cfg.getInt("retentionDays", 30) + ")");
    }

    @Override
    public void onDisable() {
        try {
            save();
        } catch (Exception e) {
            if (ctx != null) {
                ctx.getLogger().warn("Failed to save history on disable: " + e.getMessage());
            }
        }
        if (purgeExecutor != null) {
            purgeExecutor.shutdownNow();
            purgeExecutor = null;
        }
        history.clear();
        lastKnownNames.clear();
        cfg = null;
        ctx = null;
    }

    private void seedDefaults() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("enabled", true);
        defaults.put("maxEntriesPerPlayer", 100);
        defaults.put("retentionDays", 30);
        defaults.put("recordConnect", true);
        defaults.put("recordDisconnect", true);
        defaults.put("recordSwitch", true);
        cfg.ensureDefaults(defaults);
        cfg.save();
    }

    private void onConnect(PlayerPostConnectEvent ev) {
        if (!cfg.getBoolean("enabled", true) || !cfg.getBoolean("recordConnect", true)) {
            return;
        }
        record(ev.getPlayerId(), ev.getPlayerName(), null, ev.getTargetServer(), "CONNECT");
    }

    private void onDisconnect(PlayerDisconnectEvent ev) {
        if (!cfg.getBoolean("enabled", true) || !cfg.getBoolean("recordDisconnect", true)) {
            return;
        }
        record(ev.getPlayerId(), ev.getPlayerName(), ev.getLastServer(), "DISCONNECT", "DISCONNECT");
    }

    private void onSwitch(PlayerPreServerSwitchEvent ev) {
        if (!cfg.getBoolean("enabled", true) || !cfg.getBoolean("recordSwitch", true)) {
            return;
        }
        record(ev.getPlayerId(), ev.getPlayerName(), ev.getFromServer(), ev.getTargetServer(), "SWITCH");
    }

    private void record(UUID playerId, String playerName, String from, String to, String kind) {
        if (playerId == null) {
            return;
        }
        if (playerName != null && !playerName.isBlank()) {
            lastKnownNames.put(playerId, playerName);
        }
        AuditRecord rec = new AuditRecord(
                System.currentTimeMillis(),
                playerId,
                playerName != null ? playerName : lastKnownNames.getOrDefault(playerId, playerId.toString()),
                from == null ? "" : from,
                to == null ? "" : to,
                kind);
        Deque<AuditRecord> deque = history.computeIfAbsent(playerId, id -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addFirst(rec);
        }
        ctx.getLogger().debug("Audit " + kind + " player=" + rec.playerName
                + " from=" + rec.fromServer + " to=" + rec.toServer);
        applyRetentionFor(playerId);
        save();
    }

    private int applyRetention() {
        int removed = 0;
        for (UUID id : new ArrayList<>(history.keySet())) {
            removed += applyRetentionFor(id);
        }
        return removed;
    }

    private int applyRetentionFor(UUID playerId) {
        Deque<AuditRecord> deque = history.get(playerId);
        if (deque == null) {
            return 0;
        }
        int max = Math.max(1, cfg.getInt("maxEntriesPerPlayer", 100));
        int retentionDays = cfg.getInt("retentionDays", 30);
        long cutoff = retentionDays > 0
                ? System.currentTimeMillis() - retentionDays * 86_400_000L
                : Long.MIN_VALUE;
        int removed = 0;
        synchronized (deque) {
            while (deque.size() > max) {
                deque.removeLast();
                removed++;
            }
            if (retentionDays > 0) {
                Iterator<AuditRecord> it = deque.iterator();
                while (it.hasNext()) {
                    AuditRecord r = it.next();
                    if (r.timestamp < cutoff) {
                        it.remove();
                        removed++;
                    }
                }
            }
            if (deque.isEmpty()) {
                history.remove(playerId);
            }
        }
        if (removed > 0) {
            ctx.getLogger().debug("Retention trimmed " + removed + " entries for " + playerId);
        }
        return removed;
    }

    private List<AuditRecord> getHistory(UUID playerId) {
        Deque<AuditRecord> deque = history.get(playerId);
        if (deque == null) {
            return List.of();
        }
        synchronized (deque) {
            return new ArrayList<>(deque);
        }
    }

    private void clear(UUID playerId) {
        history.remove(playerId);
        save();
        ctx.getLogger().info("Cleared audit history for " + playerId);
    }

    private synchronized void load() {
        history.clear();
        if (!Files.isRegularFile(storeFile)) {
            ctx.getLogger().debug("No history file at " + storeFile);
            return;
        }
        try {
            String raw = Files.readString(storeFile, StandardCharsets.UTF_8).trim();
            if (raw.isEmpty() || "[]".equals(raw)) {
                return;
            }
            // Simple hand-rolled JSON array of objects
            int loaded = 0;
            int pos = 0;
            while (pos < raw.length()) {
                int start = raw.indexOf('{', pos);
                if (start < 0) {
                    break;
                }
                int end = raw.indexOf('}', start);
                if (end < 0) {
                    break;
                }
                String obj = raw.substring(start, end + 1);
                AuditRecord rec = AuditRecord.parse(obj);
                if (rec != null) {
                    Deque<AuditRecord> deque = history.computeIfAbsent(rec.playerId, id -> new ArrayDeque<>());
                    deque.addLast(rec); // file order is oldest-first within each append batch; we normalize below
                    lastKnownNames.put(rec.playerId, rec.playerName);
                    loaded++;
                }
                pos = end + 1;
            }
            // Ensure latest-first per player
            for (Map.Entry<UUID, Deque<AuditRecord>> e : history.entrySet()) {
                List<AuditRecord> list = new ArrayList<>(e.getValue());
                list.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
                e.setValue(new ArrayDeque<>(list));
            }
            applyRetention();
            ctx.getLogger().info("Loaded " + loaded + " audit records for " + history.size() + " player(s)");
        } catch (IOException e) {
            ctx.getLogger().error("Failed to load history.json: " + e.getMessage(), e);
        }
    }

    private synchronized void save() {
        try {
            Files.createDirectories(dataDir);
            StringBuilder sb = new StringBuilder();
            sb.append("[\n");
            boolean first = true;
            // Persist oldest-first for stable append readability
            List<AuditRecord> all = new ArrayList<>();
            for (Deque<AuditRecord> deque : history.values()) {
                synchronized (deque) {
                    all.addAll(deque);
                }
            }
            all.sort((a, b) -> Long.compare(a.timestamp, b.timestamp));
            for (AuditRecord r : all) {
                if (!first) {
                    sb.append(",\n");
                }
                first = false;
                sb.append("  ").append(r.toJson());
            }
            sb.append("\n]\n");
            Files.writeString(storeFile, sb.toString(), StandardCharsets.UTF_8);
            ctx.getLogger().debug("Saved " + all.size() + " audit records to " + storeFile);
        } catch (IOException e) {
            ctx.getLogger().error("Failed to save history.json: " + e.getMessage(), e);
        }
    }

    private Path export(UUID playerId) throws IOException {
        Files.createDirectories(dataDir);
        String suffix = playerId != null ? playerId.toString() : "all";
        Path out = dataDir.resolve("export-" + suffix + "-" + System.currentTimeMillis() + ".json");
        List<AuditRecord> recs;
        if (playerId != null) {
            recs = getHistory(playerId);
        } else {
            recs = new ArrayList<>();
            for (Deque<AuditRecord> deque : history.values()) {
                synchronized (deque) {
                    recs.addAll(deque);
                }
            }
            recs.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
        }
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < recs.size(); i++) {
            if (i > 0) {
                sb.append(",\n");
            }
            sb.append("  ").append(recs.get(i).toJson());
        }
        sb.append("\n]\n");
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        return out;
    }

    private UUID resolvePlayer(String nameOrUuid) {
        if (nameOrUuid == null || nameOrUuid.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(nameOrUuid);
        } catch (IllegalArgumentException ignored) {
        }
        String needle = nameOrUuid.toLowerCase(Locale.ROOT);
        for (Map.Entry<UUID, String> e : lastKnownNames.entrySet()) {
            if (e.getValue() != null && e.getValue().equalsIgnoreCase(needle)) {
                return e.getKey();
            }
        }
        for (Map.Entry<UUID, Deque<AuditRecord>> e : history.entrySet()) {
            Deque<AuditRecord> deque = e.getValue();
            synchronized (deque) {
                for (AuditRecord r : deque) {
                    if (r.playerName != null && r.playerName.equalsIgnoreCase(needle)) {
                        return e.getKey();
                    }
                }
            }
        }
        return null;
    }

    private record AuditRecord(
            long timestamp,
            UUID playerId,
            String playerName,
            String fromServer,
            String toServer,
            String kind
    ) {
        String toJson() {
            return "{"
                    + "\"timestamp\":" + timestamp + ","
                    + "\"playerId\":" + jsonString(playerId.toString()) + ","
                    + "\"playerName\":" + jsonString(playerName) + ","
                    + "\"fromServer\":" + jsonString(fromServer) + ","
                    + "\"toServer\":" + jsonString(toServer) + ","
                    + "\"kind\":" + jsonString(kind)
                    + "}";
        }

        static AuditRecord parse(String obj) {
            try {
                long ts = Long.parseLong(extractNumber(obj, "timestamp"));
                UUID id = UUID.fromString(extractString(obj, "playerId"));
                String name = extractString(obj, "playerName");
                String from = extractString(obj, "fromServer");
                String to = extractString(obj, "toServer");
                String kind = extractString(obj, "kind");
                return new AuditRecord(ts, id, name, from, to, kind);
            } catch (Exception e) {
                return null;
            }
        }

        private static String extractString(String json, String key) {
            String needle = "\"" + key + "\"";
            int i = json.indexOf(needle);
            if (i < 0) {
                return "";
            }
            int colon = json.indexOf(':', i + needle.length());
            int q1 = json.indexOf('"', colon + 1);
            if (q1 < 0) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (int p = q1 + 1; p < json.length(); p++) {
                char c = json.charAt(p);
                if (c == '\\' && p + 1 < json.length()) {
                    sb.append(json.charAt(p + 1));
                    p++;
                    continue;
                }
                if (c == '"') {
                    break;
                }
                sb.append(c);
            }
            return sb.toString();
        }

        private static String extractNumber(String json, String key) {
            String needle = "\"" + key + "\"";
            int i = json.indexOf(needle);
            if (i < 0) {
                return "0";
            }
            int colon = json.indexOf(':', i + needle.length());
            int start = colon + 1;
            while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
                start++;
            }
            int end = start;
            while (end < json.length() && (Character.isDigit(json.charAt(end)))) {
                end++;
            }
            return json.substring(start, end);
        }

        private static String jsonString(String s) {
            if (s == null) {
                return "\"\"";
            }
            StringBuilder sb = new StringBuilder("\"");
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '\\' -> sb.append("\\\\");
                    case '"' -> sb.append("\\\"");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> sb.append(c);
                }
            }
            sb.append('"');
            return sb.toString();
        }
    }

    private final class AuditCmd implements AddonCommand {
        @Override
        public String name() {
            return "audit";
        }

        @Override
        public List<String> aliases() {
            return List.of("moveaudit", "phistory");
        }

        @Override
        public String description() {
            return "Persistent move/connect audit (lookup, clear, export)";
        }

        @Override
        public String permission() {
            return "vpp.audit.use";
        }

        @Override
        public void execute(CommandActor actor, String[] args) {
            if (!cfg.getBoolean("enabled", true)) {
                actor.sendMessage("<red>Persistent move history is disabled in addon config.</red>");
                return;
            }
            if (args == null || args.length == 0) {
                actor.sendMessage("<yellow>Usage:</yellow> <gray>/ptero audit [player|uuid]</gray>");
                actor.sendMessage("<yellow>       </yellow> <gray>/ptero audit clear <player|uuid></gray>");
                actor.sendMessage("<yellow>       </yellow> <gray>/ptero audit export [player|uuid]</gray>");
                return;
            }
            String sub = args[0].toLowerCase(Locale.ROOT);
            if ("clear".equals(sub)) {
                if (!actor.hasPermission("vpp.audit.manage") && !actor.hasPermission("ptero.history")) {
                    actor.sendMessage("<red>No permission.</red>");
                    return;
                }
                if (args.length < 2) {
                    actor.sendMessage("<red>Usage:</red> <gray>/ptero audit clear <player|uuid></gray>");
                    return;
                }
                UUID id = resolvePlayer(args[1]);
                if (id == null) {
                    actor.sendMessage("<red>Player not found in audit store:</red> <aqua>" + args[1] + "</aqua>");
                    return;
                }
                clear(id);
                actor.sendMessage("<green>Cleared audit history for</green> <aqua>"
                        + lastKnownNames.getOrDefault(id, id.toString()) + "</aqua>");
                return;
            }
            if ("export".equals(sub)) {
                if (!actor.hasPermission("vpp.audit.manage") && !actor.hasPermission("ptero.history")) {
                    actor.sendMessage("<red>No permission.</red>");
                    return;
                }
                try {
                    UUID id = args.length >= 2 ? resolvePlayer(args[1]) : null;
                    if (args.length >= 2 && id == null) {
                        actor.sendMessage("<red>Player not found:</red> <aqua>" + args[1] + "</aqua>");
                        return;
                    }
                    Path out = export(id);
                    actor.sendMessage("<green>Exported audit to</green> <aqua>" + out.toAbsolutePath() + "</aqua>");
                    ctx.getLogger().info("Audit export by " + actor.getName() + " -> " + out);
                } catch (IOException e) {
                    actor.sendMessage("<red>Export failed:</red> " + e.getMessage());
                    ctx.getLogger().error("Audit export failed: " + e.getMessage(), e);
                }
                return;
            }

            // lookup: /ptero audit <player>
            UUID id = resolvePlayer(args[0]);
            if (id == null) {
                actor.sendMessage("<red>Player not found in audit store:</red> <aqua>" + args[0] + "</aqua>");
                actor.sendMessage("<gray>Tip: use UUID if the player has not been seen since this addon was enabled.</gray>");
                return;
            }
            List<AuditRecord> recs = getHistory(id);
            String display = lastKnownNames.getOrDefault(id, id.toString());
            actor.sendMessage("<gold>Audit for</gold> <aqua>" + display + "</aqua> <gray>("
                    + recs.size() + " entries)</gray>");
            if (recs.isEmpty()) {
                actor.sendMessage("<yellow>No entries.</yellow>");
                return;
            }
            int limit = Math.min(recs.size(), 25);
            for (int i = 0; i < limit; i++) {
                AuditRecord r = recs.get(i);
                String when = TIME_FMT.format(Instant.ofEpochMilli(r.timestamp));
                actor.sendMessage("<dark_gray>[" + when + "]</dark_gray> <yellow>" + r.kind + "</yellow> "
                        + "<white>" + emptyDash(r.fromServer) + "</white> <dark_gray>→</dark_gray> <white>"
                        + emptyDash(r.toServer) + "</white>");
            }
            if (recs.size() > limit) {
                actor.sendMessage("<gray>... and " + (recs.size() - limit) + " more (use export for full dump)</gray>");
            }
        }

        private static String emptyDash(String s) {
            return (s == null || s.isBlank()) ? "—" : s;
        }

        @Override
        public List<String> suggest(CommandActor actor, String[] args) {
            if (args == null || args.length == 0) {
                return List.of("clear", "export");
            }
            if (args.length == 1) {
                List<String> out = new ArrayList<>();
                String prefix = args[0].toLowerCase(Locale.ROOT);
                for (String s : List.of("clear", "export")) {
                    if (s.startsWith(prefix)) {
                        out.add(s);
                    }
                }
                for (String name : lastKnownNames.values()) {
                    if (name != null && name.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                        out.add(name);
                    }
                }
                return out;
            }
            if (args.length == 2 && ("clear".equalsIgnoreCase(args[0]) || "export".equalsIgnoreCase(args[0]))) {
                String prefix = args[1].toLowerCase(Locale.ROOT);
                List<String> out = new ArrayList<>();
                for (String name : lastKnownNames.values()) {
                    if (name != null && name.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                        out.add(name);
                    }
                }
                return out;
            }
            return List.of();
        }
    }
}
