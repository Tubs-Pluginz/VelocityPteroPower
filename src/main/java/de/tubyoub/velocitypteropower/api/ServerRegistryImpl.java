package de.tubyoub.velocitypteropower.api;

import de.tubyoub.vpp.api.ManagedServer;
import de.tubyoub.vpp.api.ServerRegistry;
import de.tubyoub.velocitypteropower.VelocityPteroPower;
import de.tubyoub.velocitypteropower.model.PteroServerInfo;

import java.util.*;

public class ServerRegistryImpl implements ServerRegistry {
    private final VelocityPteroPower plugin;

    public ServerRegistryImpl(VelocityPteroPower plugin) {
        this.plugin = plugin;
    }

    @Override
    public Collection<ManagedServer> listServers() {
        Map<String, PteroServerInfo> map = plugin.getServerInfoMap();
        if (map == null || map.isEmpty()) return List.of();
        List<ManagedServer> list = new ArrayList<>(map.size());
        for (Map.Entry<String, PteroServerInfo> e : map.entrySet()) {
            String name = e.getKey();
            String id = e.getValue() != null ? e.getValue().getServerId() : null;
            list.add(new SimpleManagedServer(name, id));
        }
        return Collections.unmodifiableList(list);
    }

    @Override
    public ManagedServer getByName(String serverName) {
        if (serverName == null) return null;
        PteroServerInfo info = plugin.getServerInfoMap().get(serverName);
        if (info == null) return null;
        return new SimpleManagedServer(serverName, info.getServerId());
    }

    private static final class SimpleManagedServer implements ManagedServer {
        private final String name;
        private final String id;
        private final Map<String, String> meta;
        SimpleManagedServer(String name, String id) {
            this.name = name;
            this.id = id;
            this.meta = Map.of();
        }
        @Override public String name() { return name; }
        @Override public String id() { return id; }
        @Override public Map<String, String> meta() { return meta; }
    }
}
