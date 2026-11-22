package de.tubyoub.vpp.api.event;

import java.util.function.Consumer;

/**
 * Simple event bus for VPP events.
 */
public interface VPPEventBus {
    <T extends VPPEvent> AutoCloseable subscribe(Class<T> eventType, Consumer<T> listener);
    void post(VPPEvent event);
}
