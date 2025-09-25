package de.tubyoub.velocitypteropower.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import de.tubyoub.velocitypteropower.VelocityPteroPower;
import de.tubyoub.velocitypteropower.lifecycle.ServerLifecycleManager;
import de.tubyoub.velocitypteropower.manager.MessageKey;
import de.tubyoub.velocitypteropower.manager.MessagesManager;
import de.tubyoub.velocitypteropower.model.PteroServerInfo;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class ServerSwitchListener {

  private final VelocityPteroPower plugin;
  private final ComponentLogger logger;
  private final MessagesManager messages;
  private final ServerLifecycleManager serverLifecycleManager;

  private final Map<String, ScheduledTask> scheduledShutdowns = new ConcurrentHashMap<>();

  public ServerSwitchListener(VelocityPteroPower plugin, ServerLifecycleManager serverLifecycleManager) {
    this.plugin = plugin;
    this.logger = plugin.getFilteredLogger();
    this.messages = plugin.getMessagesManager();
    this.serverLifecycleManager = serverLifecycleManager;
  }

  @Subscribe
  public void onDisconnect(DisconnectEvent event) {
    Player player = event.getPlayer();
    player.getCurrentServer()
        .ifPresent(
            serverConnection -> {
              String serverName = serverConnection.getServer().getServerInfo().getName();
              PteroServerInfo serverInfo = plugin.getServerInfoMap().get(serverName);

              if (serverInfo != null) {
                plugin.getProxyServer()
                    .getScheduler()
                    .buildTask(plugin, () -> checkAndScheduleShutdownIfNeeded(serverName, serverInfo))
                    .delay(500, TimeUnit.MILLISECONDS)
                    .schedule();
              }
            });
  }

  @Subscribe
  public void onServerSwitch(ServerConnectedEvent event) {
    RegisteredServer newServer = event.getServer();
    String newServerName = newServer.getServerInfo().getName();

    cancelShutdownTask(newServerName, "player joined");

    event
        .getPreviousServer()
        .ifPresent(
            previousServer -> {
              String prevName = previousServer.getServerInfo().getName();
              PteroServerInfo prevInfo = plugin.getServerInfoMap().get(prevName);

              if (prevInfo != null) {
                plugin.getProxyServer()
                    .getScheduler()
                    .buildTask(plugin, () -> checkAndScheduleShutdownIfNeeded(prevName, prevInfo))
                    .delay(500, TimeUnit.MILLISECONDS)
                    .schedule();
              }
            });
  }

  private void checkAndScheduleShutdownIfNeeded(String serverName, PteroServerInfo serverInfo) {
    if (scheduledShutdowns.containsKey(serverName)) {
      logger.debug("Shutdown check for '{}': Task already pending.", serverName);
      return;
    }

    if (plugin.getApiClient().isServerEmpty(serverName)) {
      logger.debug("Server '{}' is empty. Requesting shutdown schedule from LifecycleManager.", serverName);
      ScheduledTask shutdownTask =
          serverLifecycleManager.scheduleServerShutdown(serverName, serverInfo.getServerId(), serverInfo.getTimeout());

      if (shutdownTask != null) {
        scheduledShutdowns.put(serverName, shutdownTask);
      }
    } else {
      logger.debug("Server '{}' is not empty. No shutdown needed.", serverName);
    }
  }

  private void cancelShutdownTask(String serverName, String reason) {
    ScheduledTask existing = scheduledShutdowns.remove(serverName);
    if (existing != null) {
      existing.cancel();
      serverLifecycleManager.clearRetryCount(serverName);
      logger.info(
          messages.raw(MessageKey.SERVER_SHUTDOWN_CANCELLED)
              .replace("<server>", serverName)
              .replace("<reason>", reason));
    } else {
      logger.debug("No pending shutdown task found for server '{}' to cancel.", serverName);
    }
  }
}