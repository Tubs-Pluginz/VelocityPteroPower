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
    private String limboServer;
    private PanelType panel;
    private boolean checkUpdate;
    private boolean printRateLimit;
    private boolean serverNotFoundMessage;
    private boolean whitelistAllowBypass;
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
                                        .build());


            checkUpdate = (boolean) config.get("checkUpdate", true);
            printRateLimit = (boolean) config.get("printRateLimit", false);
            serverNotFoundMessage = (boolean) config.get("serverNotFoundMessage", false);
            whitelistAllowBypass = (boolean) config.get("whitelistAllowBypass", true);

            loggerLevel = (int) config.get("loggerLevel", 20);
            pingTimeout = (int) config.get("pingTimeout", 1000);
            apiThreads = (int) config.get("apiThreads", 10);
            shutdownRetryDelay = (int) config.get("shutdownRetryDelay", 30);
            shutdownRetries = (int) config.get("shutdownRetries", 3);
            idleStartShutdownTime = (int) config.get("idleStartShutdownTime", 300);
            playerCommandCooldown = (int) config.get("playerCommandCooldown", 10);
            startupInitialCheckDelay = (int) config.get("startupInitialCheckDelay", 10);
            whitelistCheckInterval = (int) config.get("whitelistCheckInterval", 10);

            limboServer = (String) config.get("limboServer", "changeMe");
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
                    Map<String, Object> serverInfoData = new HashMap<>();
                    for (Object dataKeyObj : serverInfoDataSection.getKeys()) {
                        String dataKey = (String) dataKeyObj;
                        Route dataRoute = Route.fromString(dataKey);
                        Object value = serverInfoDataSection.get(dataRoute);
                        serverInfoData.put(dataKey, value);
                    }
                    try {
                        String id = (String) serverInfoData.get("id");
                        if (!Objects.equals(id, "1234abcd")){
                            int timeout = (int) serverInfoData.getOrDefault("timeout", -1);
                            int startupJoinDelay = (int) serverInfoData.getOrDefault("startupJoinDelay", 10);
                            boolean whitelist = (boolean) serverInfoData.getOrDefault("whitelist", false);
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
            case "plcn" -> {
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

    public String getLimboServerName() {
        if (limboServer == "changeMe") {
            return null;
        }
        return limboServer;
    }

    public List<String> getStopAllIgnoreList() {
        return stopAllIgnoreList;
    }

    public ServerCheckMethod getServerCheckMethod() {
        return serverCheckMethod;
    }

}