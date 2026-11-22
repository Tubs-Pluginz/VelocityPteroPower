/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.service;

import de.tubyoub.vpp.api.maintenance.MaintenanceClearListener;
import de.tubyoub.vpp.api.maintenance.MaintenanceProvider;
import de.tubyoub.vpp.api.maintenance.MaintenanceQuery;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Exposes {@link MaintenanceService} through the public {@link MaintenanceProvider} SPI.
 */
public final class BuiltinMaintenanceProvider implements MaintenanceProvider {

    public static final String ID = "vpp-builtin";

    private final MaintenanceService service;
    private final CopyOnWriteArrayList<MaintenanceClearListener> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean enabled = true;

    public BuiltinMaintenanceProvider(MaintenanceService service) {
        this.service = service;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public MaintenanceService service() {
        return service;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int priority() {
        return 100; // prefer built-in detail text when both block
    }

    @Override
    public boolean isActive() {
        return enabled && service != null;
    }

    @Override
    public boolean blocks(MaintenanceQuery query) {
        if (!isActive() || query == null) return false;
        return service.isServerInMaintenance(query.serverName());
    }

    @Override
    public String detail(MaintenanceQuery query) {
        if (!isActive() || query == null) return "";
        return service.detailFor(query.serverName());
    }

    @Override
    public void addClearListener(MaintenanceClearListener listener) {
        if (listener != null) listeners.add(listener);
    }

    @Override
    public void removeClearListener(MaintenanceClearListener listener) {
        if (listener != null) listeners.remove(listener);
    }

    /** Called by {@link MaintenanceService} when operators toggle maintenance off. */
    public void notifyCleared(String serverNameOrNull) {
        for (MaintenanceClearListener listener : List.copyOf(listeners)) {
            try {
                listener.onMaintenanceCleared(serverNameOrNull);
            } catch (Throwable ignored) {}
        }
    }
}
