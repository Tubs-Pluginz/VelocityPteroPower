package de.tubyoub.vpp.api;

import java.util.List;

/**
 * Represents a subcommand that can be registered under /ptero.
 */
public interface AddonCommand {
    /**
     * Primary name of the subcommand (lower-case, no spaces).
     */
    String name();

    /**
     * Optional aliases for the subcommand (lower-case).
     */
    default List<String> aliases() { return List.of(); }

    /**
     * A one-line description for help output.
     */
    String description();

    /**
     * The permission node required to run this command. Return null to require no permission.
     */
    default String permission() { return null; }

    /**
     * Execute the command. Args exclude the subcommand token itself.
     */
    void execute(CommandActor actor, String[] args);

    /**
     * Tab-completion suggestions for the next argument.
     */
    default List<String> suggest(CommandActor actor, String[] args) { return List.of(); }
}
