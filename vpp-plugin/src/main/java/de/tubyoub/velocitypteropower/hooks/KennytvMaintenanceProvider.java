/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.hooks;

import com.velocitypowered.api.proxy.ProxyServer;
import de.tubyoub.velocitypteropower.util.FilteredComponentLogger;
import de.tubyoub.vpp.api.maintenance.MaintenanceClearListener;
import de.tubyoub.vpp.api.maintenance.MaintenanceProvider;
import de.tubyoub.vpp.api.maintenance.MaintenanceQuery;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Optional soft-dependency adapter for kennytv/Maintenance.
 * Implements {@link MaintenanceProvider} so VPP's built-in maintenance stays independent.
 */
public final class KennytvMaintenanceProvider implements MaintenanceProvider {

    public static final String ID = "kennytv-maintenance";

    private final FilteredComponentLogger logger;
    private final CopyOnWriteArrayList<MaintenanceClearListener> listeners = new CopyOnWriteArrayList<>();
    private volatile Object maintenanceProxy;
    private volatile boolean listenersRegistered;

    public KennytvMaintenanceProvider(FilteredComponentLogger logger) {
        this.logger = logger;
    }

    /**
     * Try to attach to kennytv/Maintenance if present. Safe to call multiple times.
     *
     * @return this provider when the plugin API is available, otherwise {@code null}
     */
    public KennytvMaintenanceProvider tryAttach(ProxyServer proxyServer) {
        maintenanceProxy = null;
        if (proxyServer == null || !proxyServer.getPluginManager().isLoaded("maintenance")) {
            return null;
        }
        try {
            Class<?> provider = Class.forName("eu.kennytv.maintenance.api.MaintenanceProvider");
            Object maintenance = provider.getMethod("get").invoke(null);
            Class<?> proxyApi = Class.forName("eu.kennytv.maintenance.api.proxy.MaintenanceProxy");
            if (!proxyApi.isInstance(maintenance)) {
                logger.warn("Maintenance is loaded but proxy API is unavailable; kennytv provider disabled.");
                return null;
            }
            maintenanceProxy = maintenance;
            registerListeners(maintenance);
            logger.info("kennytv/Maintenance detected — registered as external maintenance provider.");
            return this;
        } catch (ClassNotFoundException ex) {
            logger.debug("Maintenance plugin present but API classes missing; kennytv provider disabled.");
            return null;
        } catch (Throwable t) {
            logger.warn("Failed to initialize kennytv maintenance provider: {}", t.toString());
            return null;
        }
    }

    private void registerListeners(Object maintenance) {
        if (listenersRegistered) return;
        try {
            Object eventManager = maintenance.getClass().getMethod("getEventManager").invoke(maintenance);
            Class<?> listenerClass = Class.forName("eu.kennytv.maintenance.api.event.manager.EventListener");
            Class<?> changedEvent = Class.forName("eu.kennytv.maintenance.api.event.MaintenanceChangedEvent");
            Class<?> serverChangedEvent =
                    Class.forName("eu.kennytv.maintenance.api.event.proxy.ServerMaintenanceChangedEvent");

            Object globalListener = Proxy.newProxyInstance(
                    listenerClass.getClassLoader(),
                    new Class<?>[] {listenerClass},
                    (p, method, args) -> {
                        if ("onEvent".equals(method.getName()) && args != null && args.length == 1) {
                            boolean on = (boolean) args[0].getClass().getMethod("isMaintenance").invoke(args[0]);
                            if (!on) fireCleared(null);
                        }
                        return null;
                    });

            Object serverListener = Proxy.newProxyInstance(
                    listenerClass.getClassLoader(),
                    new Class<?>[] {listenerClass},
                    (p, method, args) -> {
                        if ("onEvent".equals(method.getName()) && args != null && args.length == 1) {
                            Object event = args[0];
                            boolean on = (boolean) event.getClass().getMethod("isMaintenance").invoke(event);
                            if (!on) {
                                Object server = event.getClass().getMethod("getServer").invoke(event);
                                String name = (String) server.getClass().getMethod("getName").invoke(server);
                                fireCleared(name);
                            }
                        }
                        return null;
                    });

            Method register = eventManager.getClass().getMethod("registerListener", listenerClass, Class.class);
            register.invoke(eventManager, globalListener, changedEvent);
            register.invoke(eventManager, serverListener, serverChangedEvent);
            listenersRegistered = true;
        } catch (Throwable t) {
            logger.debug("Could not register kennytv Maintenance event listeners: {}", t.toString());
        }
    }

    private void fireCleared(String serverName) {
        for (MaintenanceClearListener listener : List.copyOf(listeners)) {
            try {
                listener.onMaintenanceCleared(serverName);
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int priority() {
        return 50;
    }

    @Override
    public boolean isActive() {
        return maintenanceProxy != null;
    }

    @Override
    public boolean blocks(MaintenanceQuery query) {
        Object m = maintenanceProxy;
        if (m == null || query == null || query.serverName() == null) return false;
        try {
            Object settings = m.getClass().getMethod("getSettings").invoke(m);
            boolean bypass = false;
            if (query.playerId() != null) {
                bypass = query.hasPermission("maintenance.admin")
                        || query.hasPermission("maintenance.bypass")
                        || (boolean) settings.getClass().getMethod("isWhitelisted", UUID.class)
                                .invoke(settings, query.playerId());
            }

            if ((boolean) m.getClass().getMethod("isMaintenance").invoke(m) && !bypass) {
                return true;
            }

            Object server = m.getClass().getMethod("getServer", String.class).invoke(m, query.serverName());
            if (server == null) return false;

            boolean serverMaint = false;
            for (Method method : m.getClass().getMethods()) {
                if (!"isMaintenance".equals(method.getName()) || method.getParameterCount() != 1) continue;
                Class<?> param = method.getParameterTypes()[0];
                if (param.isInstance(server)) {
                    serverMaint = (boolean) method.invoke(m, server);
                    break;
                }
            }
            if (!serverMaint) return false;

            String bypassPerm =
                    "maintenance.singleserver.bypass." + query.serverName().toLowerCase(Locale.ROOT);
            return !bypass && !query.hasPermission(bypassPerm);
        } catch (Throwable t) {
            logger.debug("KennytvMaintenanceProvider.blocks failed: {}", t.toString());
            return false;
        }
    }

    @Override
    public String detail(MaintenanceQuery query) {
        return " (external)";
    }

    @Override
    public void addClearListener(MaintenanceClearListener listener) {
        if (listener != null) listeners.add(listener);
    }

    @Override
    public void removeClearListener(MaintenanceClearListener listener) {
        if (listener != null) listeners.remove(listener);
    }
}
