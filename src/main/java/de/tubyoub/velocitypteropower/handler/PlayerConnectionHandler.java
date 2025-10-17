package de.tubyoub.velocitypteropower.handler;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
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
  private final Map<String, UUID> startInitiators;

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
    this.startInitiators = plugin.getStartInitiators();
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
      // If the player is already connected to the target server, do not attempt a transfer
      boolean alreadyOnTarget = player
          .getCurrentServer()
          .map(cs -> cs.getServer().getServerInfo().getName().equalsIgnoreCase(serverName))
          .orElse(false);
      if (alreadyOnTarget) {
        logger.debug("Player {} is already connected to {}. Suppressing transfer.", player.getUsername(), serverName);
        event.setResult(ServerPreConnectEvent.ServerResult.denied());
        return;
      }
      startingServers.remove(serverName);
      startInitiators.remove(serverName);
      plugin.getStartingServersSince().remove(serverName);
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
      UUID initiator = startInitiators.get(serverName);
      MessageKey key = (initiator != null && initiator.equals(player.getUniqueId()))
          ? MessageKey.CONNECT_SERVER_STARTING_INITIATOR
          : MessageKey.CONNECT_SERVER_STARTING;
      player.sendMessage(messagesManager.prefixed(key, "server", serverName));
      event.setResult(ServerPreConnectEvent.ServerResult.denied());
      logger.debug(
          "Server {} is already starting. Denying connection for {}.",
          serverName,
          player.getUsername());
      // Queue this player for automatic connection when the server is online
      scheduleDelayedConnect(player, serverName, serverInfo);
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
      // Optionally exclude lobbies/limbos from the cap based on config
      if (!configurationManager.isCountLobbiesInMaxOnline()) {
        java.util.List<String> lobbies = configurationManager.getBalancerLobbies();
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
        java.util.List<String> limbos = configurationManager.getBalancerLimbos();
        if (limbos != null) exempt.addAll(limbos);
      }
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
      startInitiators.putIfAbsent(serverName, player.getUniqueId());
      plugin.getStartingServersSince().put(serverName, System.currentTimeMillis());
      playerCooldowns.put(player.getUniqueId(), System.currentTimeMillis());
      apiClient.powerServer(serverId, PowerSignal.START);
      scheduleInitialIdleCheck(serverName, serverId);
    }

    Optional<RegisteredServer> limboServerOpt = findValidLimboServer();

    if (limboServerOpt.isPresent()) {
      RegisteredServer limboServer = limboServerOpt.get();
      // If the player is already on the selected limbo, avoid re-sending them there to prevent Velocity errors
      boolean alreadyOnLimbo = event.getPreviousServer() != null && event.getPreviousServer().equals(limboServer);
      if (alreadyOnLimbo) {
        logger.debug("Player {} is already on limbo '{}'. Keeping them there while '{}' starts.",
            player.getUsername(), limboServer.getServerInfo().getName(), serverName);
        // Do not change the server; just deny the switch and keep the player where they are
        {
          UUID initiator = startInitiators.get(serverName);
          MessageKey key = (initiator != null && initiator.equals(player.getUniqueId()))
              ? MessageKey.CONNECT_SERVER_STARTING_INITIATOR
              : MessageKey.CONNECT_SERVER_STARTING;
          player.sendMessage(messagesManager.prefixed(key, "server", serverName));
        }
        event.setResult(ServerPreConnectEvent.ServerResult.denied());
        scheduleDelayedConnect(player, serverName, serverInfo);
      } else {
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
      }
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
    // Try multi-lobby/limbo balancer first and only
    try {
      if (plugin.getLobbyBalancerManager() != null) {
        Optional<RegisteredServer> hold = plugin.getLobbyBalancerManager().pickHoldingServer();
        if (hold.isPresent()) {
          return hold;
        }
      }
    } catch (Exception ex) {
      logger.debug("Balancer selection failed: {}", ex.toString());
    }
    return Optional.empty();
  }

  private void scheduleDelayedConnect(
    Player player, String targetServerName, PteroServerInfo targetServerInfo) {
    Optional<ServerConnection> initialConnection = player.getCurrentServer();
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
                if (!initialConnection.equals(player.getCurrentServer()) && initialConnection.isPresent()) {
                    logger.info("Player {} already connected to a different Server: {}. Connection attempt to Server: {} is beeing aborted", 
                            player.getUsername(), 
                            player.getCurrentServer().toString(), 
                            targetServerName);
                    startingServers.remove(targetServerName);
                    plugin.getStartingServersSince().remove(targetServerName);
                    startInitiators.remove(targetServerName);
                  return;
                }
                if (!player.isActive() || player.getCurrentServer().isEmpty()) {
                  logger.info(
                      "Player {} disconnected or left limbo while waiting for {}. Cancelling connect task.",
                      player.getUsername(),
                      targetServerName);
                  startingServers.remove(targetServerName);
                  plugin.getStartingServersSince().remove(targetServerName);
                  startInitiators.remove(targetServerName);
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
                    plugin.getStartingServersSince().remove(targetServerName);
                    startInitiators.remove(targetServerName);
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
      plugin.getStartingServersSince().remove(serverName);
      startInitiators.remove(serverName);
      return;
    }

    RegisteredServer targetServer = serverOpt.get();

    if (player
        .getCurrentServer()
        .map(cs -> cs.getServer().equals(targetServer))
        .orElse(false)) {
      logger.debug("Player {} is already connected to {}. No action needed.", player.getUsername(), serverName);
      startingServers.remove(serverName);
      plugin.getStartingServersSince().remove(serverName);
      startInitiators.remove(serverName);
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