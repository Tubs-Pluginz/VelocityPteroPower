package de.tubyoub.velocitypteropower.addon;

import de.tubyoub.vpp.api.AddonLogger;
import org.slf4j.Logger;

public final class Slf4jAddonLogger implements AddonLogger {
    private final Logger logger;
    private final String prefix;

    public Slf4jAddonLogger(Logger logger, String addonId) {
        this.logger = logger;
        this.prefix = "[Addon:" + addonId + "] ";
    }

    @Override public void info(String message) { logger.info(prefix + message); }
    @Override public void warn(String message) { logger.warn(prefix + message); }
    @Override public void error(String message) { logger.error(prefix + message); }
    @Override public void error(String message, Throwable throwable) { logger.error(prefix + message, throwable); }
    @Override public void debug(String message) { logger.debug(prefix + message); }
}
