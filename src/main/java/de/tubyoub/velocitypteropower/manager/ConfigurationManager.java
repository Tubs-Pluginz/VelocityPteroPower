/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.manager;

import de.tubyoub.velocitypteropower.model.PteroServerInfo;
import de.tubyoub.velocitypteropower.VelocityPteroPower;
import de.tubyoub.velocitypteropower.api.PanelType;
import dev.dejvokep.boostedyaml.YamlDocument;
import dev.dejvokep.boostedyaml.block.implementation.Section;
import dev.dejvokep.boostedyaml.dvs.versioning.BasicVersioning;
import dev.dejvokep.boostedyaml.route.Route;
import dev.dejvokep.boostedyaml.settings.dumper.DumperSettings;
import dev.dejvokep.boostedyaml.settings.general.GeneralSettings;
import dev.dejvokep.boostedyaml.settings.loader.LoaderSettings;
import dev.dejvokep.boostedyaml.settings.updater.MergeRule;
import dev.dejvokep.boostedyaml.settings.updater.UpdaterSettings;
import org.slf4j.Logger;
import org.slf4j.event.Level;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * This class manages the configuration for the VelocityPteroPower plugin.
 * It loads the configuration from a YAML file and provides methods to access the configuration values.
 */

public class ConfigurationManager {

    public enum ServerCheckMethod {
        VELOCITY_PING,
        PANEL_API
    }

    private Path dataDirectory;
    private YamlDocument config;
    private String panelUrl;
    private String apiKey;
    private PanelType panel;
    private boolean checkUpdate;
    private boolean printRateLimit;
    private boolean serverNotFoundMessage;
    private boolean whitelistAllowBypass;
    private String languageOverride;
    private int loggerLevel;
    private int apiThreads;
    private int pingTimeout;
    private int shutdownRetryDelay;
    private int shutdownRetries;
    private int idleStartShutdownTime;
    private int playerCommandCooldown;
    private int startupInitialCheckDelay;
    private int whitelistCheckInterval;
    private ServerCheckMethod serverCheckMethod;
    private List<String> stopAllIgnoreList;
    private int maxOnlineServers;
    private boolean maxOnlineAllowBypass;
    private List<String> maxOnlineExemptList;
    private boolean countLobbiesInMaxOnline;
    private boolean countLimbosInMaxOnline;
    private List<String> alwaysOnlineList;
    private int alwaysOnlineCheckInterval;
    private int resourceCacheSeconds;
    private boolean resourcePrefetchEnabled;
    private int idleShutdownCheckInterval;

    // Lobby/Limbo balancing configuration
    private List<String> balancerLobbies;
    private List<String> balancerLimbos;
    private int balancerLobbiesToUse;
    private String balancerStrategyName;
    private int balancerHealthCheckInterval;
    private boolean balancerAutoScaleEnabled;
    private int balancerMinOnline;
    private int balancerMaxOnline;
    private int balancerPlayersPerServer;
    private double balancerCpuScaleUpThreshold;
    private int balancerPreStartThresholdPercent;
    private int balancerStartFailureFallbackSeconds;
    private int balancerStartFailureCooldownSeconds;

    private final VelocityPteroPower plugin;
    private final Logger logger;
    private Map<String, PteroServerInfo> serverInfoMap;

     /**
     * Constructor for the ConfigurationManager class.
     *
     * @param plugin the VelocityPteroPower plugin instance
     */
    public ConfigurationManager(VelocityPteroPower plugin) {
        this.plugin = plugin;
        this.logger = plugin.getFilteredLogger();
        this.dataDirectory = plugin.getDataDirectory();
    }

    /**
     * This method loads the configuration from a YAML file.
     * It reads the configuration values and stores them in instance variables.
     */
    public void loadConfig(){
        try{
            config = YamlDocument.create(new File(this.dataDirectory.toFile(), "config.yml"),
                                Objects.requireNonNull(getClass().getResourceAsStream("/config.yml")),
                                GeneralSettings.DEFAULT,
                                LoaderSettings.builder().setAutoUpdate(true).build(),
                                DumperSettings.DEFAULT,
                                UpdaterSettings.builder().setVersioning(new BasicVersioning("fileversion"))
                                        .setOptionSorting(UpdaterSettings.OptionSorting.SORT_BY_DEFAULTS)
                                        .setMergeRule(MergeRule.MAPPINGS, true)
                                        .setMergeRule(MergeRule.MAPPING_AT_SECTION, false)
                                        .setMergeRule(MergeRule.SECTION_AT_MAPPING, false)
                                        .addIgnoredRoute("5", "servers", '.')
                                        .addIgnoredRoute("6", "servers", '.')
                                        .addIgnoredRoute("7", "servers", '.')
                                        .addIgnoredRoute("8", "servers", '.')
                                        .addIgnoredRoute("9", "servers", '.')
                                        .addIgnoredRoute("10", "servers", '.')
                                        .addIgnoredRoute("11", "servers", '.')
                                        .build());


            checkUpdate = (boolean) config.get("checkUpdate", true);
            printRateLimit = (boolean) config.get("printRateLimit", false);
            serverNotFoundMessage = (boolean) config.get("serverNotFoundMessage", false);
            whitelistAllowBypass = (boolean) config.get("whitelistAllowBypass", true);
            languageOverride = config.getString("languageOverride", "auto");

            loggerLevel = (int) config.get("loggerLevel", 20);
            pingTimeout = (int) config.get("pingTimeout", 1000);
            apiThreads = (int) config.get("apiThreads", 10);
            shutdownRetryDelay = (int) config.get("shutdownRetryDelay", 30);
            shutdownRetries = (int) config.get("shutdownRetries", 3);
            idleStartShutdownTime = (int) config.get("idleStartShutdownTime", 300);
            playerCommandCooldown = (int) config.get("playerCommandCooldown", 10);
            startupInitialCheckDelay = (int) config.get("startupInitialCheckDelay", 10);
            whitelistCheckInterval = (int) config.get("whitelistCheckInterval", 10);
            maxOnlineServers = (int) config.get("maxOnlineServers", 0);
            maxOnlineAllowBypass = (boolean) config.get("maxOnlineAllowBypass", true);
            maxOnlineExemptList = config.getStringList("maxOnlineExempt");
            countLobbiesInMaxOnline = (boolean) config.get("countLobbiesInMaxOnline", false);
            countLimbosInMaxOnline = (boolean) config.get("countLimbosInMaxOnline", false);
            alwaysOnlineList = config.getStringList("alwaysOnline");
            alwaysOnlineCheckInterval = (int) config.get("alwaysOnlineCheckInterval", 60);
            resourceCacheSeconds = (int) config.get("resourceCacheSeconds", 10);
            resourcePrefetchEnabled = (boolean) config.get("resourcePrefetchEnabled", true);
            idleShutdownCheckInterval = (int) config.get("idleShutdownCheckInterval", 60);

            // Lobby/Limbo balancer section (all optional, sensible defaults)
            Section lb = config.getSection("lobbyBalancer");
            if (lb != null) {
                balancerLobbies = lb.getStringList("lobbies");
                balancerLimbos = lb.getStringList("limbos");
                balancerLobbiesToUse = lb.getInt("lobbiesToUse", 0);
                String strat = lb.getString("strategy", "ROUND_ROBIN");
                balancerStrategyName = strat != null ? strat : "ROUND_ROBIN";
                balancerHealthCheckInterval = lb.getInt("healthCheckInterval", 15);
                balancerAutoScaleEnabled = lb.getBoolean("autoScaleEnabled", true);
                balancerMinOnline = lb.getInt("minOnline", 1);
                balancerMaxOnline = lb.getInt("maxOnline", 0);
                balancerPlayersPerServer = lb.getInt("playersPerServer", 80);
                try {
                    balancerCpuScaleUpThreshold = lb.getDouble("cpuScaleUpThreshold", 85.0);
                } catch (Exception ex) {
                    balancerCpuScaleUpThreshold = 85.0;
                }
                balancerPreStartThresholdPercent = lb.getInt("preStartThresholdPercent", 80);
                balancerStartFailureFallbackSeconds = lb.getInt("startFailureFallbackSeconds", 60);
                balancerStartFailureCooldownSeconds = lb.getInt("startFailureCooldownSeconds", 120);
            } else {
                // defaults
                balancerLobbies = Collections.emptyList();
                balancerLimbos = Collections.emptyList();
                balancerLobbiesToUse = 0;
                balancerStrategyName = "ROUND_ROBIN";
                balancerHealthCheckInterval = 15;
                balancerAutoScaleEnabled = true;
                balancerMinOnline = 1;
                balancerMaxOnline = 0;
                balancerPlayersPerServer = 80;
                balancerCpuScaleUpThreshold = 85.0;
                balancerPreStartThresholdPercent = 80;
                balancerStartFailureFallbackSeconds = 60;
                balancerStartFailureCooldownSeconds = 120;
            }

            // Migrate legacy 'limboServer' to 'lobbyBalancer.limbos'
            try {
                String legacyLimbo = config.getString("limboServer");
                if (legacyLimbo != null && !legacyLimbo.isBlank() && !"changeMe".equalsIgnoreCase(legacyLimbo)) {
                    Section lbSec = config.getSection("lobbyBalancer");
                    if (lbSec == null) {
                        config.set("lobbyBalancer.limbos", java.util.List.of(legacyLimbo));
                    } else {
                        java.util.List<String> limbos = lbSec.getStringList("limbos");
                        if (limbos == null) limbos = new java.util.ArrayList<>();
                        boolean exists = false;
                        for (String s : limbos) { if (s.equalsIgnoreCase(legacyLimbo)) { exists = true; break; } }
                        if (!exists) limbos.add(0, legacyLimbo);
                        config.set("lobbyBalancer.limbos", limbos);
                    }
                    config.remove(Route.fromString("limboServer"));
                    config.save();
                    logger.info("Migrated legacy 'limboServer' to 'lobbyBalancer.limbos' and removed the old key.");
                }
            } catch (Exception ignored) {}

            stopAllIgnoreList = config.getStringList("stopIdleIgnore");

            String checkMethodStr = config.getString("serverStatusCheckMethod", "VELOCITY_PING");
            try {
                this.serverCheckMethod = ServerCheckMethod.valueOf(checkMethodStr.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                logger.error("Invalid serverStatusCheckMethod '{}' in config. Using default 'VELOCITY_PING'.", checkMethodStr);
                this.serverCheckMethod = ServerCheckMethod.VELOCITY_PING;
            }

            Section pterodactylSection = config.getSection("pterodactyl");
            Map<String, Object> pterodactyl = new HashMap<>();
            if (pterodactylSection != null) {
                for (Object keyObj : pterodactylSection.getKeys()) {
                    String key = (String) keyObj;
                    Route route = Route.fromString(key);
                    Object value = pterodactylSection.get(route);
                    pterodactyl.put(key, value);
                }
            }
            panelUrl = (String) pterodactyl.get("url");
            if (!panelUrl.endsWith("/")) {
                panelUrl += "/";
            }
            apiKey = (String) pterodactyl.get("apiKey");
            panel = detectPanelType(apiKey);


            Section serversSection = config.getSection("servers");
                if (serversSection != null) {
                    serverInfoMap = processServerSection(serversSection);
                } else {
                    logger.error("Servers section not found in configuration.");
                }
                } catch (IOException e) {
                    logger.error("Error creating/loading configuration: " + e.getMessage());
                }
            }

    /**
     * This method processes the server section of the configuration.
     * It creates a map of server names to PteroServerInfo objects.
     *
     * @param serversSection the server section of the configuration
     * @return a map of server names to PteroServerInfo objects
     */
    public Map<String, PteroServerInfo> processServerSection(Section serversSection) {
            Map<String, PteroServerInfo> serverInfoMap = new HashMap<>();
            for (Object keyObj : serversSection.getKeys()) {
                String key = (String) keyObj;
                Route route = Route.fromString(key);
                Object serverInfoDataObj = serversSection.get(route);
                if (serverInfoDataObj instanceof Section) {
                    Section serverInfoDataSection = (Section) serverInfoDataObj;
                    try {
                        Object idObj = serverInfoDataSection.get("id");
                        if (idObj == null) {
                            throw new IllegalArgumentException("Missing 'id' for server '" + key + "'");
                        }

                        if (!(idObj instanceof String)) {
                            // YAML likely parsed an unquoted ID (e.g., 91e62747) as a number (scientific notation),
                            // which can overflow to Infinity. We cannot recover the original text.
                            throw new IllegalArgumentException("Invalid type for 'id' (" + idObj.getClass().getSimpleName() + ") at path 'servers." + key + ".id'. YAML parsed an unquoted value as a number. Edit your config and quote the ID, e.g.: id: \"91e62747\" then restart/reload.");
                        }

                        String id = ((String) idObj).trim();
                        if (id.equalsIgnoreCase("infinity") || id.equalsIgnoreCase("nan") || id.isEmpty()) {
                            throw new IllegalArgumentException("Invalid 'id' value '" + id + "' for server '" + key + "'. Quote the ID in YAML, e.g.: id: \"91e62747\"");
                        }

                        // Basic sanity check: IDs are typically short alphanumeric (pterodactyl short uuid)
                        if (!id.matches("^[A-Za-z0-9_-]{4,64}$")) {
                            logger.warn("Suspicious server id '{}' for server '{}'. Expected alphanumeric/underscore/dash. Continuing anyway.", id, key);
                        }

                        if (!Objects.equals(id, "1234abcd")){
                            int timeout = serverInfoDataSection.getInt("timeout", -1);
                            int startupJoinDelay = serverInfoDataSection.getInt("startupJoinDelay", 10);
                            boolean whitelist = serverInfoDataSection.getBoolean("whitelist", false);
                            serverInfoMap.put(key, new PteroServerInfo(id, timeout, startupJoinDelay, whitelist));
                            logger.info("Registered Server: " + id + " successfully");
                        }
                    } catch (Exception e) {
                        logger.warn("Error processing server '" + key + "': " + e.getMessage());
                    }
                }
            }
            return serverInfoMap;
        }

    private PanelType detectPanelType(String apiKey) {
        if (apiKey == null || apiKey.isEmpty()) {
          return PanelType.pterodactyl;
        }

        String prefix = apiKey.split("_")[0];
        return switch (prefix) {
            case "ptlc" -> {
                logger.debug("Detected pterodactyl panel from apiKey");
                yield PanelType.pterodactyl;
            }
            case "plcn", "pacc"-> {
                logger.debug("Detected pelican panel from apiKey");
                yield PanelType.pelican;
            }
            case "ptla", "peli" -> {
                logger.debug("Detected Application Api Key please change this to a client key");
                yield PanelType.error;
            }
            default -> {
                logger.info("API Key has no recognized prefix, assuming mcServerSoft.");
                yield PanelType.mcServerSoft;
            }
        };
    }

    /**
     * This method returns the map of server names to PteroServerInfo objects.
     *
     * @return the map of server names to PteroServerInfo objects
     */
    public Map<String, PteroServerInfo> getServerInfoMap() {
        return serverInfoMap;
    }

    // Balancer getters
    public List<String> getBalancerLobbies() { return balancerLobbies == null ? Collections.emptyList() : balancerLobbies; }
    public List<String> getBalancerLimbos() { return balancerLimbos == null ? Collections.emptyList() : balancerLimbos; }
    public int getBalancerLobbiesToUse() { return balancerLobbiesToUse; }
    public String getBalancerStrategyName() { return balancerStrategyName == null ? "ROUND_ROBIN" : balancerStrategyName; }
    public int getBalancerHealthCheckInterval() { return balancerHealthCheckInterval <= 0 ? 15 : balancerHealthCheckInterval; }
    public boolean isBalancerAutoScaleEnabled() { return balancerAutoScaleEnabled; }
    public int getBalancerMinOnline() { return Math.max(0, balancerMinOnline); }
    public int getBalancerMaxOnline() { return Math.max(0, balancerMaxOnline); }
    public int getBalancerPlayersPerServer() { return Math.max(1, balancerPlayersPerServer); }
    public double getBalancerCpuScaleUpThreshold() { return balancerCpuScaleUpThreshold <= 0 ? 85.0 : balancerCpuScaleUpThreshold; }
    public int getBalancerPreStartThresholdPercent() { return balancerPreStartThresholdPercent <= 0 ? 80 : Math.min(100, balancerPreStartThresholdPercent); }
    public int getBalancerStartFailureFallbackSeconds() { return balancerStartFailureFallbackSeconds <= 0 ? 60 : balancerStartFailureFallbackSeconds; }
    public int getBalancerStartFailureCooldownSeconds() { return balancerStartFailureCooldownSeconds <= 0 ? 120 : balancerStartFailureCooldownSeconds; }

    /**
     * This method returns the Pterodactyl URL.
     *
     * @return the Pterodactyl URL
     */
    public String getPterodactylUrl() {
        return panelUrl;
    }

    /**
     * This method returns the Pterodactyl API key.
     *
     * @return the Pterodactyl API key
     */
    public String getPterodactylApiKey() {
        return apiKey;
    }

    /**
     * This method returns whether to check for updates.
     *
     * @return true if updates should be checked, false otherwise
     */
    public boolean isCheckUpdate() {
        return checkUpdate;
    }

    public boolean isServerNotFoundMessage() {
        return serverNotFoundMessage;
    }

    public boolean isWhitelistAllowBypass() {
        return whitelistAllowBypass;
    }

    public PanelType getPanelType(){
        return panel;
    }

    public Level getLoggerLevel() {
        try {
            return Level.intToLevel(loggerLevel);
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid logger level: {}. Defaulting to INFO.", loggerLevel);
            return Level.INFO;
        }
    }

    public int getApiThreads() {
        return apiThreads;
    }

    public boolean isPrintRateLimit() {
        return printRateLimit;
    }

    public String getLanguageOverride() {
        return languageOverride;
    }

    public int getPingTimeout() {
        return pingTimeout;
    }

    public int getShutdownRetries() {
        return shutdownRetries;
    }

    public int getShutdownRetryDelay(){
        return shutdownRetryDelay;
    }

    public int getPlayerCommandCooldown() {
        return playerCommandCooldown;
    }
    public int getIdleStartShutdownTime(){
        return idleStartShutdownTime;
    }

    public  int getStartupInitialCheckDelay(){
        return startupInitialCheckDelay;
    }

    public int getWhitelistCheckInterval(){
        return whitelistCheckInterval;
    }


    public List<String> getStopAllIgnoreList() {
        return stopAllIgnoreList;
    }

    public ServerCheckMethod getServerCheckMethod() {
        return serverCheckMethod;
    }
    
    public int getMaxOnlineServers() {
        return maxOnlineServers;
    }

    public boolean isMaxOnlineAllowBypass() {
        return maxOnlineAllowBypass;
    }

    public java.util.List<String> getMaxOnlineExemptList() {
        return maxOnlineExemptList == null ? java.util.Collections.emptyList() : maxOnlineExemptList;
    }

    public boolean isCountLobbiesInMaxOnline() { return countLobbiesInMaxOnline; }
    public boolean isCountLimbosInMaxOnline() { return countLimbosInMaxOnline; }

    public java.util.List<String> getAlwaysOnlineList() {
        return alwaysOnlineList == null ? java.util.Collections.emptyList() : alwaysOnlineList;
    }

    public int getAlwaysOnlineCheckInterval() {
        return alwaysOnlineCheckInterval;
    }

    public int getResourceCacheSeconds() {
        return Math.max(0, resourceCacheSeconds);
    }

    public boolean isResourcePrefetchEnabled() {
        return resourcePrefetchEnabled;
    }

    public int getIdleShutdownCheckInterval() {
        return Math.max(0, idleShutdownCheckInterval);
    }
}