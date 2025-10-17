package de.tubyoub.velocitypteropower.service;

/**
 * Reasons why a player is currently on a limbo server.
 */
public enum LimboReason {
    /**
     * Plugin redirected the player to limbo while their requested server is starting up.
     */
    SERVER_START_WAIT,

    /**
     * The player switched to a limbo server by themselves (manual move).
     */
    SELF_MOVE,

    /**
     * Any other plugin/external reason.
     */
    OTHER
}
