/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.service;

import de.tubyoub.velocitypteropower.manager.ConfigurationManager;
import de.tubyoub.velocitypteropower.util.VersionChecker;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

/**
 * Handles checking for plugin updates using the Modrinth API.
 */
public class UpdateService {

    private final ComponentLogger logger;
    private final ConfigurationManager configurationManager;
    private final String currentVersion;
    private final String projectId;
    private VersionChecker.VersionInfo lastCheckedVersionInfo = null;

    /**
     * Constructor for UpdateService.
     * @param logger The plugin's logger.
     * @param configurationManager The plugin's configuration manager.
     * @param currentVersion The current version of the plugin.
     * @param projectId The Modrinth project ID.
     */
    public UpdateService(ComponentLogger logger, ConfigurationManager configurationManager, String currentVersion, String projectId) {
        this.logger = logger;
        this.configurationManager = configurationManager;
        this.currentVersion = currentVersion;
        this.projectId = projectId;
    }

    /**
     * Performs the update check using VersionChecker if enabled in config.
     * Logs messages based on the result.
     */
    public void performUpdateCheck() {
        if (configurationManager.isCheckUpdate()) {
            logger.info("Checking for VelocityPteroPower updates...");
            try {
                VersionChecker.VersionInfo info = VersionChecker.isNewVersionAvailable(currentVersion, projectId);
                this.lastCheckedVersionInfo = info; // Store for potential future use

                if (info.isNewVersionAvailable) {
                    logUpdateAvailable(info);
                } else {
                    logger.info("You are running the latest version of VelocityPteroPower ({}).", currentVersion);
                }
            } catch (Exception e) {
                 logger.warn("Failed to check for updates: {}", e.getMessage(), e); // Log exception details
            }
        } else {
            logger.info("Automatic update checking is disabled in the configuration.");
        }
    }

    /**
     * Logs messages about an available update based on its urgency.
     * @param info The version information obtained from the check.
     */
    private void logUpdateAvailable(VersionChecker.VersionInfo info) {
        String updateUrl = "https://modrinth.com/plugin/velocitypteropower/version/" + info.latestVersion;
        switch (info.urgency) {
            case CRITICAL:
            case HIGH:
                logger.warn("!---------------- Important Update Available ----------------!");
                logger.warn("A new critical/high importance update for VelocityPteroPower is available: v{}", info.latestVersion);
                logger.warn("This update may contain important security fixes or major improvements.");
                logger.warn("Please update AS SOON AS POSSIBLE: {}", updateUrl);
                logger.warn("Remember to back up your configuration files before updating.");
                logger.warn("!------------------------------------------------------------!");
                break;
            case NORMAL:
            case LOW:
            case NONE:
            default:
                logger.info("---------------- Update Available ----------------");
                logger.info("A new version of VelocityPteroPower is available: v{}", info.latestVersion);
                logger.info("Download it here: {}", updateUrl);
                logger.info("Remember to back up your configuration files before updating.");
                logger.info("--------------------------------------------------");
                break;
        }
        // Optionally log changelog if not too long
        if (info.changelog != null && !info.changelog.isBlank() && info.changelog.length() < 500) {
             logger.info("Changelog snippet:\n{}", info.changelog);
        }
    }

    /**
     * Gets the last checked version information.
     * @return The VersionInfo object, or null if no check has been performed.
     */
    public VersionChecker.VersionInfo getLastCheckedVersionInfo() {
        return lastCheckedVersionInfo;
    }
}
