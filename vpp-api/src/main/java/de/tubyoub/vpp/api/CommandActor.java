package de.tubyoub.vpp.api;

/**
 * Minimal abstraction of a command source to avoid exposing Velocity types.
 */
public interface CommandActor {
    boolean hasPermission(String permission);
    void sendMessage(String message);
    String getName();
}
