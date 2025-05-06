/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 *
 *  Copyright (c) TubYoub <github@tubyoub.de>
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package de.tubyoub.velocitypteropower;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import de.tubyoub.velocitypteropower.api.PanelAPIClient;
import de.tubyoub.velocitypteropower.api.PanelType;
import de.tubyoub.velocitypteropower.api.PelicanAPIClient;
import de.tubyoub.velocitypteropower.api.PterodactylAPIClient;
import de.tubyoub.velocitypteropower.manager.ConfigurationManager;
import de.tubyoub.velocitypteropower.manager.MessagesManager;
import de.tubyoub.velocitypteropower.util.Metrics;
import de.tubyoub.velocitypteropower.util.VersionChecker;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.net.InetSocketAddress;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Main class for the VelocityPteroPower plugin.
 * This class handles the initialization of the plugin and the registration of commands and events.
 */
@Plugin(id = "velocity-ptero-power", name = "VelocityPteroPower", version = "0.9.3.2", authors = {"TubYoub"}, description = "A plugin for Velocity that allows you to manage your Pterodactyl/Pelican servers from the Velocity console.", url = "https://github.com/TubYoub/VelocityPteroPower")
public class VelocityPteroPower {
    private final String version = "0.9.3.2";
    private final String project = "1dDr5J4w";
    private final int pluginId = 21465;

    private final ProxyServer proxyServer;
    private final ComponentLogger logger;
    private final Path dataDirectory;
    private Map<String, PteroServerInfo> serverInfoMap;
    private final CommandManager commandManager;
    private final ConfigurationManager configurationManager;
    private final MessagesManager messagesManager;
    private VersionChecker.VersionInfo versionInfo;
    private PanelAPIClient apiClient;
    private final Metrics.Factory metricsFactory;
    private final Set<String> startingServers = ConcurrentHashMap.newKeySet();
    private final AtomicInteger rateLimit = new AtomicInteger(60); // Default value, will be updated
    private final AtomicInteger remainingRequests = new AtomicInteger(60); // Default value, will be updated
    private final ReentrantLock rateLimitLock = new ReentrantLock();
    private final Map<String, Integer> retryCounts = new ConcurrentHashMap<>();
    private final Map<UUID, Long> playerCooldowns = new ConcurrentHashMap<>();

    /**
     * Constructor for the VelocityPteroPower class.
     *
     * @param proxy The ProxyServer instance. This is the main server instance for the Velocity proxy.
     * @param dataDirectory The path to the data directory. This is where the plugin can store and retrieve data.
     * @param commandManager The CommandManager instance. This is used to register and manage commands for the plugin.
     * @param logger The ComponentLogger instance. This is used for logging messages to the console.
     * @param metricsFactory The Metrics.Factory instance. This is used for creating metrics for the plugin.
     */
    @Inject
    public VelocityPteroPower(ProxyServer proxy, @DataDirectory Path dataDirectory,CommandManager commandManager,ComponentLogger logger, Metrics.Factory metricsFactory) {
        this.proxyServer = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.commandManager = commandManager;
        this.configurationManager = new ConfigurationManager(this);
        this.messagesManager = new MessagesManager(this);
        this.metricsFactory = metricsFactory;
    }

     /**
     * This method is called when the proxy server is initialized.
     * It logs the startup message, loads the configuration, initializes the Pterodactyl API client,
     * registers the commands and events, and checks for updates if enabled in the configuration.
     *
     * @param event the proxy initialize event
     */
    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        logger.info(MiniMessage.miniMessage().deserialize("<#4287f5>____   ________________________"));
        logger.info(MiniMessage.miniMessage().deserialize("<#4287f5>\\   \\ /   /\\______   \\______   \\"));
        logger.info(MiniMessage.miniMessage().deserialize("<#4287f5> \\   Y   /  |     ___/|     ___/"));
        logger.info(MiniMessage.miniMessage().deserialize("<#4287f5>  \\     /   |    |    |    |"+ "<#00ff77>         VelocityPteroPower <#6b6c6e>v" + version));
        logger.info(MiniMessage.miniMessage().deserialize("<#4287f5>   \\___/    |____|tero|____|ower" + "<#A9A9A9>     Running with Blackmagic on Velocity"));

        configurationManager.loadConfig();
        messagesManager.loadMessages();

        // Check if API key is valid
        if (!configurationManager.hasValidApiKey()) {
            logger.error("=================================================");
            logger.error("VelocityPteroPower initialization failed!");
            logger.error("No valid API key found in configuration.");
            logger.error("Please add a valid API key to the config.yml file.");
            logger.error("Plugin will be disabled.");
            logger.error("=================================================");
            return;
        }

        if (configurationManager.getPanelType() == PanelType.pelican) {
            logger.info("detected the pelican panel");
            this.apiClient = new PelicanAPIClient(this);
        } else {
            logger.info("detected the pterodactyl panel");
            this.apiClient = new PterodactylAPIClient(this);
        }

        commandManager.register("ptero", new PteroCommand(this));
        proxyServer.getEventManager().register(this,new ServerSwitchListener(this));

        this.serverInfoMap = configurationManager.getServerInfoMap();
        Metrics metrics = metricsFactory.make(this, pluginId);

        if (configurationManager.isCheckUpdate()) {
            versionInfo = VersionChecker.isNewVersionAvailable(version, project);
            if (versionInfo.isNewVersionAvailable) {
                switch  (versionInfo.urgency) {
                    case CRITICAL, HIGH:
                        this.getLogger().warn("--- Important Update --- ");
                        this.getLogger().warn("There is a new critical update for VelocityPteroPower available");
                        this.getLogger().warn("please update NOW");
                        this.getLogger().warn("https://modrinth.com/plugin/velocitypteropower/version/" + versionInfo.latestVersion);
                        this.getLogger().warn("backup your config");
                        this.getLogger().warn("---");
                        break;
                    case NORMAL, LOW, NONE:
                        this.getLogger().warn("There is a new update for VelocityPteroPower available");
                        this.getLogger().warn("https://modrinth.com/plugin/velocitypteropower/version/" + versionInfo.latestVersion);
                        this.getLogger().warn("backup your config");
                        break;
                }
            } else {
                this.getLogger().info(" You are running the latest version of VelocityPteroPower");
            }
        } else {
            this.getLogger().info("You have automatic checks for new updates disabled. Enable them in the config to stay up to date");
        }
        logger.isEnabledForLevel(configurationManager.getLoggerLevel());
        logger.info("VelocityPteroPower succesfully loaded");
    }

    /**
     * This method schedules a server shutdown if the server is empty.
     *
     * @param serverName the name of the server
     * @param serverID the ID of the server
     * @param timeout the timeout in seconds after which the server should be shut down if it is empty
     */
        public ScheduledTask scheduleServerShutdown(String serverName, String serverID, int timeout) {
            if (timeout < 0) {
                return null;
            }
            logger.info(messagesManager.getMessage("shutdown-scheduled")
                    .replace("%server%", serverName)
                    .replace("%timeout%", String.valueOf(timeout)));
            return proxyServer.getScheduler().buildTask(this, () -> {
                if (canMakeRequest() && apiClient.isServerEmpty(serverName)) {
                    apiClient.powerServer(serverID, "stop");
                    logger.info(messagesManager.getMessage("server-shutting-down")
                            .replace("%server%", serverName));
                    scheduleShutdownCheck(serverName, serverID);
                }else {
                    logger.info(messagesManager.getMessage("shutdown-cancelled")
                            .replace("%server%", serverName));
                }
            }).delay(timeout, TimeUnit.SECONDS).schedule();
        }

        private void scheduleShutdownCheck(String serverName, String serverID) {
            proxyServer.getScheduler().buildTask(this, () -> {
                if (canMakeRequest() && apiClient.isServerOnline(serverName,serverID)) {
                    int retryCount = retryCounts.getOrDefault(serverName, 0) + 1;
                    // Check emptiness again before retrying stop command
                    if (retryCount <= configurationManager.getShutdownRetryDelay()) {
                        if (canMakeRequest() && apiClient.isServerEmpty(serverName)){
                            retryCounts.put(serverName, retryCount);
                            logger.warn(messagesManager.getMessage("server-still-online-retying")
                                    .replace("%server%", serverName)
                                    .replace("%retry%", String.valueOf(retryCount))
                                    .replace("%maxRetries%", String.valueOf(configurationManager.getShutdownRetries())));
                            apiClient.powerServer(serverID, "stop");
                            scheduleShutdownCheck(serverName, serverID);
                        } else { // Server is no longer empty, cancel shutdown
                            logger.info(messagesManager.getMessage("shutdown-cancelled")
                                    .replace("%server%", serverName));
                            retryCounts.remove(serverName);
                        }
                    } else {
                        retryCounts.remove(serverName);
                        logger.error(messagesManager.getMessage("shutdown-failed")
                                .replace("%server%", serverName)
                                .replace("%retry%", String.valueOf(retryCount)));
                    }
                } else {
                    retryCounts.remove(serverName);
                    if (!canMakeRequest()) {
                        logger.warn("Could not confirm shutdown status for {} due to rate limit.", serverName);
                    } else {
                        logger.info(messagesManager.getMessage("shutdown-success") // Assume success if offline
                                .replace("%server%", serverName));
                    }
                }
            }).delay(configurationManager.getShutdownRetryDelay(), TimeUnit.SECONDS).schedule();
        }
     /**
     * This method is called when a player tries to connect to a server.
     * It checks if the server is online and starts it if it is not.
     * If the server is already starting, it sends a message to the player and denies the connection.
     * If the server is offline, it starts the server, sends a message to the player, denies the connection,
     * and schedules a task to check if the server is online and connect the player.
     *
     * @param event the server pre-connect event
     */
    @Subscribe(priority = 10)
    public void onServerPreConnect(ServerPreConnectEvent event) {
        Player player = event.getPlayer();
        String serverName = event.getOriginalServer().getServerInfo().getName();
        this.serverInfoMap = configurationManager.getServerInfoMap();
        PteroServerInfo serverInfo = serverInfoMap.get(serverName);

        // 1. Check if this server is managed by the plugin
        if (!serverInfoMap.containsKey(serverName)) {
            logger.warn(messagesManager.getMessage("server-not-found")
                    .replace("%server%", serverName));
            if (configurationManager.isServerNotFoundMessage()) {
                player.sendMessage(
                        this.getPluginPrefix()
                                .append(Component.text(messagesManager.getMessage("server-not-found")
                                        .replace("%server%", serverName), NamedTextColor.WHITE)));
            }
            return;
        }

        String serverId = serverInfo.getServerId();

        // 2. Check Player Cooldown
        long currentTime = System.currentTimeMillis();
        long lastStartTime = playerCooldowns.getOrDefault(player.getUniqueId(), 0L);
        int cooldownTime = configurationManager.getPlayerCommandCooldown() * 1000; // Convert to milliseconds

        if (currentTime - lastStartTime < cooldownTime) {
            long remainingSeconds = (cooldownTime - (currentTime - lastStartTime)) / 1000;
            player.sendMessage(
                    this.getPluginPrefix()
                            .append(Component.text(messagesManager.getMessage("cooldown-active")
                                    .replace("%timeout%", String.valueOf(remainingSeconds)), NamedTextColor.WHITE)));
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            return;
        }

        // 3. Check if the server is online
        boolean isOnline = canMakeRequest() && apiClient.isServerOnline(serverName,serverId);
        if (isOnline) {
            if (startingServers.contains(serverName)) {
                logger.info("Server {} ({}) is now online.", serverName, serverId);
                startingServers.remove(serverName);
            }
            return;
        }

        // 4. Check if already starting
        if (startingServers.contains(serverName)) {
            player.sendMessage(
                    this.getPluginPrefix()
                            .append(Component.text(messagesManager.getMessage("server-starting")
                                    .replace("%server%", serverName), NamedTextColor.YELLOW)));
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            return;
        }

        // 5. Check Rate Limit *before* attempting start
        if (!canMakeRequest()) {
            logger.warn("Cannot start server {} ({}) due to rate limiting.", serverName, serverId);
            player.sendMessage(
                    this.getPluginPrefix()
                            .append(Component.text(messagesManager.getMessage("error-rate-limited"), NamedTextColor.YELLOW)));
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            return;
        }

        // 6. Get Limbo Server details
        String limboServerName = configurationManager.getLimboServerName();
        Optional<RegisteredServer> limboServerOpt = Optional.empty();
        boolean useLimbo = false;

        if (limboServerName != null) {
            // Limbo server *is* configured, now check if it's usable
            limboServerOpt = proxyServer.getServer(limboServerName); // Is it registered in Velocity?

            if (limboServerOpt.isPresent()) {
                // It's registered in Velocity. Now check if VPP manages it.
                if (serverInfoMap.containsKey(limboServerName)) {
                    // VPP manages this limbo server. Check its online status via API.
                    PteroServerInfo limboInfo = serverInfoMap.get(limboServerName);
                    String limboServerId = limboInfo.getServerId();

                    if (canMakeRequest()) {
                        if (apiClient.isServerOnline(limboServerName,limboServerId)) {
                            logger.debug("VPP-managed limbo server '{}' is online. Using it.", limboServerName);
                            useLimbo = true;
                        } else {
                            logger.warn("VPP-managed limbo server '{}' is offline. Starting it, but player {} will be disconnected.", limboServerName, player.getUsername());
                            apiClient.powerServer(limboServerId, "start");
                        }
                    } else {
                        logger.warn("Rate limited. Cannot check online status or start VPP-managed limbo server '{}'. Player {} will be disconnected.", limboServerName, player.getUsername());
                    }
                } else {
                    logger.debug("Limbo server '{}' is registered but not managed by VPP. Assuming usable.", limboServerName);
                    useLimbo = true;
                }
            } else {
                logger.error("The configured limbo server '{}' is not registered with Velocity. Player {} will be disconnected.", limboServerName, player.getUsername());
            }
        } else {
            logger.info("Limbo server is not configured. Player {} will be disconnected.", player.getUsername());
        }

        // 7. Start the Server & Handle Player
        logger.info("Attempting to start server '{}' ({}) for player {}", serverName, serverId, player.getUsername());
        startingServers.add(serverName);
        playerCooldowns.put(player.getUniqueId(), currentTime); // Apply cooldown
        apiClient.powerServer(serverId, "start"); // Start the TARGET server
        checkInitialServerActivity(serverName, serverId); // Schedule idle check for TARGET server

        if (useLimbo) {
            // We already confirmed limboServerOpt.isPresent() if useLimbo is true
            RegisteredServer limboServer = limboServerOpt.get();
            logger.info("Redirecting player {} to limbo server '{}' while server '{}' starts.", player.getUsername(), limboServerName, serverName);
            player.sendMessage(
                    this.getPluginPrefix()
                            .append(Component.text(messagesManager.getMessage("redirecting-to-limbo")
                                    .replace("%server%", serverName)
                                    .replace("%limbo%", limboServerName), NamedTextColor.AQUA)));

            event.setResult(ServerPreConnectEvent.ServerResult.allowed(limboServer));

            // Schedule the task to check and connect later FROM LIMBO
            long initialDelay = configurationManager.getStartupInitialCheckDelay();
            proxyServer.getScheduler().buildTask(this, () -> {
                checkServerAndConnectPlayer(player, serverName);
            }).delay(initialDelay, TimeUnit.SECONDS).schedule();

        } else {
            // Disconnect player (Limbo not configured, not registered, offline, or rate limited)
            logger.info("Disconnecting player {} while server '{}' starts (Limbo not available/usable).", player.getUsername(), serverName);
            if(event.getPreviousServer() == null){
                 player.disconnect(
                        Component.text(messagesManager.getMessage("starting-server-disconnect")
                                .replace("%server%", serverName), NamedTextColor.WHITE));
            }else{
                player.sendMessage(
                        this.getPluginPrefix().append(Component.text(messagesManager.getMessage("starting-server-disconnect")
                                .replace("%server%", serverName), NamedTextColor.AQUA)));
            }
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
        }
    }

    private void checkServerAndConnectPlayer(Player player, String serverName) {
        PteroServerInfo serverInfo = serverInfoMap.get(serverName);
        if (apiClient.isServerOnline(serverName, serverInfo.getServerId()) && this.canMakeRequest()) {
            connectPlayer(player, serverName);
        } else {
            proxyServer.getScheduler().buildTask(this, () -> checkServerAndConnectPlayer(player, serverName))
                    .delay(serverInfo.getJoinDelay(), TimeUnit.SECONDS).schedule();
        }
    }

     /**
     * This method connects a player to a server.
     * It first retrieves the server by its name. If the server is not found, it throws a RuntimeException.
     * If the player is not currently connected to any server and the target server is empty, it schedules a shutdown for the server.
     * If the player is already connected to the target server, it does nothing.
     * If the target server is online, it sends a connection request to the player and removes the server from the startingServers set.
     *
     * @param player the player to connect
     * @param serverName the name of the server
     */
    private void connectPlayer(Player player, String serverName) {
        RegisteredServer server = proxyServer.getServer(serverName).orElseThrow(() -> new RuntimeException("Server not found: " + serverName));

        if (!player.getCurrentServer().isPresent()) {
            if (apiClient.isServerEmpty(serverName)){
                this.scheduleServerShutdown(serverName, serverInfoMap.get(serverName).getServerId(),serverInfoMap.get(serverName).getTimeout());
            }
            return;
        }
        // Check if the player is already connected to the server
        if (player.getCurrentServer().get().getServerInfo().getName().equals(serverName)) {
            return;
        }

        if (apiClient.isServerOnline(serverName, serverInfoMap.get(serverName).getServerId()) && this.canMakeRequest()) {
            player.createConnectionRequest(server).fireAndForget();
            startingServers.remove(serverName);
        }
    }
    public boolean canMakeRequest() {
        rateLimitLock.lock();
        try {
            return remainingRequests.get() > 0;
        } finally {
            rateLimitLock.unlock();
        }
    }

    public void updateRateLimitInfo(HttpResponse<String> response) {
        rateLimitLock.lock();
        try {
            String limitHeader = response.headers().firstValue("x-ratelimit-limit").orElse(null);
            String remainingHeader = response.headers().firstValue("x-ratelimit-remaining").orElse(null);

            if (limitHeader != null) {
                rateLimit.set(Integer.parseInt(limitHeader));
            }
            if (remainingHeader != null) {
                remainingRequests.set(Integer.parseInt(remainingHeader));
            }
        } finally {
            rateLimitLock.unlock();
            if (configurationManager.isPrintRateLimit()) {
                logger.info("Rate limit updated: Limit: {}, Remaining: {}", rateLimit.get(), remainingRequests.get());
            }
        }
    }

    public Component getPluginPrefix() {
        return Component.text("[", NamedTextColor.WHITE)
            .append(Component.text(messagesManager.getMessage("prefix"), TextColor.color(66,135,245)))
            .append(Component.text("] ", NamedTextColor.WHITE));
    }

    private void checkInitialServerActivity(String serverName, String serverId) {
        proxyServer.getScheduler().buildTask(this, () -> {
            if (apiClient.isServerOnline(serverName, serverId) && apiClient.isServerEmpty(serverName)) {
                apiClient.powerServer(serverId, "stop");
                logger.info(messagesManager.getMessage("idle-shutdown")
                        .replace("%server%", serverName));
                startingServers.remove(serverName);
            }
        }).delay(configurationManager.getIdleStartShutdownTime(), TimeUnit.SECONDS).schedule();
    }

    /**
     * This method reloads the configuration for the VelocityPteroPower plugin.
     * It calls the loadConfig method of the ConfigurationManager instance to reload the configuration.
     * It then updates the serverInfoMap with the new configuration.
     */
    public void reloadConfig() {
        configurationManager.loadConfig();
        this.serverInfoMap = configurationManager.getServerInfoMap();
        messagesManager.loadMessages();
        this.logger.isEnabledForLevel(configurationManager.getLoggerLevel());
    }
    /**
     * This method returns the map of server names to PteroServerInfo objects.
     *
     * @return the map of server names to PteroServerInfo objects
     */
    public Map<String, PteroServerInfo> getServerInfoMap() {
        return serverInfoMap;
    }

    /**
     * Returns the ProxyServer instance.
     *
     * @return the ProxyServer instance
     */
    public ProxyServer getProxyServer(){
        return proxyServer;
    }

    /**
     * Returns the ComponentLogger instance.
     *
     * @return the ComponentLogger instance
     */
    public ComponentLogger getLogger(){
        return logger;
    }

    /**
     * Returns the Path to the data directory.
     *
     * @return the Path to the data directory
     */
    public Path getDataDirectory(){
        return dataDirectory;
    }

    /**
     * Returns the PterodactylAPIClient instance.
     *
     * @return the PterodactylAPIClient instance
     */
    public PanelAPIClient getAPIClient() {
        return apiClient;
    }

    /**
     * Returns the ConfigurationManager instance.
     *
     * @return the ConfigurationManager instance
     */
    public ConfigurationManager getConfigurationManager() {
        return configurationManager;
    }
    public MessagesManager getMessagesManager() {
        return messagesManager;
    }
    public void onProxyShutdown(ProxyShutdownEvent event) {
        apiClient.shutdown();
        logger.info("Shutting down VelocityPteroPower... Goodbye");
    }
}