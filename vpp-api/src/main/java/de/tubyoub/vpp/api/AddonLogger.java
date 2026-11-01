package de.tubyoub.vpp.api;

/**
 * Simple logger facade for addons (keeps the public API free of SLF4J/Velocity types).
 */
public interface AddonLogger {
    void info(String message);
    void warn(String message);
    void error(String message);
    void error(String message, Throwable throwable);
    void debug(String message);
}
