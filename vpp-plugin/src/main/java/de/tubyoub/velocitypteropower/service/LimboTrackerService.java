package de.tubyoub.velocitypteropower.service;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import de.tubyoub.velocitypteropower.VelocityPteroPower;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Tracks players that are on a limbo server and why they are there.
 * Provides simple query and cleanup helpers and a periodic sweep to remove stale entries.
 */
public class LimboTrackerService {

    private final VelocityPteroPower plugin;
    private final ComponentLogger logger;

    // Active limbo records by player UUID
    private final Map<UUID, PlayerLimboRecord> records = new ConcurrentHashMap<>();

    public LimboTrackerService(VelocityPteroPower plugin) {
        this.plugin = plugin;
        this.logger = plugin.getFilteredLogger();
    }

    public void record(Player player, RegisteredServer limbo, LimboReason reason, String context) {
        PlayerLimboRecord rec = new PlayerLimboRecord(
                player.getUniqueId(),
                player.getUsername(),
                limbo.getServerInfo().getName(),
                reason,
                context
        );
        records.put(player.getUniqueId(), rec);
        logger.debug("Recorded limbo: {} -> {} reason={} context={}", player.getUsername(), rec.getLimboServer(), reason, context);
    }

    public void recordSelfMove(Player player, RegisteredServer limbo) {
        record(player, limbo, LimboReason.SELF_MOVE, null);
    }

    public Optional<PlayerLimboRecord> get(UUID playerId) {
        return Optional.ofNullable(records.get(playerId));
    }

    public List<PlayerLimboRecord> all() {
        return new ArrayList<>(records.values());
    }

    public void clearForPlayer(UUID playerId, String reason) {
        PlayerLimboRecord removed = records.remove(playerId);
        if (removed != null) {
            logger.debug("Cleared limbo record for {} because {}", removed.getPlayerName(), reason);
        }
    }

    /**
     * Run a sweep: if a player is not actually on a limbo anymore, clear the record.
     */
    public void sweepNow() {
        Set<UUID> toRemove = records.entrySet().stream()
                .filter(e -> plugin.getProxyServer().getPlayer(e.getKey())
                        .map(p -> p.getCurrentServer().map(sc -> isLimbo(sc.getServer().getServerInfo().getName())).orElse(false))
                        .orElse(false) == false)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
        toRemove.forEach(id -> clearForPlayer(id, "no longer on limbo"));
    }

    private boolean isLimbo(String serverName) {
        try {
            var cfg = plugin.getConfigurationManager();
            if (cfg == null) return false;
            List<String> limbos = cfg.getBalancerLimbos();
            return limbos != null && limbos.contains(serverName);
        } catch (Throwable t) {
            return false;
        }
    }
}
