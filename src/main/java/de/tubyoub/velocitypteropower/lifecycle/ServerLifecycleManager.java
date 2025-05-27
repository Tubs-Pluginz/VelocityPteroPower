/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.lifecycle;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import de.tubyoub.velocitypteropower.VelocityPteroPower;
import de.tubyoub.velocitypteropower.model.PteroServerInfo;
import de.tubyoub.velocitypteropower.api.PanelAPIClient;
import de.tubyoub.velocitypteropower.api.PowerSignal;
import de.tubyoub.velocitypteropower.manager.ConfigurationManager;
import de.tubyoub.velocitypteropower.manager.MessagesManager;
import de.tubyoub.velocitypteropower.util.RateLimitTracker;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Manages the lifecycle of servers related to automatic shutdowns,
 * including scheduling, checking, and retrying stop commands.
 */
public class ServerLifecycleManager {

    private final ProxyServer proxyServer;
    private final VelocityPteroPower plugin;
    private final ComponentLogger logger;
    private final ConfigurationManager configurationManager;
    private final MessagesManager messagesManager;
    private final PanelAPIClient apiClient;
    private final RateLimitTracker rateLimitTracker;
    private final Map<String, PteroServerInfo> serverInfoMap; // Reference to the map

    // State managed by this class
    private final Map<String, Integer> shutdownRetryCounts = new ConcurrentHashMap<>();

    /**
     * Constructor for ServerLifecycleManager.
     * @param proxyServer Velocity ProxyServer instance.
     * @param plugin Plugin instance.
     */
    public ServerLifecycleManager(ProxyServer proxyServer, VelocityPteroPower plugin) {
        this.proxyServer = proxyServer;
        this.plugin = plugin;
        this.logger = plugin.getFilteredLogger();
        this.configurationManager = plugin.getConfigurationManager();
        this.messagesManager = plugin.getMessagesManager();
        this.apiClient = plugin.getApiClient();
        this.rateLimitTracker = plugin.getRateLimitTracker();
        this.serverInfoMap = plugin.getServerInfoMap();
    }

     /**
     * Schedules a task to shut down a server after a specified timeout if it remains empty.
     * This method contains the logic executed by the scheduled task.
     *
     * @param serverName The name of the server.
     * @param serverId   The panel ID of the server.
     * @param timeoutSeconds The delay in seconds before checking for emptiness and shutting down. Negative values disable shutdown.
     * @return The scheduled task.
     */
    public ScheduledTask scheduleServerShutdown(String serverName, String serverId, int timeoutSeconds) {
        if (timeoutSeconds < 0) {
            logger.debug("Automatic shutdown disabled for server '{}' (timeout < 0).", serverName);
            return null;
        }

        logger.info(
            messagesManager.getMessage("shutdown-scheduled")
                .replace("%server%", serverName)
                .replace("%timeout%", String.valueOf(timeoutSeconds))
        );

        return proxyServer.getScheduler().buildTask(plugin, () -> {
            // Check emptiness and rate limit *before* sending stop signal
            if (rateLimitTracker.canMakeRequest() && apiClient.isServerEmpty(serverName)) {
                if (apiClient.isServerOnline(serverName, serverId)) {
                    logger.info(
                        messagesManager.getMessage("server-shutting-down")
                            .replace("%server%", serverName)
                    );
                    apiClient.powerServer(serverId, PowerSignal.STOP);
                    shutdownRetryCounts.put(serverName, 0);
                    scheduleShutdownConfirmationCheck(serverName, serverId);
                } else {
                     logger.debug("Shutdown task for '{}' executed, but server was already offline.", serverName);
                     // Ensure de.tubyoub.velocitypteropower.listener removes the task reference if needed
                }
            } else {
                if (!apiClient.isServerEmpty(serverName)) {
                     logger.info(
                        messagesManager.getMessage("shutdown-cancelled-players")
                            .replace("%server%", serverName)
                    );
                } else { // Must be rate limited
                    logger.warn("Shutdown check for {} skipped due to rate limit.", serverName);
                    // Consider rescheduling the shutdown check later if rate limited
                }
                // Ensure de.tubyoub.velocitypteropower.listener removes the task reference if needed
            }
        }).delay(timeoutSeconds, TimeUnit.SECONDS).schedule();
    }

    /**
     * Schedules a follow-up task to check if a server actually stopped after a stop signal was sent.
     * Retries stopping the server if it's still online and empty, up to a configured limit.
     *
     * @param serverName The name of the server.
     * @param serverId   The panel ID of the server.
     */
    private void scheduleShutdownConfirmationCheck(String serverName, String serverId) {
        long retryDelay = configurationManager.getShutdownRetryDelay();

        proxyServer.getScheduler().buildTask(plugin, () -> {
            if (!rateLimitTracker.canMakeRequest()) {
                logger.warn("Could not confirm shutdown status for {} due to rate limit.", serverName);
                return;
            }

            // Check if the server is *still* online
            if (apiClient.isServerOnline(serverName, serverId)) {
                int currentRetries = shutdownRetryCounts.getOrDefault(serverName, 0);
                int maxRetries = configurationManager.getShutdownRetries();

                if (currentRetries < maxRetries) {
                    // Check emptiness *again* before retrying stop command
                    if (apiClient.isServerEmpty(serverName)) {
                        int nextRetry = currentRetries + 1;
                        shutdownRetryCounts.put(serverName, nextRetry);
                        logger.warn(
                            messagesManager.getMessage("server-still-online-retrying")
                                .replace("%server%", serverName)
                                .replace("%retry%", String.valueOf(nextRetry))
                                .replace("%maxRetries%", String.valueOf(maxRetries))
                        );
                        apiClient.powerServer(serverId, PowerSignal.STOP);
                        scheduleShutdownConfirmationCheck(serverName, serverId);
                    } else {
                        // Server is online but no longer empty, cancel shutdown process
                        logger.info(
                            messagesManager.getMessage("shutdown-cancelled-players")
                                .replace("%server%", serverName)
                        );
                        shutdownRetryCounts.remove(serverName);
                    }
                } else {
                    // Max retries reached
                    logger.error(
                        messagesManager.getMessage("shutdown-failed")
                            .replace("%server%", serverName)
                            .replace("%retry%", String.valueOf(maxRetries))
                    );
                    shutdownRetryCounts.remove(serverName);
                }
            } else {
                // Server is offline, shutdown successful (or was already offline)
                logger.info(
                    messagesManager.getMessage("shutdown-success")
                        .replace("%server%", serverName)
                );
                shutdownRetryCounts.remove(serverName);
            }
        }).delay(retryDelay, TimeUnit.SECONDS).schedule();
    }

     /**
     * Clears the shutdown retry count for a server, typically called when a shutdown is cancelled.
     * @param serverName The name of the server.
     */
    public void clearRetryCount(String serverName) {
        shutdownRetryCounts.remove(serverName);
    }
}
