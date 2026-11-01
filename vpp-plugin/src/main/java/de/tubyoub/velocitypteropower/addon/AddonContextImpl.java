package de.tubyoub.velocitypteropower.addon;

import de.tubyoub.vpp.api.*;
import de.tubyoub.vpp.api.event.VPPEvent;
import de.tubyoub.vpp.api.routing.RoutingProvider;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Tracks registrations so disable/reload can clean them up.
 */
public final class AddonContextImpl implements AddonContext {
    private final VPPApi api;
    private final AddonMeta meta;
    private final AddonLogger logger;
    private final Path dataDirectory;

    private final List<String> registeredCommands = new ArrayList<>();
    private final List<RoutingProvider> registeredProviders = new ArrayList<>();
    private final List<AutoCloseable> subscriptions = new ArrayList<>();

    public AddonContextImpl(VPPApi api, AddonMeta meta, AddonLogger logger, Path dataDirectory) {
        this.api = Objects.requireNonNull(api, "api");
        this.meta = Objects.requireNonNull(meta, "meta");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
    }

    @Override
    public VPPApi getApi() { return api; }

    @Override
    public AddonMeta getMeta() { return meta; }

    @Override
    public AddonLogger getLogger() { return logger; }

    @Override
    public Path getDataDirectory() { return dataDirectory; }

    @Override
    public AddonConfig getConfig() {
        return api.getAddonConfig(meta.id());
    }

    @Override
    public void registerCommand(AddonCommand command) {
        if (command == null || command.name() == null) return;
        api.registerCommand(command);
        registeredCommands.add(command.name());
    }

    @Override
    public void registerRoutingProvider(RoutingProvider provider, int priority) {
        if (provider == null) return;
        api.registerRoutingProvider(provider, priority);
        registeredProviders.add(provider);
    }

    @Override
    public <T extends VPPEvent> void subscribe(Class<T> eventType, Consumer<T> listener) {
        if (eventType == null || listener == null) return;
        AutoCloseable handle = api.getEventBus().subscribe(eventType, listener);
        if (handle != null) subscriptions.add(handle);
    }

    /** Unregister everything tracked by this context. */
    public void cleanup() {
        for (String name : registeredCommands) {
            try { api.unregisterCommand(name); } catch (Throwable ignored) {}
        }
        registeredCommands.clear();
        for (RoutingProvider provider : registeredProviders) {
            try { api.unregisterRoutingProvider(provider); } catch (Throwable ignored) {}
        }
        registeredProviders.clear();
        for (AutoCloseable sub : subscriptions) {
            try { sub.close(); } catch (Throwable ignored) {}
        }
        subscriptions.clear();
    }
}
