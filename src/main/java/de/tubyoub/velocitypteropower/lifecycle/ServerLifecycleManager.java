package de.tubyoub.velocitypteropower.lifecycle;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import de.tubyoub.velocitypteropower.VelocityPteroPower;
import de.tubyoub.velocitypteropower.api.PanelAPIClient;
import de.tubyoub.velocitypteropower.api.PowerSignal;
import de.tubyoub.velocitypteropower.manager.ConfigurationManager;
import de.tubyoub.velocitypteropower.manager.MessageKey;
import de.tubyoub.velocitypteropower.manager.MessagesManager;
import de.tubyoub.velocitypteropower.model.PteroServerInfo;
import de.tubyoub.velocitypteropower.util.RateLimitTracker;
import net.kyori.adventure.text.Component;
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
  private final MessagesManager messages;
  private final PanelAPIClient apiClient;
  private final RateLimitTracker rateLimitTracker;
  private final Map<String, PteroServerInfo> serverInfoMap;

  // State managed by this class
  private final Map<String, Integer> shutdownRetryCounts = new ConcurrentHashMap<>();

  /**
   * Constructor for ServerLifecycleManager.
   *
   * @param proxyServer Velocity ProxyServer instance.
   * @param plugin Plugin instance.
   */
  public ServerLifecycleManager(ProxyServer proxyServer, VelocityPteroPower plugin) {
    this.proxyServer = proxyServer;
    this.plugin = plugin;
    this.logger = plugin.getFilteredLogger();
    this.configurationManager = plugin.getConfigurationManager();
    this.messages = plugin.getMessagesManager();
    this.apiClient = plugin.getApiClient();
    this.rateLimitTracker = plugin.getRateLimitTracker();
    this.serverInfoMap = plugin.getServerInfoMap();
  }

  /**
   * Schedules a task to shut down a server after a specified timeout if it remains empty.
   * This method contains the logic executed by the scheduled task.
   *
   * @param serverName The name of the server.
   * @param serverId The panel ID of the server.
   * @param timeoutSeconds The delay in seconds before checking for emptiness and shutting down. Negative values disable shutdown.
   * @return The scheduled task, or null if disabled by configuration.
   */
  public ScheduledTask scheduleServerShutdown(String serverName, String serverId, int timeoutSeconds) {
    if (timeoutSeconds < 0) {
      logger.debug("Automatic shutdown disabled for server '{}' (timeout < 0).", serverName);
      return null;
    }

    // Informative log with MiniMessage template (rendered to plain in console, keeps consistent style)
    Component scheduledMsg =
        messages.mm(
            MessageKey.SERVER_SHUTDOWN_SCHEDULED,
            Map.of("server", serverName, "timeout", String.valueOf(timeoutSeconds)));
    logger.info(scheduledMsg);

    return proxyServer
        .getScheduler()
        .buildTask(
            plugin,
            () -> {
              // Check emptiness and rate limit before sending stop
              boolean empty = apiClient.isServerEmpty(serverName);
              if (rateLimitTracker.canMakeRequest() && empty) {
                if (apiClient.isServerOnline(serverName, serverId)) {
                  // Log power action using MiniMessage template
                  logger.info(
                      messages.mm(
                          MessageKey.POWER_ACTION_SENT,
                          Map.of("action", "stop", "server", serverName)));
                  apiClient.powerServer(serverId, PowerSignal.STOP);
                  shutdownRetryCounts.put(serverName, 0);
                  scheduleShutdownConfirmationCheck(serverName, serverId);
                } else {
                  logger.debug(
                      "Shutdown task for '{}' executed, but server was already offline.",
                      serverName);
                }
              } else {
                if (!empty) {
                  logger.info(messages.mm(MessageKey.SERVER_SHUTDOWN_CANCELLED_PLAYERS, Map.of("server", serverName)));
                } else {
                  logger.warn("Shutdown check for {} skipped due to rate limit.", serverName);
                }
              }
            })
        .delay(timeoutSeconds, TimeUnit.SECONDS)
        .schedule();
  }

  /**
   * Schedules a follow-up task to check if a server actually stopped after a stop signal was sent.
   * Retries stopping the server if it's still online and empty, up to a configured limit.
   *
   * @param serverName The name of the server.
   * @param serverId The panel ID of the server.
   */
  private void scheduleShutdownConfirmationCheck(String serverName, String serverId) {
    long retryDelay = configurationManager.getShutdownRetryDelay();

    proxyServer
        .getScheduler()
        .buildTask(
            plugin,
            () -> {
              if (!rateLimitTracker.canMakeRequest()) {
                logger.warn("Could not confirm shutdown status for {} due to rate limit.", serverName);
                return;
              }

              if (apiClient.isServerOnline(serverName, serverId)) {
                int currentRetries = shutdownRetryCounts.getOrDefault(serverName, 0);
                int maxRetries = configurationManager.getShutdownRetries();

                if (currentRetries < maxRetries) {
                  // Check emptiness again before retrying stop
                  if (apiClient.isServerEmpty(serverName)) {
                    int nextRetry = currentRetries + 1;
                    shutdownRetryCounts.put(serverName, nextRetry);

                    logger.warn(
                        messages.mm(
                            MessageKey.SERVER_STILL_ONLINE_RETRYING,
                            Map.of(
                                "server", serverName,
                                "retry", String.valueOf(nextRetry),
                                "maxRetries", String.valueOf(maxRetries))));

                    apiClient.powerServer(serverId, PowerSignal.STOP);
                    scheduleShutdownConfirmationCheck(serverName, serverId);
                  } else {
                    // Players came back — cancel shutdown process
                    logger.info(
                        messages.mm(MessageKey.SERVER_SHUTDOWN_CANCELLED_PLAYERS, Map.of("server", serverName)));
                    shutdownRetryCounts.remove(serverName);
                  }
                } else {
                  // Max retries reached
                  logger.error(
                      messages.mm(
                          MessageKey.SERVER_SHUTDOWN_FAILED,
                          Map.of("server", serverName, "retry", String.valueOf(maxRetries))));
                  shutdownRetryCounts.remove(serverName);
                }
              } else {
                // Server is offline, shutdown successful
                logger.info(messages.mm(MessageKey.SERVER_SHUTDOWN_SUCCESS, Map.of("server", serverName)));
                shutdownRetryCounts.remove(serverName);
              }
            })
        .delay(retryDelay, TimeUnit.SECONDS)
        .schedule();
  }

  /**
   * Clears the shutdown retry count for a server, typically called when a shutdown is cancelled.
   * @param serverName The name of the server.
   */
  public void clearRetryCount(String serverName) {
    shutdownRetryCounts.remove(serverName);
  }
}