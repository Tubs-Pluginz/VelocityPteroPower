package de.tubyoub.velocitypteropower.api;

import de.tubyoub.vpp.api.*;
import de.tubyoub.vpp.api.event.VPPEventBus;
import de.tubyoub.velocitypteropower.VelocityPteroPower;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class DefaultVPPApi implements VPPApi {
    private static final String API_VERSION = "1.0.0";

    private final VPPEventBus eventBus;
    private final PanelHttpFacade panelHttp;
    private final ServerControl serverControl;
    private final ServerRegistry serverRegistry;
    private final java.nio.file.Path dataDirectory;
    private final Map<String, AddonCommand> commands = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<ProviderHolder> routingProviders = new CopyOnWriteArrayList<>();
    private final java.util.concurrent.ConcurrentHashMap<String, de.tubyoub.vpp.api.AddonConfig> addonConfigs = new java.util.concurrent.ConcurrentHashMap<>();

    private static final class ProviderHolder {
        final de.tubyoub.vpp.api.routing.RoutingProvider provider;
        final int priority;
        ProviderHolder(de.tubyoub.vpp.api.routing.RoutingProvider provider, int priority) {
            this.provider = provider;
            this.priority = priority;
        }
    }

    public DefaultVPPApi(VPPEventBus eventBus, PanelHttpFacade panelHttp) {
        this(eventBus, panelHttp, null, null, null);
    }

    @Override
    public String getApiVersion() {
        return API_VERSION;
    }

    public DefaultVPPApi(VPPEventBus eventBus, PanelHttpFacade panelHttp, ServerControl serverControl, ServerRegistry serverRegistry) {
        this(eventBus, panelHttp, serverControl, serverRegistry, null);
    }

    public DefaultVPPApi(VPPEventBus eventBus, PanelHttpFacade panelHttp, ServerControl serverControl, ServerRegistry serverRegistry, java.nio.file.Path dataDirectory) {
        this.eventBus = eventBus;
        this.panelHttp = panelHttp;
        this.serverControl = serverControl;
        this.serverRegistry = serverRegistry;
        this.dataDirectory = dataDirectory;
    }

    @Override
    public void registerCommand(AddonCommand command) {
        if (command == null || command.name() == null) return;
        String primary = command.name().toLowerCase(Locale.ROOT);
        commands.put(primary, command);
        List<String> aliases = command.aliases();
        if (aliases != null) {
            for (String a : aliases) {
                if (a != null && !a.isBlank()) {
                    commands.put(a.toLowerCase(Locale.ROOT), command);
                }
            }
        }
    }

    @Override
    public void unregisterCommand(String name) {
        if (name == null) return;
        String key = name.toLowerCase(Locale.ROOT);
        AddonCommand cmd = commands.remove(key);
        if (cmd != null) {
            // remove aliases mapping to the same object
            commands.entrySet().removeIf(e -> e.getValue() == cmd);
        }
    }

    @Override
    public boolean dispatchSubcommand(String name, CommandActor actor, String[] args) {
        if (name == null) return false;
        AddonCommand cmd = commands.get(name.toLowerCase(Locale.ROOT));
        if (cmd == null) return false;
        String perm = cmd.permission();
        if (perm != null && !perm.isBlank()) {
            try { if (!actor.hasPermission(perm)) { actor.sendMessage("You don't have permission to use this command."); return true; } } catch (Throwable ignored) {}
        }
        try {
            cmd.execute(actor, args != null ? args : new String[0]);
        } catch (Throwable t) {
            try { actor.sendMessage("An error occurred while executing the command: " + t.getMessage()); } catch (Throwable ignored) {}
        }
        return true;
    }

    @Override
    public Collection<AddonCommand> getRegisteredCommands() {
        // return unique instances
        return new LinkedHashSet<>(commands.values());
    }

    @Override
    public VPPEventBus getEventBus() {
        return eventBus;
    }

    @Override
    public PanelHttpFacade getPanelHttp() {
        return panelHttp;
    }

    @Override
    public ServerControl getServerControl() {
        return serverControl;
    }

    @Override
    public ServerRegistry getServerRegistry() {
        return serverRegistry;
    }

    @Override
    public de.tubyoub.vpp.api.AddonConfig getAddonConfig(String addonId) {
        if (addonId == null || addonId.isBlank()) throw new IllegalArgumentException("addonId must not be null/blank");
        return addonConfigs.computeIfAbsent(addonId.toLowerCase(java.util.Locale.ROOT), id -> {
            java.nio.file.Path base = this.dataDirectory;
            if (base == null) throw new IllegalStateException("VPP data directory unavailable");
            return new AddonConfigImpl(id, base);
        });
    }

    // Routing SPI
    @Override
    public void registerRoutingProvider(de.tubyoub.vpp.api.routing.RoutingProvider provider, int priority) {
        if (provider == null) return;
        routingProviders.add(new ProviderHolder(provider, priority));
        routingProviders.sort((a, b) -> Integer.compare(b.priority, a.priority)); // highest first
    }

    @Override
    public void unregisterRoutingProvider(de.tubyoub.vpp.api.routing.RoutingProvider provider) {
        if (provider == null) return;
        routingProviders.removeIf(h -> h.provider == provider);
    }

    @Override
    public boolean hasRoutingProvider() {
        return !routingProviders.isEmpty();
    }

    @Override
    public boolean selectRoute(de.tubyoub.vpp.api.routing.PlayerRouteContext ctx) {
        for (ProviderHolder h : routingProviders) {
            try {
                if (h.provider.selectTarget(ctx)) return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }
}
