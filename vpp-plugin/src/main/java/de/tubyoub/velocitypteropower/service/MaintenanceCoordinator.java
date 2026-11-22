/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.service;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import de.tubyoub.velocitypteropower.VelocityPteroPower;
import de.tubyoub.velocitypteropower.service.LimboReason;
import de.tubyoub.velocitypteropower.service.LimboTrackerService;
import de.tubyoub.velocitypteropower.service.PlayerLimboRecord;
import de.tubyoub.vpp.api.maintenance.MaintenanceClearListener;
import de.tubyoub.vpp.api.maintenance.MaintenanceDecision;
import de.tubyoub.vpp.api.maintenance.MaintenanceProvider;
import de.tubyoub.vpp.api.maintenance.MaintenanceQuery;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Aggregates VPP built-in maintenance and any external {@link MaintenanceProvider}s.
 * A cold-start is blocked when <strong>any</strong> active provider blocks.
 */
public class MaintenanceCoordinator {

    private final VelocityPteroPower plugin;
    private final ComponentLogger logger;
    private final CopyOnWriteArrayList<MaintenanceProvider> providers = new CopyOnWriteArrayList<>();
    private final MaintenanceClearListener clearFanOut = this::onAnyCleared;
    private volatile boolean externalProvidersEnabled = true;

    public MaintenanceCoordinator(VelocityPteroPower plugin) {
        this.plugin = plugin;
        this.logger = plugin.getFilteredLogger();
    }

    public void setExternalProvidersEnabled(boolean enabled) {
        this.externalProvidersEnabled = enabled;
    }

    public boolean isExternalProvidersEnabled() {
        return externalProvidersEnabled;
    }

    public void register(MaintenanceProvider provider) {
        if (provider == null) return;
        unregister(provider);
        providers.add(provider);
        providers.sort(Comparator.comparingInt(MaintenanceProvider::priority).reversed());
        try {
            provider.addClearListener(clearFanOut);
        } catch (Throwable t) {
            logger.debug("Failed to attach clear listener to {}: {}", provider.id(), t.toString());
        }
        logger.info("Registered maintenance provider '{}' (priority={}, active={}).",
                provider.id(), provider.priority(), provider.isActive());
    }

    public void unregister(MaintenanceProvider provider) {
        if (provider == null) return;
        try {
            provider.removeClearListener(clearFanOut);
        } catch (Throwable ignored) {}
        providers.removeIf(p -> p == provider || p.id().equalsIgnoreCase(provider.id()));
    }

    public List<MaintenanceProvider> providers() {
        return List.copyOf(providers);
    }

    /** Player-scoped gate used on connect / start attempts. */
    public MaintenanceDecision evaluate(Player player, String serverName) {
        MaintenanceQuery query = new MaintenanceQuery(
                player != null ? player.getUniqueId() : null,
                player != null ? player.getUsername() : "",
                serverName,
                player != null ? player::hasPermission : perm -> false);
        return evaluate(query);
    }

    /** Non-player checks (idle sweep, always-online, linked start, addon control). */
    public MaintenanceDecision evaluate(String serverName) {
        return evaluate(new MaintenanceQuery(null, "", serverName, perm -> false));
    }

    public MaintenanceDecision evaluate(MaintenanceQuery query) {
        if (query == null || query.serverName() == null) {
            return MaintenanceDecision.allow();
        }
        MaintenanceDecision firstBlock = null;
        for (MaintenanceProvider provider : providers) {
            try {
                if (!provider.isActive()) continue;
                if (isExternal(provider) && !externalProvidersEnabled) continue;
                if (!provider.blocks(query)) continue;
                String detail = safeDetail(provider, query);
                MaintenanceDecision decision = MaintenanceDecision.block(provider.id(), detail);
                if (firstBlock == null) {
                    firstBlock = decision;
                }
            } catch (Throwable t) {
                logger.debug("Maintenance provider '{}' failed: {}", provider.id(), t.toString());
            }
        }
        return firstBlock != null ? firstBlock : MaintenanceDecision.allow();
    }

    public boolean isBlocked(Player player, String serverName) {
        return evaluate(player, serverName).isBlocked();
    }

    public boolean isBlocked(String serverName) {
        return evaluate(serverName).isBlocked();
    }

    public String detailFor(Player player, String serverName) {
        return evaluate(player, serverName).detail();
    }

    public String detailFor(String serverName) {
        return evaluate(serverName).detail();
    }

    private boolean isExternal(MaintenanceProvider provider) {
        return provider != null && !"vpp-builtin".equalsIgnoreCase(provider.id());
    }

    private static String safeDetail(MaintenanceProvider provider, MaintenanceQuery query) {
        try {
            String d = provider.detail(query);
            return d == null ? "" : d;
        } catch (Throwable t) {
            return "";
        }
    }

    private void onAnyCleared(String serverName) {
        ProxyServer proxy = plugin.getProxyServer();
        LimboTrackerService lts = plugin.getLimboTrackerService();
        if (proxy == null || lts == null) return;

        proxy.getScheduler()
                .buildTask(
                        plugin,
                        () -> {
                            List<PlayerLimboRecord> records = new ArrayList<>(lts.all());
                            for (PlayerLimboRecord record : records) {
                                if (record.getReason() != LimboReason.MAINTENANCE_WAIT) continue;
                                String target = record.getContext();
                                if (target == null || target.isBlank()) continue;
                                if (serverName != null && !serverName.equalsIgnoreCase(target)) continue;

                                Optional<Player> maybe = proxy.getPlayer(record.getPlayerId());
                                if (maybe.isEmpty()) {
                                    lts.clearForPlayer(record.getPlayerId(), "offline during maintenance retry");
                                    continue;
                                }
                                Player player = maybe.get();
                                if (isBlocked(player, target)) continue;

                                Optional<RegisteredServer> rs = proxy.getServer(target);
                                if (rs.isEmpty()) {
                                    lts.clearForPlayer(player.getUniqueId(), "target missing");
                                    continue;
                                }
                                logger.info(
                                        "Maintenance cleared — reconnecting {} to '{}'.",
                                        player.getUsername(),
                                        target);
                                lts.clearForPlayer(player.getUniqueId(), "maintenance cleared");
                                player.createConnectionRequest(rs.get()).fireAndForget();
                            }
                        })
                .delay(1, TimeUnit.SECONDS)
                .schedule();
    }
}
