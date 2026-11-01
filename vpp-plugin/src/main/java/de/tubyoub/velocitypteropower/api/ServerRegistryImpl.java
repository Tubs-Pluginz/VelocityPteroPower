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
            list.add(toManaged(e.getKey(), e.getValue()));
        }
        return Collections.unmodifiableList(list);
    }

    @Override
    public ManagedServer getByName(String serverName) {
        if (serverName == null) return null;
        Map<String, PteroServerInfo> map = plugin.getServerInfoMap();
        if (map == null) return null;
        PteroServerInfo info = map.get(serverName);
        if (info == null) return null;
        return toManaged(serverName, info);
    }

    private ManagedServer toManaged(String name, PteroServerInfo info) {
        String id = info != null ? info.getServerId() : null;
        return new SimpleManagedServer(name, id, buildMeta(name, info));
    }

    private Map<String, String> buildMeta(String name, PteroServerInfo info) {
        if (info == null) return Map.of();
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("timeout", String.valueOf(info.getTimeout()));
        meta.put("joinDelay", String.valueOf(info.getJoinDelay()));
        meta.put("whitelist", String.valueOf(info.isWhitelistEnabled()));
        if (info.getPollIntervalSeconds() != null) {
            meta.put("pollIntervalSeconds", String.valueOf(info.getPollIntervalSeconds()));
        }
        if (info.getStartupTimeoutSeconds() != null) {
            meta.put("startupTimeoutSeconds", String.valueOf(info.getStartupTimeoutSeconds()));
        }
        if (info.getCheckMethodOverride() != null) {
            meta.put("checkMethod", info.getCheckMethodOverride().name());
        }
        if (info.getProfileName() != null && !info.getProfileName().isBlank()) {
            meta.put("profile", info.getProfileName());
        }
        if (info.getGroup() != null && !info.getGroup().isBlank()) {
            meta.put("group", info.getGroup());
        }
        if (info.getLinkedServers() != null && !info.getLinkedServers().isEmpty()) {
            meta.put("linkedServers", String.join(",", info.getLinkedServers()));
        }
        if (info.getStartPassword() != null && !info.getStartPassword().isBlank()) {
            meta.put("startPassword", "set");
        }
        var alwaysOnline = plugin.getConfigurationManager().getAlwaysOnlineList();
        boolean isAlwaysOnline = alwaysOnline != null
                && alwaysOnline.stream().anyMatch(s -> s.equalsIgnoreCase(name));
        meta.put("alwaysOnline", String.valueOf(isAlwaysOnline));
        return Collections.unmodifiableMap(meta);
    }

    private static final class SimpleManagedServer implements ManagedServer {
        private final String name;
        private final String id;
        private final Map<String, String> meta;

        SimpleManagedServer(String name, String id, Map<String, String> meta) {
            this.name = name;
            this.id = id;
            this.meta = meta != null ? meta : Map.of();
        }

        @Override public String name() { return name; }
        @Override public String id() { return id; }
        @Override public Map<String, String> meta() { return meta; }
    }
}
