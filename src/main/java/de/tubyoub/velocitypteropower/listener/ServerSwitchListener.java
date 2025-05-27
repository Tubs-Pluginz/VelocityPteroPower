/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import de.tubyoub.velocitypteropower.model.PteroServerInfo;
import de.tubyoub.velocitypteropower.VelocityPteroPower;
import de.tubyoub.velocitypteropower.api.PanelAPIClient;
import de.tubyoub.velocitypteropower.lifecycle.ServerLifecycleManager; // Import manager
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Listens for player server switches and disconnects to trigger checks
 * for automatic shutdown of empty servers via the ServerLifecycleManager.
 * Also manages the cancellation of pending shutdowns when players join.
 */
public class ServerSwitchListener {

    private final VelocityPteroPower plugin;
    private final ComponentLogger logger;
    private final ProxyServer proxyServer;
    private final PanelAPIClient apiClient;
    private final ServerLifecycleManager serverLifecycleManager;

    private final Map<String, ScheduledTask> scheduledShutdowns = new ConcurrentHashMap<>();

    /**
     * Constructor for the ServerSwitchListener.
     *
     * @param plugin The main VelocityPteroPower plugin instance.
     * @param serverLifecycleManager The manager responsible for shutdown logic.
     */
    public ServerSwitchListener(VelocityPteroPower plugin, ServerLifecycleManager serverLifecycleManager) {
        this.plugin = plugin;
        this.logger = plugin.getFilteredLogger();
        this.proxyServer = plugin.getProxyServer();
        this.apiClient = plugin.getApiClient(); // Get from plugin
        this.serverLifecycleManager = serverLifecycleManager;
    }

    /**
     * Handles player disconnect events.
     * Checks if the server the player disconnected from might be empty and triggers a check.
     */
    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();

        player.getCurrentServer().ifPresent(serverConnection -> {
            RegisteredServer server = serverConnection.getServer();
            String serverName = server.getServerInfo().getName();
            PteroServerInfo serverInfo = plugin.getServerInfoMap().get(serverName);

            if (serverInfo != null) {
                proxyServer.getScheduler().buildTask(plugin, () -> {
                    checkAndScheduleShutdownIfNeeded(serverName, serverInfo);
                }).delay(500, TimeUnit.MILLISECONDS).schedule();
            }
        });
    }

    /**
     * Handles player server switch events.
     * Cancels pending shutdown for the new server and triggers a check for the previous server.
     */
    @Subscribe
    public void onServerSwitch(ServerConnectedEvent event) {
        RegisteredServer newServer = event.getServer();
        String newServerName = newServer.getServerInfo().getName();

        // 1. Cancel shutdown for the server the player *joined*
        cancelShutdownTask(newServerName, "player joined");

        // 2. Check the server the player *left* (if any)
        event.getPreviousServer().ifPresent(previousServer -> {
            String previousServerName = previousServer.getServerInfo().getName();
            PteroServerInfo previousServerInfo = plugin.getServerInfoMap().get(previousServerName);

            if (previousServerInfo != null) {
                 proxyServer.getScheduler().buildTask(plugin, () -> {
                    checkAndScheduleShutdownIfNeeded(previousServerName, previousServerInfo);
                }).delay(500, TimeUnit.MILLISECONDS).schedule();
            }
        });
    }

    /**
     * Checks if a server is empty and triggers the ServerLifecycleManager
     * to schedule a shutdown if needed and not already scheduled.
     */
    private void checkAndScheduleShutdownIfNeeded(String serverName, PteroServerInfo serverInfo) {
        // Prevent duplicate scheduling attempts by checking map first
        if (scheduledShutdowns.containsKey(serverName)) {
            logger.debug("Shutdown check for '{}': Task already pending.", serverName);
            return;
        }

        // Check if the server is actually empty now
        if (apiClient.isServerEmpty(serverName)) {
            logger.debug("Server '{}' is empty. Requesting shutdown schedule from LifecycleManager.", serverName);
            ScheduledTask shutdownTask = serverLifecycleManager.scheduleServerShutdown(
                serverName,
                serverInfo.getServerId(),
                serverInfo.getTimeout()
            );

            // Store the task if scheduling was successful (timeout >= 0)
            if (shutdownTask != null) {
                scheduledShutdowns.put(serverName, shutdownTask);
            }
        } else {
            logger.debug("Server '{}' is not empty. No shutdown needed.", serverName);
        }
    }

    /**
     * Cancels a pending shutdown task for a specific server.
     */
    private void cancelShutdownTask(String serverName, String reason) {
        ScheduledTask existingTask = scheduledShutdowns.remove(serverName);
        if (existingTask != null) {
            existingTask.cancel();
            // Also inform the lifecycle manager to clear any retry state
            serverLifecycleManager.clearRetryCount(serverName);
            logger.info(
                plugin.getMessagesManager().getMessage("shutdown-cancelled")
                    .replace("%server%", serverName) + " (Reason: " + reason + ")"
            );
        } else {
             logger.debug("No pending shutdown task found for server '{}' to cancel.", serverName);
        }
    }
}
