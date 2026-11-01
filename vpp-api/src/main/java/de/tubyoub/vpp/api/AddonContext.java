package de.tubyoub.vpp.api;

import de.tubyoub.vpp.api.event.VPPEvent;
import de.tubyoub.vpp.api.routing.RoutingProvider;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Runtime context for a loaded addon. Registrations made through this context
 * are tracked and automatically cleaned up on disable/reload.
 */
public interface AddonContext {
    VPPApi getApi();

    AddonMeta getMeta();

    AddonLogger getLogger();

    /** Per-addon data directory under the host plugin data folder. */
    Path getDataDirectory();

    /** YAML config stored as {@code addons/configs/{id}.yml}. */
    AddonConfig getConfig();

    void registerCommand(AddonCommand command);

    void registerRoutingProvider(RoutingProvider provider, int priority);

    <T extends VPPEvent> void subscribe(Class<T> eventType, Consumer<T> listener);
}
