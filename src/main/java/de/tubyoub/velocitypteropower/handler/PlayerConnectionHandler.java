package de.tubyoub.velocitypteropower.handler;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class PlayerConnectionHandler {

  private final ProxyServer proxyServer;
  private final VelocityPteroPower plugin;
  private final ComponentLogger logger;
  private final ConfigurationManager configurationManager;
  private final MessagesManager messagesManager;
  private final PanelAPIClient apiClient;
  private final RateLimitTracker rateLimitTracker;

  private final Map<String, PteroServerInfo> serverInfoMap;
  private final Set<String> startingServers;
  private final Map<UUID, Long> playerCooldowns;

  public PlayerConnectionHandler(ProxyServer proxyServer, VelocityPteroPower plugin) {
    this.proxyServer = proxyServer;
    this.plugin = plugin;
    this.logger = plugin.getFilteredLogger();
    this.configurationManager = plugin.getConfigurationManager();
    this.messagesManager = plugin.getMessagesManager();
    this.apiClient = plugin.getApiClient();
    this.rateLimitTracker = plugin.getRateLimitTracker();
    this.serverInfoMap = plugin.getServerInfoMap();
    this.startingServers = plugin.getStartingServers();
    this.playerCooldowns = plugin.getPlayerCooldowns();
  }

  @Subscribe(priority = 10)
  public void onServerPreConnect(ServerPreConnectEvent event) {
    Player player = event.getPlayer();
    RegisteredServer targetServer = event.getOriginalServer();
    String serverName = targetServer.getServerInfo().getName();

    PteroServerInfo serverInfo = serverInfoMap.get(serverName);
    if (serverInfo == null) {
      handleUnmanagedServer(player, serverName);
      return;
    }

    if (!plugin.getWhitelistManager().isPlayerWhitelisted(serverName, player.getUsername())) {
      if (event.getPreviousServer() == null) {
        player.disconnect(
            messagesManager.prefixed(MessageKey.CONNECT_NOT_WHITELISTED));
        return;
      }
      // Player is trying to switch to a whitelisted server they are not allowed on.
      // Inform and deny without starting the target server or sending them to limbo.
      player.sendMessage(messagesManager.prefixed(MessageKey.CONNECT_NOT_WHITELISTED));
      event.setResult(ServerPreConnectEvent.ServerResult.denied());
      return;
    }

    String serverId = serverInfo.getServerId();

    if (isPlayerOnCooldown(player, serverName) && event.getPreviousServer() != null) {
      event.setResult(ServerPreConnectEvent.ServerResult.denied());
      return;
    }

    boolean isOnline = rateLimitTracker.canMakeRequest() && apiClient.isServerOnline(serverName, serverId);

    if (isOnline) {
      startingServers.remove(serverName);
      logger.debug("Server {} is online. Allowing connection for {}.", serverName, player.getUsername());
      return;
    }

    handleOfflineServerConnection(event, player, serverName, serverId, serverInfo);
  }

  private void handleUnmanagedServer(Player player, String serverName) {
    logger.debug("Server '{}' is not managed by VelocityPteroPower.", serverName);
    if (configurationManager.isServerNotFoundMessage()) {
      player.sendMessage(
          messagesManager.prefixed(
              MessageKey.CONNECT_UNMANAGED_SERVER, "server", serverName));
    }
  }

  private boolean isPlayerOnCooldown(Player player, String serverName) {
    long currentTime = System.currentTimeMillis();
    long lastStartTime = playerCooldowns.getOrDefault(player.getUniqueId(), 0L);
    int cooldownMillis = configurationManager.getPlayerCommandCooldown() * 1000;

    if (currentTime - lastStartTime < cooldownMillis) {
      long remainingSeconds =
          TimeUnit.MILLISECONDS.toSeconds(cooldownMillis - (currentTime - lastStartTime)) + 1;
      player.sendMessage(
          messagesManager.prefixed(
              MessageKey.COMMAND_COOLDOWN_ACTIVE, "timeout", String.valueOf(remainingSeconds)));
      logger.debug(
          "Player {} is on cooldown for starting server {}.",
          player.getUsername(),
          serverName);
      return true;
    }
    return false;
  }

  private void handleOfflineServerConnection(
      ServerPreConnectEvent event,
      Player player,
      String serverName,
      String serverId,
      PteroServerInfo serverInfo) {
    if (startingServers.contains(serverName) && event.getPreviousServer() != null) {
      player.sendMessage(
          messagesManager.prefixed(
              MessageKey.CONNECT_SERVER_STARTING, "server", serverName));
      event.setResult(ServerPreConnectEvent.ServerResult.denied());
      logger.debug(
          "Server {} is already starting. Denying connection for {}.",
          serverName,
          player.getUsername());
      return;
    }

    if (!rateLimitTracker.canMakeRequest()) {
      logger.warn(
          "Cannot start server {} ({}) for {} due to rate limiting.",
          serverName,
          serverId,
          player.getUsername());
      player.sendMessage(messagesManager.prefixed(MessageKey.CONNECT_ERROR_RATE_LIMITED));
      event.setResult(ServerPreConnectEvent.ServerResult.denied());
      return;
    }

    // Enforce maximum concurrent online servers (excluding exempt), unless bypassed
    int maxOnline = configurationManager.getMaxOnlineServers();
    boolean hasBypass = configurationManager.isMaxOnlineAllowBypass() && player.hasPermission("ptero.maxcap.bypass");
    if (maxOnline > 0 && !hasBypass) {
      java.util.Set<String> exempt = new java.util.HashSet<>(configurationManager.getMaxOnlineExemptList());
      if (!exempt.contains(serverName)) {
        int onlineCount = plugin.getServerLifecycleManager().countOnlineServersExcluding(exempt);
        if (onlineCount >= maxOnline && onlineCount != -1) {
          player.sendMessage(
              messagesManager.prefixed(
                  MessageKey.CONNECT_MAX_ONLINE_REACHED,
                  "max", String.valueOf(maxOnline)));
          event.setResult(ServerPreConnectEvent.ServerResult.denied());
          return;
        }
      }
    }

    if (!startingServers.contains(serverName)) {
      logger.info(
          "Attempting to start server '{}' ({}) for player {}",
          serverName,
          serverId,
          player.getUsername());
      startingServers.add(serverName);
      playerCooldowns.put(player.getUniqueId(), System.currentTimeMillis());
      apiClient.powerServer(serverId, PowerSignal.START);
      scheduleInitialIdleCheck(serverName, serverId);
    }

    Optional<RegisteredServer> limboServerOpt = findValidLimboServer();

    if (limboServerOpt.isPresent()) {
      RegisteredServer limboServer = limboServerOpt.get();
      logger.info(
          "Redirecting player {} to limbo server '{}' while server '{}' starts.",
          player.getUsername(),
          limboServer.getServerInfo().getName(),
          serverName);
      player.sendMessage(
          messagesManager.prefixed(
              MessageKey.CONNECT_REDIRECTING_TO_LIMBO,
              "server",
              serverName,
              "limbo",
              limboServer.getServerInfo().getName()));
      event.setResult(ServerPreConnectEvent.ServerResult.allowed(limboServer));
      scheduleDelayedConnect(player, serverName, serverInfo);
    } else {
      String baseMsgKey = MessageKey.CONNECT_STARTING_SERVER_DISCONNECT.getPath();
      if (event.getPreviousServer() == null) {
        logger.info(
            "Disconnecting player {} while server '{}' starts (Limbo not available/usable).",
            player.getUsername(),
            serverName);
        player.disconnect(messagesManager.prefixed(baseMsgKey, "server", serverName));
      } else {
        player.sendMessage(messagesManager.prefixed(baseMsgKey, "server", serverName));
        scheduleDelayedConnect(player, serverName, serverInfo);
      }
      event.setResult(ServerPreConnectEvent.ServerResult.denied());
    }
  }

  private Optional<RegisteredServer> findValidLimboServer() {
    String limboServerName = configurationManager.getLimboServerName();
    if (limboServerName == null) {
      logger.debug("Limbo server not configured.");
      return Optional.empty();
    }

    Optional<RegisteredServer> limboOpt = proxyServer.getServer(limboServerName);
    if (limboOpt.isEmpty()) {
      logger.error("Configured limbo server '{}' is not registered with Velocity.", limboServerName);
      return Optional.empty();
    }

    PteroServerInfo limboInfo = serverInfoMap.get(limboServerName);
    if (limboInfo != null) {
      if (!rateLimitTracker.canMakeRequest()) {
        logger.warn("Rate limited. Cannot check or start VPP-managed limbo server '{}'.", limboServerName);
        return Optional.empty();
      }
      if (!apiClient.isServerOnline(limboServerName, limboInfo.getServerId())) {
        logger.warn(
            "VPP-managed limbo server '{}' is offline. Attempting to start it, but cannot use it for redirection now.",
            limboServerName);
        apiClient.powerServer(limboInfo.getServerId(), PowerSignal.START);
        return Optional.empty();
      }
      logger.debug("VPP-managed limbo server '{}' is online.", limboServerName);
    } else {
      logger.debug("Limbo server '{}' is registered but not managed by VPP. Assuming usable.", limboServerName);
    }

    return limboOpt;
  }

  private void scheduleDelayedConnect(
      Player player, String targetServerName, PteroServerInfo targetServerInfo) {
    long initialDelay = configurationManager.getStartupInitialCheckDelay();
    long checkInterval = Math.max(5, targetServerInfo.getJoinDelay());

    proxyServer
        .getScheduler()
        .buildTask(
            plugin,
            new Runnable() {
              private int attempts = 0;
              private final int maxAttempts = 12;

              @Override
              public void run() {
                if (!player.isActive() || player.getCurrentServer().isEmpty()) {
                  logger.info(
                      "Player {} disconnected or left limbo while waiting for {}. Cancelling connect task.",
                      player.getUsername(),
                      targetServerName);
                  startingServers.remove(targetServerName);
                  return;
                }

                if (rateLimitTracker.canMakeRequest()
                    && apiClient.isServerOnline(targetServerName, targetServerInfo.getServerId())) {
                  logger.info(
                      "Server {} is now online. Attempting to connect player {}.",
                      targetServerName,
                      player.getUsername());
                  connectPlayerToServer(player, targetServerName);
                } else {
                  attempts++;
                  if (attempts >= maxAttempts) {
                    logger.error(
                        "Server {} did not come online within the expected time for player {}. Cancelling connect task.",
                        targetServerName,
                        player.getUsername());
                    player.sendMessage(
                        messagesManager.prefixed(
                            MessageKey.CONNECT_START_TIMEOUT, "server", targetServerName));
                    startingServers.remove(targetServerName);
                  } else {
                    logger.debug(
                        "Server {} not online yet for player {}. Rescheduling check (Attempt {}/{}).",
                        targetServerName,
                        player.getUsername(),
                        attempts,
                        maxAttempts);
                    proxyServer
                        .getScheduler()
                        .buildTask(plugin, this)
                        .delay(checkInterval, java.util.concurrent.TimeUnit.SECONDS)
                        .schedule();
                  }
                }
              }
            })
        .delay(initialDelay, TimeUnit.SECONDS)
        .schedule();
  }

  private void connectPlayerToServer(Player player, String serverName) {
    Optional<RegisteredServer> serverOpt = proxyServer.getServer(serverName);

    if (serverOpt.isEmpty()) {
      logger.error(
          "Cannot connect player {}: Server '{}' not found/registered in Velocity.",
          player.getUsername(),
          serverName);
      player.sendMessage(
          messagesManager.prefixed(
              MessageKey.CONNECT_TARGET_SERVER_NOT_FOUND, "server", serverName));
      startingServers.remove(serverName);
      return;
    }

    RegisteredServer targetServer = serverOpt.get();

    if (player
        .getCurrentServer()
        .map(cs -> cs.getServer().equals(targetServer))
        .orElse(false)) {
      logger.debug("Player {} is already connected to {}. No action needed.", player.getUsername(), serverName);
      startingServers.remove(serverName);
      return;
    }

    logger.info("Connecting player {} to server {}.", player.getUsername(), serverName);
    player.createConnectionRequest(targetServer).fireAndForget();
  }

  private void scheduleInitialIdleCheck(String serverName, String serverId) {
    long idleCheckDelay = configurationManager.getIdleStartShutdownTime();
    if (idleCheckDelay < 0) return;

    long recheckInterval = Math.max(5, configurationManager.getStartupInitialCheckDelay());

    proxyServer
        .getScheduler()
        .buildTask(
            plugin,
            new Runnable() {
              @Override
              public void run() {
                if (!startingServers.contains(serverName)) {
                  logger.debug("Initial idle check for {}: Cancelled (server no longer starting).", serverName);
                  return;
                }

                if (!rateLimitTracker.canMakeRequest()) {
                  logger.debug("Initial idle check for {}: Rate limited. Rescheduling.", serverName);
                  reschedule();
                  return;
                }

                boolean online = apiClient.isServerOnline(serverName, serverId);
                if (online) {
                  if (apiClient.isServerEmpty(serverName)) {
                    logger.info(
                        messagesManager.raw(MessageKey.SERVER_IDLE_SHUTDOWN)
                            .replace("<server>", serverName));
                    apiClient.powerServer(serverId, PowerSignal.STOP);
                    startingServers.remove(serverName);
                  } else {
                    logger.debug("Initial idle check for {}: Players present. Cancelling idle shutdown task.", serverName);
                    startingServers.remove(serverName);
                  }
                } else {
                  logger.debug("Initial idle check for {}: Server not online yet. Rescheduling.", serverName);
                  reschedule();
                }
              }

              private void reschedule() {
                proxyServer
                    .getScheduler()
                    .buildTask(plugin, this)
                    .delay(recheckInterval, TimeUnit.SECONDS)
                    .schedule();
              }
            })
        .delay(idleCheckDelay, TimeUnit.SECONDS)
        .schedule();
  }
}