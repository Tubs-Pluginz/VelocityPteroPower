package de.tubyoub.velocitypteropower.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import de.tubyoub.velocitypteropower.VelocityPteroPower;
import de.tubyoub.velocitypteropower.lifecycle.ServerLifecycleManager;
import de.tubyoub.velocitypteropower.manager.MessageKey;
import de.tubyoub.velocitypteropower.manager.MessagesManager;
import de.tubyoub.velocitypteropower.model.PteroServerInfo;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.util.concurrent.TimeUnit;

public class ServerSwitchListener {

  private final VelocityPteroPower plugin;
  private final ComponentLogger logger;
  private final MessagesManager messages;
  private final ServerLifecycleManager serverLifecycleManager;

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
                    .buildTask(plugin, () -> serverLifecycleManager.checkAndScheduleShutdownIfNeeded(serverName, serverInfo))
                    .delay(500, TimeUnit.MILLISECONDS)
                    .schedule();
              }
            });
  }

  @Subscribe
  public void onServerSwitch(ServerConnectedEvent event) {
    RegisteredServer newServer = event.getServer();
    String newServerName = newServer.getServerInfo().getName();

    serverLifecycleManager.cancelScheduledShutdown(newServerName, "player " + event.getPlayer().getUsername() + " joined");

    event
        .getPreviousServer()
        .ifPresent(
            previousServer -> {
              String prevName = previousServer.getServerInfo().getName();
              PteroServerInfo prevInfo = plugin.getServerInfoMap().get(prevName);

              if (prevInfo != null) {
                plugin.getProxyServer()
                    .getScheduler()
                    .buildTask(plugin, () -> serverLifecycleManager.checkAndScheduleShutdownIfNeeded(prevName, prevInfo))
                    .delay(500, TimeUnit.MILLISECONDS)
                    .schedule();
              }
            });
  }
}