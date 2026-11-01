package de.tubyoub.vpp.api;

import java.util.Map;

/**
 * Minimal information about a managed server.
 */
public interface ManagedServer {
    String name();
    String id();
    /**
     * Read-only metadata map. Typical keys when populated by core VPP:
     * {@code timeout}, {@code joinDelay}, {@code whitelist}, {@code alwaysOnline},
     * and optionally {@code pollIntervalSeconds}, {@code startupTimeoutSeconds},
     * {@code checkMethod}, {@code profile}, {@code group}, {@code linkedServers},
     * {@code startPassword} ({@code "set"} when configured).
     */
    default Map<String, String> meta() { return java.util.Map.of(); }
}
