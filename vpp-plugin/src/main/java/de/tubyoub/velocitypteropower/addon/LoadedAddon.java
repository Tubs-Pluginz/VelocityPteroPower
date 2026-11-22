package de.tubyoub.velocitypteropower.addon;

import de.tubyoub.vpp.api.AddonMeta;
import de.tubyoub.vpp.api.VPPAddon;

import java.net.URLClassLoader;
import java.nio.file.Path;

public final class LoadedAddon {
    public enum Status { ACTIVE, DISABLED, ERROR }

    private final Path jarPath;
    private final AddonMeta meta;
    private final VPPAddon instance;
    private final URLClassLoader classLoader;
    private AddonContextImpl context;
    private Status status;
    private String errorMessage;

    public LoadedAddon(Path jarPath, AddonMeta meta, VPPAddon instance, URLClassLoader classLoader, Status status) {
        this.jarPath = jarPath;
        this.meta = meta;
        this.instance = instance;
        this.classLoader = classLoader;
        this.status = status;
    }

    public Path getJarPath() { return jarPath; }
    public AddonMeta getMeta() { return meta; }
    public VPPAddon getInstance() { return instance; }
    public URLClassLoader getClassLoader() { return classLoader; }
    public AddonContextImpl getContext() { return context; }
    public void setContext(AddonContextImpl context) { this.context = context; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public boolean isActive() { return status == Status.ACTIVE; }
}
