/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.service;

import de.tubyoub.velocitypteropower.VelocityPteroPower;
import de.tubyoub.velocitypteropower.http.PanelAPIClient;
import de.tubyoub.velocitypteropower.http.PowerSignal;
import de.tubyoub.velocitypteropower.model.PteroServerInfo;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.util.*;

/**
 * Linked servers / groups (#33): starting one member also starts peers;
 * idle-shutdown is skipped while any peer still has players.
 */
public class ServerGroupService {

    private final VelocityPteroPower plugin;
    private final ComponentLogger logger;

    public ServerGroupService(VelocityPteroPower plugin) {
        this.plugin = plugin;
        this.logger = plugin.getFilteredLogger();
    }

    /** All managed server names that share a group or explicit link with {@code serverName}. */
    public Set<String> peersOf(String serverName) {
        Set<String> peers = new LinkedHashSet<>();
        if (serverName == null) return peers;
        Map<String, PteroServerInfo> map = plugin.getServerInfoMap();
        if (map == null) return peers;

        PteroServerInfo self = map.get(serverName);
        if (self == null) return peers;

        String group = self.getGroup();
        if (group != null && !group.isBlank()) {
            for (Map.Entry<String, PteroServerInfo> e : map.entrySet()) {
                if (e.getKey().equalsIgnoreCase(serverName)) continue;
                String otherGroup = e.getValue().getGroup();
                if (otherGroup != null && otherGroup.equalsIgnoreCase(group)) {
                    peers.add(e.getKey());
                }
            }
            // Explicit serverGroups: section may list additional members
            List<String> fromConfig = plugin.getConfigurationManager().getServerGroupMembers(group);
            for (String name : fromConfig) {
                if (name != null && !name.equalsIgnoreCase(serverName) && map.containsKey(name)) {
                    peers.add(name);
                }
            }
        }

        List<String> linked = self.getLinkedServers();
        if (linked != null) {
            for (String name : linked) {
                if (name != null && !name.isBlank() && map.containsKey(name)
                        && !name.equalsIgnoreCase(serverName)) {
                    peers.add(name);
                }
            }
        }
        return peers;
    }

    /** True if this server or any peer currently has connected players. */
    public boolean groupHasPlayers(String serverName) {
        Map<String, PteroServerInfo> map = plugin.getServerInfoMap();
        if (map == null) return false;
        PanelAPIClient api = plugin.getApiClient();
        if (api == null) return false;

        Set<String> check = new LinkedHashSet<>();
        check.add(serverName);
        check.addAll(peersOf(serverName));
        for (String name : check) {
            try {
                if (!api.isServerEmpty(name)) {
                    return true;
                }
            } catch (Exception ignored) {}
        }
        return false;
    }

    /**
     * Start offline peers when {@code serverName} is being started.
     * Does not add peers to the player's wait queue.
     */
    public void startLinkedPeers(String serverName) {
        PanelAPIClient api = plugin.getApiClient();
        Map<String, PteroServerInfo> map = plugin.getServerInfoMap();
        if (api == null || map == null) return;

        for (String peer : peersOf(serverName)) {
            PteroServerInfo info = map.get(peer);
            if (info == null) continue;
            if (plugin.getMaintenanceCoordinator() != null
                    && plugin.getMaintenanceCoordinator().isBlocked(peer)) {
                logger.debug("Skipping linked start for '{}' (maintenance).", peer);
                continue;
            }
            try {
                if (api.isServerOnline(peer, info.getServerId())) {
                    continue;
                }
            } catch (Exception ignored) {}
            if (!plugin.getRateLimitTracker().canMakeRequest()) {
                logger.warn("Rate limited; cannot start linked peer '{}' for '{}'.", peer, serverName);
                continue;
            }
            logger.info("Starting linked peer '{}' for group/link of '{}'.", peer, serverName);
            api.powerServer(info.getServerId(), PowerSignal.START);
            plugin.recordServerStartSignalSent();
        }
    }
}
