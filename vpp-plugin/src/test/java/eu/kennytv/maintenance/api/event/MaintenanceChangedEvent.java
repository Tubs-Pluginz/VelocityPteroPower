package eu.kennytv.maintenance.api.event;

public class MaintenanceChangedEvent {
    private final boolean maintenance;

    public MaintenanceChangedEvent(boolean maintenance) {
        this.maintenance = maintenance;
    }

    public boolean isMaintenance() {
        return maintenance;
    }
}
