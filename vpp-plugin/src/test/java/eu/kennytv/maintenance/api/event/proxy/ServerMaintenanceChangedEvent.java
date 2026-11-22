package eu.kennytv.maintenance.api.event.proxy;

import eu.kennytv.maintenance.api.proxy.Server;

public class ServerMaintenanceChangedEvent {
    private final Server server;
    private final boolean maintenance;

    public ServerMaintenanceChangedEvent(Server server, boolean maintenance) {
        this.server = server;
        this.maintenance = maintenance;
    }

    public Server getServer() {
        return server;
    }

    public boolean isMaintenance() {
        return maintenance;
    }
}
