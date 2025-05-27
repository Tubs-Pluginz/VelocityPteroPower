/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.handler;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import de.tubyoub.velocitypteropower.VelocityPteroPower;
import de.tubyoub.velocitypteropower.model.PteroServerInfo;
import de.tubyoub.velocitypteropower.api.PanelAPIClient;
import de.tubyoub.velocitypteropower.api.PowerSignal;
import de.tubyoub.velocitypteropower.manager.ConfigurationManager;
import de.tubyoub.velocitypteropower.manager.MessagesManager;
import de.tubyoub.velocitypteropower.util.RateLimitTracker;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Handles the ServerPreConnectEvent to manage automatic server starting,
 * player cooldowns, limbo redirection, and connection scheduling.
 */
public class PlayerConnectionHandler {

    private final ProxyServer proxyServer;
    private final VelocityPteroPower plugin;
    private final ComponentLogger logger;
    private final ConfigurationManager configurationManager;
    private final MessagesManager messagesManager;
    private final PanelAPIClient apiClient;
    private final RateLimitTracker rateLimitTracker;

    // State maps passed from the main plugin instance
    private final Map<String, PteroServerInfo> serverInfoMap;
    private final Set<String> startingServers;
    private final Map<UUID, Long> playerCooldowns;

    /**
     * Constructor for PlayerConnectionHandler.
     * @param proxyServer Velocity ProxyServer instance.
     * @param plugin Plugin instance.
     */
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

    /**
     * Listens for players attempting to connect to a server.
     * Handles starting offline servers managed by this plugin.
     * Includes checks for management status, player cooldowns, server online status,
     * starting status, rate limits, and limbo server logic.
     *
     * @param event The server pre-connect event.
     */
    @Subscribe(priority = 10) // Use constant if available, otherwise numeric
    public void onServerPreConnect(ServerPreConnectEvent event) {
        Player player = event.getPlayer();
        RegisteredServer targetServer = event.getOriginalServer();
        String serverName = targetServer.getServerInfo().getName();

        // 1. Check if this server is managed by the plugin
        PteroServerInfo serverInfo = serverInfoMap.get(serverName);
        if (serverInfo == null) {
            handleUnmanagedServer(player, serverName);
            return; // Not managed by this plugin
        }
        if (!plugin.getWhitelistManager().isPlayerWhitelisted(serverName, player.getUsername())) {
            if (event.getPreviousServer() == null) {
                player.disconnect(Component.text("You are not whitelisted on this server"));
                return;
            }
            player.sendMessage(Component.text("You are not whitelisted on this server"));
        }

        String serverId = serverInfo.getServerId();

        // 2. Check Player Cooldown for starting this specific server
        if (isPlayerOnCooldown(player, serverName) && event.getPreviousServer() != null ) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            return; // Player is on cooldown for this action
        }

        // 3. Check Server Online Status (respecting rate limit)
        boolean isOnline = rateLimitTracker.canMakeRequest() && apiClient.isServerOnline(serverName, serverId);

        if (isOnline) {
            // Server is online, allow connection (clear starting flag if set)
            startingServers.remove(serverName);
            logger.debug("Server {} is online. Allowing connection for {}.", serverName, player.getUsername());
            // Let Velocity handle the connection (no need to set result if allowing original)
            return;
        }

        // 4. Server is Offline - Handle Startup Logic
        handleOfflineServerConnection(event, player, serverName, serverId, serverInfo);
    }

    // --- Helper Methods ---

    /** Handles logging and messaging when a player tries to join an unmanaged server. */
    private void handleUnmanagedServer(Player player, String serverName) {
        logger.debug("Server '{}' is not managed by VelocityPteroPower.", serverName);
        // Optionally notify player if configured
        if (configurationManager.isServerNotFoundMessage()) {
            player.sendMessage(
                getPluginPrefix().append(
                    Component.text(
                        messagesManager.getMessage("server-not-found")
                            .replace("%server%", serverName),
                        NamedTextColor.RED // Use red for errors
                    )
                )
            );
        }
        // Allow Velocity to handle the connection attempt normally
    }

    /** Checks if a player is on cooldown for starting a server. Sends message if true. */
    private boolean isPlayerOnCooldown(Player player, String serverName) {
        long currentTime = System.currentTimeMillis();
        long lastStartTime = playerCooldowns.getOrDefault(player.getUniqueId(), 0L);
        int cooldownMillis = configurationManager.getPlayerCommandCooldown() * 1000;

        if (currentTime - lastStartTime < cooldownMillis) {
            long remainingSeconds = TimeUnit.MILLISECONDS.toSeconds(cooldownMillis - (currentTime - lastStartTime)) + 1; // Ceil
            player.sendMessage(
                getPluginPrefix().append(
                    Component.text(
                        messagesManager.getMessage("cooldown-active")
                            .replace("%timeout%", String.valueOf(remainingSeconds)),
                        NamedTextColor.YELLOW // Use yellow for warnings/cooldowns
                    )
                )
            );
            logger.debug("Player {} is on cooldown for starting server {}.", player.getUsername(), serverName);
            return true;
        }
        return false;
    }

    /** Handles the logic when a player tries to connect to an offline, managed server. */
    private void handleOfflineServerConnection(ServerPreConnectEvent event, Player player, String serverName, String serverId, PteroServerInfo serverInfo) {
        // 5. Check if server is already being started by someone else
        if (startingServers.contains(serverName) && event.getPreviousServer() != null) {
            player.sendMessage(
                getPluginPrefix().append(
                    Component.text(
                        messagesManager.getMessage("server-starting")
                            .replace("%server%", serverName),
                        NamedTextColor.YELLOW
                    )
                )
            );
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            logger.debug("Server {} is already starting. Denying connection for {}.", serverName, player.getUsername());
            return;
        }

        // 6. Check Rate Limit *before* attempting start
        if (!rateLimitTracker.canMakeRequest()) {
            logger.warn("Cannot start server {} ({}) for {} due to rate limiting.", serverName, serverId, player.getUsername());
            player.sendMessage(
                getPluginPrefix().append(
                    Component.text(messagesManager.getMessage("error-rate-limited"), NamedTextColor.RED)
                )
            );
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            return;
        }

        // 7. Attempt to Start the Server
        if (!startingServers.contains(serverName)) {
            logger.info("Attempting to start server '{}' ({}) for player {}", serverName, serverId, player.getUsername());
            startingServers.add(serverName);
            playerCooldowns.put(player.getUniqueId(), System.currentTimeMillis()); // Apply cooldown *now*
            apiClient.powerServer(serverId, PowerSignal.START); // Use Enum
            scheduleInitialIdleCheck(serverName, serverId);
        }// Schedule check in case player leaves queue

        // 8. Handle Player Redirection (Limbo or Disconnect)
        Optional<RegisteredServer> limboServerOpt = findValidLimboServer();

        if (limboServerOpt.isPresent()) {
            // Redirect to Limbo
            RegisteredServer limboServer = limboServerOpt.get();
            logger.info("Redirecting player {} to limbo server '{}' while server '{}' starts.", player.getUsername(), limboServer.getServerInfo().getName(), serverName);
            player.sendMessage(
                getPluginPrefix().append(
                    Component.text(
                        messagesManager.getMessage("redirecting-to-limbo")
                            .replace("%server%", serverName)
                            .replace("%limbo%", limboServer.getServerInfo().getName()),
                        NamedTextColor.AQUA
                    )
                )
            );
            event.setResult(ServerPreConnectEvent.ServerResult.allowed(limboServer));

            // Schedule task to check target server status and connect player later
            scheduleDelayedConnect(player, serverName, serverInfo);
        } else {
            // No usable Limbo
            if (event.getPreviousServer() == null) {
                // Disconnect Player
                logger.info("Disconnecting player {} while server '{}' starts (Limbo not available/usable).", player.getUsername(), serverName);
                player.disconnect(
                    Component.text(
                        messagesManager.getMessage("starting-server-disconnect")
                            .replace("%server%", serverName),
                        NamedTextColor.WHITE
                    )
                );

                // No need to schedule connect task if player is disconnected
            } else {
                // Keep Player connected
                player.sendMessage(
                    getPluginPrefix().append(
                        Component.text(
                            messagesManager.getMessage("starting-server-disconnect")
                                .replace("%server%", serverName),
                            NamedTextColor.AQUA
                        )
                    )
                );

                // Schedule task to check target server status and connect player later
                scheduleDelayedConnect(player, serverName, serverInfo);
            }

            event.setResult(ServerPreConnectEvent.ServerResult.denied());
        }
    }

    /** Finds and validates the configured limbo server. Returns empty if not usable. */
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

        // Check if the limbo server itself is managed by VPP and needs starting
        PteroServerInfo limboInfo = serverInfoMap.get(limboServerName);
        if (limboInfo != null) {
            // VPP manages the limbo server, check its status
            if (!rateLimitTracker.canMakeRequest()) {
                logger.warn("Rate limited. Cannot check or start VPP-managed limbo server '{}'.", limboServerName);
                return Optional.empty(); // Cannot guarantee limbo is usable
            }
            if (!apiClient.isServerOnline(limboServerName, limboInfo.getServerId())) {
                logger.warn("VPP-managed limbo server '{}' is offline. Attempting to start it, but cannot use it for redirection now.", limboServerName);
                apiClient.powerServer(limboInfo.getServerId(), PowerSignal.START); // Try to start it
                return Optional.empty(); // Cannot use it right now
            }
            logger.debug("VPP-managed limbo server '{}' is online.", limboServerName);
        } else {
            logger.debug("Limbo server '{}' is registered but not managed by VPP. Assuming usable.", limboServerName);
        }

        return limboOpt; // Limbo is configured, registered, and (if managed) online
    }

    /** Schedules a task to periodically check if the target server is online and connect the player. */
    private void scheduleDelayedConnect(Player player, String targetServerName, PteroServerInfo targetServerInfo) {
        long initialDelay = configurationManager.getStartupInitialCheckDelay();
        long checkInterval = Math.max(5, targetServerInfo.getJoinDelay()); // Use join delay as interval, min 5s

        proxyServer.getScheduler().buildTask(plugin, new Runnable() {
            private int attempts = 0;
            private final int maxAttempts = 12; // Max checks (e.g., 12 * 5s = 1 minute timeout)

            @Override
            public void run() {
                // Check if player is still online and potentially still on limbo
                if (!player.isActive() || player.getCurrentServer().isEmpty()) {
                    logger.info("Player {} disconnected or left limbo while waiting for {}. Cancelling connect task.", player.getUsername(), targetServerName);
                    startingServers.remove(targetServerName); // Allow others to try starting
                    return;
                }

                // Check if target server is now online (respect rate limit)
                if (rateLimitTracker.canMakeRequest() && apiClient.isServerOnline(targetServerName, targetServerInfo.getServerId())) {
                    logger.info("Server {} is now online. Attempting to connect player {}.", targetServerName, player.getUsername());
                    connectPlayerToServer(player, targetServerName);
                    // Task completes successfully
                } else {
                    attempts++;
                    if (attempts >= maxAttempts) {
                        logger.error("Server {} did not come online within the expected time for player {}. Cancelling connect task.", targetServerName, player.getUsername());
                        player.sendMessage(getPluginPrefix().append(Component.text(messagesManager.getMessage("error-start-timeout").replace("%server%", targetServerName), NamedTextColor.RED)));
                        startingServers.remove(targetServerName); // Allow others to try starting
                        // Consider disconnecting player from limbo here if desired
                    } else {
                        logger.debug("Server {} not online yet for player {}. Rescheduling check (Attempt {}/{}).", targetServerName, player.getUsername(), attempts, maxAttempts);
                        // Reschedule the same task
                        proxyServer.getScheduler().buildTask(plugin, this) // Reschedule self
                            .delay(checkInterval, TimeUnit.SECONDS).schedule();
                    }
                }
            }
        }).delay(initialDelay, TimeUnit.SECONDS).schedule();
    }

    /**
     * Attempts to connect a player to the specified server.
     * Assumes the server is online (should be checked before calling).
     *
     * @param player     The player to connect.
     * @param serverName The target server name.
     */
    private void connectPlayerToServer(Player player, String serverName) {
        Optional<RegisteredServer> serverOpt = proxyServer.getServer(serverName);

        if (serverOpt.isEmpty()) {
            logger.error("Cannot connect player {}: Server '{}' not found/registered in Velocity.", player.getUsername(), serverName);
            player.sendMessage(getPluginPrefix().append(Component.text(messagesManager.getMessage("error-generic"), NamedTextColor.RED))); // Generic error
            startingServers.remove(serverName); // Clean up starting state
            return;
        }

        RegisteredServer targetServer = serverOpt.get();

        // Prevent unnecessary connection attempts if already there (e.g., race condition)
        if (player.getCurrentServer().map(cs -> cs.getServer().equals(targetServer)).orElse(false)) {
             logger.debug("Player {} is already connected to {}. No action needed.", player.getUsername(), serverName);
             startingServers.remove(serverName); // Ensure flag is cleared
             return;
        }

        logger.info("Connecting player {} to server {}.", player.getUsername(), serverName);
        player.createConnectionRequest(targetServer).fireAndForget();
    }

     /**
     * Schedules a check shortly after starting a server to see if it's immediately idle.
     * If it is, shut it down to prevent unused servers staying online.
     *
     * @param serverName The name of the server.
     * @param serverId   The panel ID of the server.
     */
    private void scheduleInitialIdleCheck(String serverName, String serverId) {
        long idleCheckDelay = configurationManager.getIdleStartShutdownTime();
        if (idleCheckDelay < 0) return; // Disabled

        proxyServer.getScheduler().buildTask(plugin, () -> {
            if (startingServers.contains(serverName) && rateLimitTracker.canMakeRequest()) {
                // Check if it's online AND empty
                if (apiClient.isServerOnline(serverName, serverId) && apiClient.isServerEmpty(serverName)) {
                    logger.info(
                        messagesManager.getMessage("idle-shutdown")
                            .replace("%server%", serverName)
                    );
                    apiClient.powerServer(serverId, PowerSignal.STOP);
                    startingServers.remove(serverName);
                } else {
                    logger.debug("Initial idle check for {}: Server not online or not empty.", serverName);
                }
            } else {
                 logger.debug("Initial idle check for {}: Skipped (no longer starting or rate limited).", serverName);
            }
        }).delay(idleCheckDelay, TimeUnit.SECONDS).schedule();
    }

    /**
     * Gets the formatted plugin prefix for messages.
     * Example: "[VPP] "
     *
     * @return The prefix component.
     */
    private Component getPluginPrefix() {
        // TODO: Use a config value for the prefix color.
        TextColor prefixColor = TextColor.color(66, 135, 245); // Blueish
        return Component.text("[", NamedTextColor.GRAY)
            .append(Component.text(messagesManager.getMessage("prefix"), prefixColor))
            .append(Component.text("] ", NamedTextColor.GRAY));
    }
}
