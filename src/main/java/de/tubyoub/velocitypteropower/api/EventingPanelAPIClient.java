package de.tubyoub.velocitypteropower.api;

import de.tubyoub.vpp.api.VPPApiProvider;
import de.tubyoub.vpp.api.event.ServerPowerSignalEvent;
import de.tubyoub.velocitypteropower.VelocityPteroPower;
import de.tubyoub.velocitypteropower.http.PanelAPIClient;
import de.tubyoub.velocitypteropower.http.PowerSignal;
import de.tubyoub.velocitypteropower.model.PteroServerInfo;

import java.util.Map;

/**
 * Wrapper for PanelAPIClient that fires VPP API events before delegating power signals.
 */
public class EventingPanelAPIClient implements PanelAPIClient {
    private final VelocityPteroPower plugin;
    private volatile PanelAPIClient delegate;

    public EventingPanelAPIClient(VelocityPteroPower plugin, PanelAPIClient delegate) {
        this.plugin = plugin;
        this.delegate = delegate;
    }

    public void setDelegate(PanelAPIClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public void powerServer(String serverId, PowerSignal signal) {
        String serverName = null;
        try {
            // Best-effort reverse lookup by id
            for (Map.Entry<String, PteroServerInfo> e : plugin.getServerInfoMap().entrySet()) {
                if (e.getValue() != null && serverId != null && serverId.equals(e.getValue().getServerId())) {
                    serverName = e.getKey();
                    break;
                }
            }
        } catch (Exception ignored) {}

        ServerPowerSignalEvent event = new ServerPowerSignalEvent(serverName, serverId, signal.name());
        var api = VPPApiProvider.get();
        if (api != null) api.getEventBus().post(event);
        if (event.isCancelled()) return;

        PowerSignal effective;
        try { effective = PowerSignal.valueOf(event.getSignal()); } catch (IllegalArgumentException ex) { effective = signal; }
        delegate.powerServer(serverId, effective);
    }

    @Override
    public boolean isServerOnline(String serverName, String serverId) {
        return delegate.isServerOnline(serverName, serverId);
    }

    @Override
    public boolean isServerEmpty(String serverName) {
        return delegate.isServerEmpty(serverName);
    }

    @Override
    public java.util.concurrent.CompletableFuture<de.tubyoub.velocitypteropower.model.ServerResourceUsage> fetchServerResources(String serverId) {
        return delegate.fetchServerResources(serverId);
    }

    @Override
    public java.util.concurrent.CompletableFuture<String> fetchWhitelistFile(String serverId) {
        return delegate.fetchWhitelistFile(serverId);
    }

    @Override
    public boolean isApiKeyValid(String apiKey) { return delegate.isApiKeyValid(apiKey); }

    @Override
    public void shutdown() { delegate.shutdown(); }
}
