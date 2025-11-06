package de.tubyoub.vpp.api;

import java.util.Map;

/**
 * Minimal information about a managed server.
 */
public interface ManagedServer {
    String name();
    String id();
    /**
     * Optional metadata map; may be empty.
     */
    default Map<String, String> meta() { return java.util.Map.of(); }
}
