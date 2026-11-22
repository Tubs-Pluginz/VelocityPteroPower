package eu.kennytv.maintenance.api;

/** Test stub mirroring the kennytv/Maintenance static accessor the VPP adapter reflects on. */
public final class MaintenanceProvider {
    public static Object instance;

    private MaintenanceProvider() {}

    public static Object get() {
        return instance;
    }
}
