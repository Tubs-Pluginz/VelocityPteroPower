package eu.kennytv.maintenance.api.proxy;

import eu.kennytv.maintenance.api.Settings;
import eu.kennytv.maintenance.api.event.manager.EventManager;

public interface MaintenanceProxy {
    boolean isMaintenance();

    boolean isMaintenance(Server server);

    Server getServer(String name);

    Settings getSettings();

    EventManager getEventManager();
}
