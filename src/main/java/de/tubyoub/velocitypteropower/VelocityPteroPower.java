/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import de.tubyoub.velocitypteropower.api.*;
import de.tubyoub.velocitypteropower.command.PteroCommand;
import de.tubyoub.velocitypteropower.manager.ConfigurationManager;
import de.tubyoub.velocitypteropower.manager.MessagesManager;
import de.tubyoub.velocitypteropower.handler.PlayerConnectionHandler;
import de.tubyoub.velocitypteropower.lifecycle.ServerLifecycleManager;
import de.tubyoub.velocitypteropower.listener.ServerSwitchListener;
import de.tubyoub.velocitypteropower.manager.WhitelistManager;
import de.tubyoub.velocitypteropower.model.PteroServerInfo;
import de.tubyoub.velocitypteropower.service.UpdateService;
import de.tubyoub.velocitypteropower.util.FilteredComponentLogger;
import de.tubyoub.velocitypteropower.util.Metrics;
import de.tubyoub.velocitypteropower.util.RateLimitTracker;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.slf4j.event.Level;
import org.slf4j.helpers.MessageFormatter;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Main plugin class for VelocityPteroPower.
 * Handles plugin initialization, dependency setup, and provides access to core components.
 */
@Plugin(
    id = "velocity-ptero-power",
    name = "VelocityPteroPower",
    version = "0.9.4",
    authors = {"TubYoub"},
    description = "Manage Pterodactyl/Pelican servers via Velocity.",
    url = "https://github.com/BT-Pluginz/VelocityPteroPower"
)
public class VelocityPteroPower {
    // Plugin constants
    private static final String VERSION = "0.9.4";
    private static final String MODRINTH_PROJECT_ID = "1dDr5J4w";
    private static final int BSTATS_PLUGIN_ID = 21465;

    // Injected dependencies
    private final ProxyServer proxyServer;
    private ComponentLogger originalLogger;
    private final Path dataDirectory;
    private final CommandManager commandManager;
    private final Metrics.Factory metricsFactory;

    // Core Components (Managers, Services, Handlers)
    private ConfigurationManager configurationManager;
    private MessagesManager messagesManager;
    private WhitelistManager whitelistManager;
    private PanelAPIClient apiClient;
    private RateLimitTracker rateLimitTracker;
    private UpdateService updateService;
    private PlayerConnectionHandler playerConnectionHandler;
    private ServerLifecycleManager serverLifecycleManager;
    private ServerSwitchListener serverSwitchListener;

    private FilteredComponentLogger filteredLogger;

    // Shared State (managed here, passed to handlers/managers)
    private Map<String, PteroServerInfo> serverInfoMap = new ConcurrentHashMap<>();
    private final Set<String> startingServers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> playerCooldowns = new ConcurrentHashMap<>();

    /**
     * Constructor called by Velocity's plugin loader.
     */
    @Inject
    public VelocityPteroPower(
        ProxyServer proxy,
        @DataDirectory Path dataDirectory,
        CommandManager commandManager,
        ComponentLogger logger,
        Metrics.Factory metricsFactory
    ) {
        this.proxyServer = proxy;
        this.originalLogger = logger;
        this.dataDirectory = dataDirectory;
        this.commandManager = commandManager;
        this.metricsFactory = metricsFactory;

        this.filteredLogger = new FilteredComponentLogger(this.originalLogger, Level.INFO);
    }

    /**
     * Handles plugin initialization logic when the proxy starts.
     */
    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        logStartupBanner();

        // 1. Initialize Config and Messages First
        this.configurationManager = new ConfigurationManager(this);
        configurationManager.loadConfig();
        this.serverInfoMap = configurationManager.getServerInfoMap();
        this.updateLoggerLevel();

        this.whitelistManager = new WhitelistManager(proxyServer, this);

        this.messagesManager = new MessagesManager(this);
        messagesManager.loadMessages();

        // 3. Initialize Core Components
        this.rateLimitTracker = new RateLimitTracker(filteredLogger, configurationManager);
        this.updateService = new UpdateService(filteredLogger, configurationManager, VERSION, MODRINTH_PROJECT_ID);
        initializeApiClient(); // Sets up apiClient
        if (this.apiClient == null) {
            filteredLogger.error("Failed to initialize Panel API Client. Plugin disabled.");
            return;
        }
        if (!configurationManager.getPanelType().equals(PanelType.mcServerSoft)) {
            whitelistManager.initialize();
        }else {
            filteredLogger.warn("Mc Server Soft does not support whitelist fetching... disabling whitelist checking...");
        }
        // 4. Initialize Handlers/Managers/Listeners that depend on core components
        this.serverLifecycleManager = new ServerLifecycleManager(proxyServer,this);
        this.playerConnectionHandler = new PlayerConnectionHandler(proxyServer, this);
        this.serverSwitchListener = new ServerSwitchListener(this, serverLifecycleManager);

        // 5. Register Commands and Listeners
        commandManager.register(
            commandManager.metaBuilder("ptero").aliases("vpp").build(),
            new PteroCommand(this)
        );
        proxyServer.getEventManager().register(this, playerConnectionHandler);
        proxyServer.getEventManager().register(this, serverSwitchListener);

        // 6. Enable bStats Metrics
        metricsFactory.make(this, BSTATS_PLUGIN_ID);

        // 7. Check for Updates
        updateService.performUpdateCheck();

        filteredLogger.info("VelocityPteroPower v{} successfully loaded.", VERSION);
    }

    /**
     * Handles plugin shutdown logic.
     */
    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (apiClient != null) {
            apiClient.shutdown();
        }
        // Potentially add shutdown logic for other components if needed
        filteredLogger.info("Shutting down VelocityPteroPower... Goodbye!");
    }

    /** Reloads the plugin's configuration and messages, and updates relevant state. */
    public void reload() {
        filteredLogger.info("Reloading VelocityPteroPower configuration...");

        // Reload core config/messages
        configurationManager.loadConfig();
        this.updateLoggerLevel();
        messagesManager.loadMessages();
        whitelistManager.initialize();

        // Update server info map reference (important for handlers/managers using it)
        this.serverInfoMap = configurationManager.getServerInfoMap();
        // Re-initialize API client if panel type or credentials changed
        PanelType oldType = (apiClient instanceof PelicanAPIClient) ? PanelType.pelican : PanelType.pterodactyl;
        PanelType newType = configurationManager.getPanelType();
        String oldKey = apiClient != null ? configurationManager.getPterodactylApiKey() : ""; // Get key before reload potentially changes it
        String newKey = configurationManager.getPterodactylApiKey(); // Key after reload

        // Only re-init if type or key actually changed
        if (apiClient == null || oldType != newType || !oldKey.equals(newKey)) {
             filteredLogger.info("API client configuration changed. Re-initializing...");
             if (apiClient != null) {
                 apiClient.shutdown(); // Shutdown old client
             }
             initializeApiClient();
             if (apiClient == null) {
                 filteredLogger.error("Failed to re-initialize Panel API Client after reload. Plugin will not function correctly.");
             }
             // Update API client reference in components that use it
             if (serverLifecycleManager != null) {
                 filteredLogger.warn("API Client re-initialized. Dependent components might need restarting or updating.");
             }
             if (playerConnectionHandler != null) {
                  filteredLogger.warn("API Client re-initialized. Dependent components might need restarting or updating.");
             }

        } else {
             filteredLogger.info("API client configuration unchanged.");
        }


        filteredLogger.info("VelocityPteroPower configuration reloaded.");
    }

   public void updateLoggerLevel() {
        org.slf4j.event.Level configLevel = configurationManager.getLoggerLevel();
        filteredLogger.setLevel(configLevel); // Use your wrapper's setLevel method
        // The setLevel method in FilteredComponentLogger will log the change
    }

    /** Initializes the appropriate PanelAPIClient based on configuration. */
    private void initializeApiClient() {
        PanelType type = configurationManager.getPanelType();
        filteredLogger.info("Initializing API client for panel type: {}", type);

        switch (type) {
            case pterodactyl:
                filteredLogger.debug("Detected Pterodactyl Panel, creating api client...");
                this.apiClient = new PterodactylAPIClient(this);
                break;
            case pelican:
                filteredLogger.debug("Detected Pelican Panel, creating api client...");
                this.apiClient = new PelicanAPIClient(this);
                break;
            case mcServerSoft:
                filteredLogger.debug("Detected Mc Server Soft Panel, creating api client...");
                this.apiClient = new McServerSoftApiClient(this);
                break;
            default:
                filteredLogger.debug("No Panel type specified. Defaulting to pterodactyl Api Client...");
                this.apiClient = new PterodactylAPIClient(this);
                break;
        }
            if (!apiClient.isApiKeyValid(configurationManager.getPterodactylApiKey())) {
                 logInvalidApiKeyError();
                 this.apiClient = null;
            }
    }

    /** Logs the plugin's startup banner. */
    private void logStartupBanner() {
        MiniMessage mm = MiniMessage.miniMessage();
        filteredLogger.info(mm.deserialize("<#4287f5>____   ________________________"));
        filteredLogger.info(mm.deserialize("<#4287f5>\\   \\ /   /\\______   \\______   \\"));
        filteredLogger.info(mm.deserialize("<#4287f5> \\   Y   /  |     ___/|     ___/"));
        filteredLogger.info(mm.deserialize("<#4287f5>  \\     /   |    |    |    |"+ "<#00ff77>         VelocityPteroPower <#6b6c6e>v" + VERSION));
        filteredLogger.info(mm.deserialize("<#4287f5>   \\___/    |____|tero|____|ower" + "<#A9A9A9>     Running on Velocity"));
    }

    /** Logs a detailed error message when the API key is invalid. */
    private void logInvalidApiKeyError() {
         filteredLogger.error("=================================================");
         filteredLogger.error(" VelocityPteroPower Initialization Failed!");
         filteredLogger.error(" ");
         filteredLogger.error(" No valid API key found or configured in config.yml.");
         filteredLogger.error(" Please ensure 'pterodactyl.apiKey' is set correctly.");
         filteredLogger.error(" Key should start with 'ptlc_' (Client) or 'plcn_' (Pelican).");
         filteredLogger.error(" Application API keys ('ptla_') are NOT supported.");
         filteredLogger.error(" ");
         filteredLogger.error(" Plugin will be disabled.");
         filteredLogger.error("=================================================");
    }

    /**
     * Gets the formatted plugin prefix for messages.
     * Example: "[VPP] "
     *
     * @return The prefix component.
     */
    public Component getPluginPrefix() {
        TextColor prefixColor = TextColor.color(66, 135, 245); // Blueish
        return Component.text("[", NamedTextColor.GRAY)
            .append(Component.text(messagesManager.getMessage("prefix"), prefixColor))
            .append(Component.text("] ", NamedTextColor.GRAY));
    }

    // --- Getters for Core Components and State ---
    // These allow other classes (like commands) to access necessary parts

    public ProxyServer getProxyServer() {
        return proxyServer;
    }

    public FilteredComponentLogger getFilteredLogger() {
        return filteredLogger;
    }

    public Path getDataDirectory() {
        return dataDirectory;
    }

    public ConfigurationManager getConfigurationManager() {
        return configurationManager;
    }

    public MessagesManager getMessagesManager() {
        return messagesManager;
    }

    public WhitelistManager getWhitelistManager() {
        return whitelistManager;
    }

    public PanelAPIClient getApiClient() {
        return apiClient;
    }

    public RateLimitTracker getRateLimitTracker() {
        return rateLimitTracker;
    }

     public ServerLifecycleManager getServerLifecycleManager() {
        return serverLifecycleManager;
    }

    public Map<String, PteroServerInfo> getServerInfoMap() {
        // Return the live map so handlers see updates after reload
        return serverInfoMap;
    }

    public Set<String> getStartingServers() {
        return startingServers; // Used by PlayerConnectionHandler
    }

    public Map<UUID, Long> getPlayerCooldowns() {
        return playerCooldowns; // Used by PlayerConnectionHandler
    }
}
