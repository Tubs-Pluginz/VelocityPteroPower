package de.tubyoub.velocitypteropower.api;

import de.tubyoub.vpp.api.ServerControl;
import de.tubyoub.velocitypteropower.VelocityPteroPower;
import de.tubyoub.velocitypteropower.http.PanelAPIClient;
import de.tubyoub.velocitypteropower.http.PowerSignal;
import de.tubyoub.velocitypteropower.model.PteroServerInfo;

import java.util.Map;

public class ServerControlImpl implements ServerControl {
    private final VelocityPteroPower plugin;

    public ServerControlImpl(VelocityPteroPower plugin) {
        this.plugin = plugin;
    }

    @Override
    public void startByName(String serverName) {
        if (serverName == null) return;
        Map<String, PteroServerInfo> map = plugin.getServerInfoMap();
        if (map == null) return;
        PteroServerInfo info = map.get(serverName);
        if (info == null) return;
        try { plugin.getApiClient().powerServer(info.getServerId(), PowerSignal.START); } catch (Throwable ignored) {}
    }

    @Override
    public void stopByName(String serverName) {
        if (serverName == null) return;
        Map<String, PteroServerInfo> map = plugin.getServerInfoMap();
        if (map == null) return;
        PteroServerInfo info = map.get(serverName);
        if (info == null) return;
        try { plugin.getApiClient().powerServer(info.getServerId(), PowerSignal.STOP); } catch (Throwable ignored) {}
    }

    @Override
    public boolean isOnline(String serverName) {
        if (serverName == null) return false;
        Map<String, PteroServerInfo> map = plugin.getServerInfoMap();
        if (map == null) return false;
        PteroServerInfo info = map.get(serverName);
        if (info == null) return false;
        try { return plugin.getApiClient().isServerOnline(serverName, info.getServerId()); } catch (Throwable t) { return false; }
    }
}
