package de.tubyoub.velocitypteropower.api;

import de.tubyoub.vpp.api.ServerControl;
import de.tubyoub.velocitypteropower.VelocityPteroPower;
import de.tubyoub.velocitypteropower.http.PowerSignal;
import de.tubyoub.velocitypteropower.manager.ConfigurationManager;
import de.tubyoub.velocitypteropower.model.PteroServerInfo;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Routes addon start/stop/restart through the same rate-limit, maintenance,
 * and max-online checks used by core commands. Power signals go through
 * {@link EventingPanelAPIClient} so {@code ServerPowerSignalEvent} still fires.
 */
public class ServerControlImpl implements ServerControl {
    private final VelocityPteroPower plugin;
    private final Logger logger;

    public ServerControlImpl(VelocityPteroPower plugin) {
        this.plugin = plugin;
        this.logger = plugin.getFilteredLogger();
    }

    @Override
    public void startByName(String serverName) {
        PteroServerInfo info = resolve(serverName);
        if (info == null) return;

        if (plugin.getMaintenanceCoordinator() != null
                && plugin.getMaintenanceCoordinator().isBlocked(serverName)) {
            logger.warn("Addon start blocked for '{}': server is in maintenance", serverName);
            return;
        }
        if (!checkMaxOnline(serverName)) {
            logger.warn("Addon start blocked for '{}': maxOnlineServers cap reached", serverName);
            return;
        }
        if (!plugin.getRateLimitTracker().canMakeRequest()) {
            logger.warn("Addon start skipped for '{}': panel rate limited", serverName);
            return;
        }
        try {
            plugin.getApiClient().powerServer(info.getServerId(), PowerSignal.START);
            plugin.recordServerStartSignalSent();
            plugin.getStartingServers().add(serverName);
            plugin.getStartingServersSince().put(serverName, System.currentTimeMillis());
            if (plugin.getServerGroupService() != null) {
                plugin.getServerGroupService().startLinkedPeers(serverName);
            }
        } catch (Throwable t) {
            logger.error("Addon start failed for '{}': {}", serverName, t.toString());
        }
    }

    @Override
    public void stopByName(String serverName) {
        PteroServerInfo info = resolve(serverName);
        if (info == null) return;
        if (!plugin.getRateLimitTracker().canMakeRequest()) {
            logger.warn("Addon stop skipped for '{}': panel rate limited", serverName);
            return;
        }
        try {
            plugin.getApiClient().powerServer(info.getServerId(), PowerSignal.STOP);
            plugin.getStartingServers().remove(serverName);
            plugin.getStartingServersSince().remove(serverName);
        } catch (Throwable t) {
            logger.error("Addon stop failed for '{}': {}", serverName, t.toString());
        }
    }

    @Override
    public void restartByName(String serverName) {
        PteroServerInfo info = resolve(serverName);
        if (info == null) return;
        if (plugin.getMaintenanceCoordinator() != null
                && plugin.getMaintenanceCoordinator().isBlocked(serverName)) {
            logger.warn("Addon restart blocked for '{}': server is in maintenance", serverName);
            return;
        }
        if (!plugin.getRateLimitTracker().canMakeRequest()) {
            logger.warn("Addon restart skipped for '{}': panel rate limited", serverName);
            return;
        }
        try {
            plugin.getApiClient().powerServer(info.getServerId(), PowerSignal.RESTART);
            plugin.recordServerStartSignalSent();
            plugin.getStartingServers().add(serverName);
            plugin.getStartingServersSince().put(serverName, System.currentTimeMillis());
        } catch (Throwable t) {
            logger.error("Addon restart failed for '{}': {}", serverName, t.toString());
        }
    }

    @Override
    public boolean isOnline(String serverName) {
        PteroServerInfo info = resolve(serverName);
        if (info == null) return false;
        try {
            return plugin.getApiClient().isServerOnline(serverName, info.getServerId());
        } catch (Throwable t) {
            logger.debug("Online check failed for '{}': {}", serverName, t.toString());
            return false;
        }
    }

    private PteroServerInfo resolve(String serverName) {
        if (serverName == null) return null;
        Map<String, PteroServerInfo> map = plugin.getServerInfoMap();
        if (map == null) return null;
        PteroServerInfo info = map.get(serverName);
        if (info == null) {
            logger.debug("Addon power request ignored: unknown server '{}'", serverName);
        }
        return info;
    }

    private boolean checkMaxOnline(String serverName) {
        ConfigurationManager configurationManager = plugin.getConfigurationManager();
        int maxOnline = configurationManager.getMaxOnlineServers();
        if (maxOnline <= 0) return true;

        Set<String> exempt = new HashSet<>(configurationManager.getMaxOnlineExemptList());
        if (!configurationManager.isCountLobbiesInMaxOnline()) {
            List<String> lobbies = configurationManager.getBalancerLobbies();
            int use = Math.max(0, configurationManager.getBalancerLobbiesToUse());
            if (lobbies != null && !lobbies.isEmpty()) {
                if (use > 0 && use < lobbies.size()) {
                    exempt.addAll(lobbies.subList(0, use));
                } else {
                    exempt.addAll(lobbies);
                }
            }
        }
        if (!configurationManager.isCountLimbosInMaxOnline()) {
            List<String> limbos = configurationManager.getBalancerLimbos();
            if (limbos != null) exempt.addAll(limbos);
        }
        if (exempt.contains(serverName)) return true;

        int onlineCount = plugin.getServerLifecycleManager().countOnlineServersExcluding(exempt);
        if (onlineCount < 0) return true;
        int effectiveCap = Math.max(0, maxOnline - configurationManager.getMaxOnlineReservations());
        return onlineCount < effectiveCap;
    }
}
