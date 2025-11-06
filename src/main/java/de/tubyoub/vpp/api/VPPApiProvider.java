package de.tubyoub.vpp.api;

/**
 * Static access to the VPP API instance created by the plugin at runtime.
 */
public final class VPPApiProvider {
    private static volatile VPPApi instance;

    private VPPApiProvider() {}

    public static VPPApi get() {
        return instance;
    }

    public static void set(VPPApi api) {
        instance = api;
    }
}
