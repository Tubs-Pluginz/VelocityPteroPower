package de.tubyoub.vpp.api.routing;

/**
 * Pluggable routing provider that can select a target server for a connecting player.
 * If selectTarget returns true, the provider indicates it has handled the routing decision.
 * Implementations may either set a target server or cancel the route on the context.
 */
public interface RoutingProvider {
    /**
     * @param ctx mutable route context with player/request information; set target or cancel
     * @return true if this provider handled the route (target set or cancel decided), false to let others/builtin handle
     */
    boolean selectTarget(PlayerRouteContext ctx);
}
