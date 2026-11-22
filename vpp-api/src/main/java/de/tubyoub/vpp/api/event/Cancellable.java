package de.tubyoub.vpp.api.event;

/** Event that can be cancelled by listeners. */
public interface Cancellable extends VPPEvent {
    boolean isCancelled();
    void setCancelled(boolean cancelled);
}
