package de.tubyoub.vpp.api;

import de.tubyoub.vpp.api.event.VPPEventBus;

import java.util.Collection;
//Test change for Workflow.
/**
 * Public entry point for third-party integrations with VelocityPteroPower.
 *
 * This API intentionally does not expose Velocity types. Implementations
 * are expected to run on a Velocity server and can access Velocity on their own.
 */
public interface VPPApi {
    /** Current public API version string (semantic). */
    String getApiVersion();

    /**
     * Register a new addon subcommand under /ptero.
     * The command name must be unique and lower-case.
     */
    void registerCommand(AddonCommand command);

    /**
     * Unregister a previously registered addon subcommand by its primary name.
     */
    void unregisterCommand(String name);

    /**
     * Try to execute a registered subcommand by name.
     * @return true if a matching command was found and executed
     */
    boolean dispatchSubcommand(String name, CommandActor actor, String[] args);

    /**
     * Get all currently registered addon commands.
     */
    Collection<AddonCommand> getRegisteredCommands();

    /**
     * Access the event bus to subscribe to and post VPP events.
     */
    VPPEventBus getEventBus();

    /**
     * Facade for making custom HTTP calls to the configured panel (Pterodactyl, Pelican, McServerSoft).
     * Implementations will apply base URL and Authorization automatically.
     */
    PanelHttpFacade getPanelHttp();

    /**
     * Control facade to start/stop servers through VPP safely.
     */
    ServerControl getServerControl();

    /**
     * Read-only registry of managed servers.
     */
    ServerRegistry getServerRegistry();

    /**
     * Obtain or create a separate YAML configuration for your addon under VPP's data directory.
     * The file is stored separately from the main config, and does not expose sensitive values.
     */
    AddonConfig getAddonConfig(String addonId);

    // Routing SPI
    void registerRoutingProvider(de.tubyoub.vpp.api.routing.RoutingProvider provider, int priority);
    void unregisterRoutingProvider(de.tubyoub.vpp.api.routing.RoutingProvider provider);
    boolean hasRoutingProvider();
    /**
     * Ask registered routing providers to select a route. Returns true if any provider handled it.
     */
    boolean selectRoute(de.tubyoub.vpp.api.routing.PlayerRouteContext ctx);
}
