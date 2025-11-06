package de.tubyoub.vpp.api;

import java.util.Collection;

/**
 * Read-only view of servers managed by VelocityPteroPower.
 */
public interface ServerRegistry {
    Collection<ManagedServer> listServers();
    ManagedServer getByName(String serverName);
}
