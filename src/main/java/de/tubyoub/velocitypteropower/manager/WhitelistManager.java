/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.manager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import de.tubyoub.velocitypteropower.VelocityPteroPower;
import de.tubyoub.velocitypteropower.api.PanelAPIClient;
import de.tubyoub.velocitypteropower.api.PanelType;
import de.tubyoub.velocitypteropower.manager.ConfigurationManager;
import de.tubyoub.velocitypteropower.model.PteroServerInfo;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

public class WhitelistManager {
    private final VelocityPteroPower plugin;
    private final Logger logger;
    private final ConfigurationManager configManager;
    private PanelAPIClient apiClient;
    private final ProxyServer proxyServer;

    // Map to store whitelisted players for each server
    private final Map<String, Set<String>> serverWhitelists = new ConcurrentHashMap<>();

    // Scheduler for periodic whitelist updates
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> updateTask;

    public WhitelistManager(ProxyServer proxyServer, VelocityPteroPower plugin) {
        this.plugin = plugin;
        this.logger = plugin.getFilteredLogger();
        this.configManager = plugin.getConfigurationManager();
        this.proxyServer = proxyServer;
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    /**
     * Initialize the whitelist manager and start periodic updates
     */
    public void initialize() {
        logger.debug("Initializing WhitelistManager...");
        this.apiClient = plugin.getApiClient();
        updateAllWhitelists();

        // Schedule periodic updates
        if (updateTask != null) {
            updateTask.cancel(true);
            logger.debug("Previous update task canceled.");
        }
        int updateInterval = configManager.getWhitelistCheckInterval();
        if (updateInterval > 0) {
            updateTask = scheduler.scheduleAtFixedRate(
                this::updateAllWhitelists,
                updateInterval,
                updateInterval,
                TimeUnit.MINUTES
            );
            logger.info("Scheduled whitelist updates every {} minutes", updateInterval);
        } else {
            logger.debug("Whitelist updates are disabled (interval <= 0).");
        }
    }

    /**
     * Update whitelists for all configured servers
     */
    public void updateAllWhitelists() {
        logger.debug("Starting update of all whitelists...");
        Map<String, PteroServerInfo> serverInfoMap = plugin.getServerInfoMap();

        for (Map.Entry<String, PteroServerInfo> entry : serverInfoMap.entrySet()) {
            String serverName = entry.getKey();
            PteroServerInfo serverInfo = entry.getValue();

            // Only fetch whitelist if enabled for this server
            if (serverInfo.isWhitelistEnabled()) {
                logger.debug("Fetching whitelist for server: {}", serverName);
                fetchWhitelist(serverName, serverInfo.getServerId());
            } else {
                logger.debug("Whitelist is disabled for server: {}", serverName);
            }
        }
    }

    /**
     * Fetch whitelist for a specific server
     */
    private void fetchWhitelist(String serverName, String serverId) {
        if (configManager.getPanelType().equals(PanelType.mcServerSoft)){
            logger.warn("MC Server Soft does not support whitelist fetching");
        }
        //logger.debug("Fetching whitelist for server {} with ID {}", serverName, serverId);
        apiClient.fetchWhitelistFile(serverId)
            .thenAccept(whitelistJson -> {
                Set<String> whitelistedPlayers = parseWhitelistJson(whitelistJson);
                serverWhitelists.put(serverName, whitelistedPlayers);
                logger.debug("Updated whitelist for server {}: {} players", serverName, whitelistedPlayers.size());
            })
            .exceptionally(ex -> {
                logger.error("Failed to update whitelist for server {}: {}", serverName, ex.getMessage());
                if (logger.isDebugEnabled()) {
                    ex.printStackTrace();
                }
                return null;
            });
    }

    /**
     * Parse the whitelist JSON file and extract player names
     */
    private Set<String> parseWhitelistJson(String json) {
        logger.debug("Parsing whitelist JSON...");
        Set<String> players = new HashSet<>();

        try {
            JsonArray whitelist = JsonParser.parseString(json).getAsJsonArray();

            for (JsonElement element : whitelist) {
                JsonObject player = element.getAsJsonObject();
                if (player.has("name")) {
                    String playerName = player.get("name").getAsString().toLowerCase();
                    players.add(playerName);
                    logger.debug("Added player to whitelist: {}", playerName);
                } else if (player.has("uuid")) {
                    // Some whitelist formats might only have UUID
                    String uuid = player.get("uuid").getAsString();
                    logger.debug("Found player with UUID {} but no name in whitelist", uuid);
                }
            }
        } catch (Exception e) {
            logger.error("Error parsing whitelist JSON: {}", e.getMessage());
        }

        return players;
    }

    /**
     * Check if a player is whitelisted on a specific server
     */
    public boolean isPlayerWhitelisted(String serverName, String playerName) {
        logger.debug("Checking if player {} is whitelisted on server {}", playerName, serverName);
        if (configManager.getPanelType().equals(PanelType.mcServerSoft)){
            return true;
        }
        // If whitelist is not enabled for this server, consider everyone whitelisted
        PteroServerInfo serverInfo = plugin.getServerInfoMap().get(serverName);
        if (serverInfo == null || !serverInfo.isWhitelistEnabled()) {
            logger.debug("Whitelist is not enabled for server {}, allowing access for player {}", serverName, playerName);
            return true;
        }

        // If bypass is enabled and player has permission, allow access
        if (configManager.isWhitelistAllowBypass()) {
            UUID playerUuid = proxyServer.getPlayer(playerName).map(player -> player.getUniqueId()).orElse(null);
            if (playerUuid != null && proxyServer.getPlayer(playerUuid).isPresent() &&
                proxyServer.getPlayer(playerUuid).get().hasPermission("ptero.bypass")) {
                logger.debug("Player {} bypassing whitelist check for server {}", playerName, serverName);
                return true;
            }
        }

        // Check if server has a whitelist
        Set<String> whitelist = serverWhitelists.get(serverName);
        if (whitelist == null) {
            logger.debug("No whitelist found for server {}, allowing access", serverName);
            return true;
        }

        // Check if player is in the whitelist
        boolean isWhitelisted = whitelist.contains(playerName.toLowerCase());
        logger.debug("Player {} whitelist check for server {}: {}", playerName, serverName, isWhitelisted);
        return isWhitelisted;
    }

    /**
     * Shutdown the whitelist manager
     */
    public void shutdown() {
        logger.debug("Shutting down WhitelistManager...");
        if (updateTask != null) {
            updateTask.cancel(false);
            logger.debug("Update task canceled.");
        }

        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                    logger.debug("Scheduler forced to shut down.");
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
                logger.error("Scheduler shutdown interrupted: {}", e.getMessage());
            }
        }
    }
}
