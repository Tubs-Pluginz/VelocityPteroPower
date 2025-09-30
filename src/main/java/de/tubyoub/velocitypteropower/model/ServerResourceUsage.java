package de.tubyoub.velocitypteropower.model;

/**
 * Represents resource usage information for a server as returned by the
 * Panel client API endpoints (resources + server details for limits).
 */
public class ServerResourceUsage {
    private final String currentState;
    private final boolean suspended;
    private final long memoryBytes;
    private final long memoryLimitBytes; // 0 = unlimited, -1 = unknown
    private final double cpuAbsolute;
    private final double cpuLimitPercent; // 0 = unlimited, -1 = unknown
    private final long diskBytes;
    private final long diskLimitBytes; // 0 = unlimited, -1 = unknown
    private final long networkRxBytes;
    private final long networkTxBytes;
    private final long uptimeMillis;
    private final boolean available;

    public ServerResourceUsage(String currentState,
                               boolean suspended,
                               long memoryBytes,
                               long memoryLimitBytes,
                               double cpuAbsolute,
                               double cpuLimitPercent,
                               long diskBytes,
                               long diskLimitBytes,
                               long networkRxBytes,
                               long networkTxBytes,
                               long uptimeMillis,
                               boolean available) {
        this.currentState = currentState;
        this.suspended = suspended;
        this.memoryBytes = memoryBytes;
        this.memoryLimitBytes = memoryLimitBytes;
        this.cpuAbsolute = cpuAbsolute;
        this.cpuLimitPercent = cpuLimitPercent;
        this.diskBytes = diskBytes;
        this.diskLimitBytes = diskLimitBytes;
        this.networkRxBytes = networkRxBytes;
        this.networkTxBytes = networkTxBytes;
        this.uptimeMillis = uptimeMillis;
        this.available = available;
    }

    public static ServerResourceUsage unavailable() {
        return new ServerResourceUsage("unknown", false, 0L, -1L, 0.0, -1.0, 0L, -1L, 0L, 0L, 0L, false);
    }

    public String getCurrentState() { return currentState; }
    public boolean isSuspended() { return suspended; }
    public long getMemoryBytes() { return memoryBytes; }
    public long getMemoryLimitBytes() { return memoryLimitBytes; }
    public double getCpuAbsolute() { return cpuAbsolute; }
    public double getCpuLimitPercent() { return cpuLimitPercent; }
    public long getDiskBytes() { return diskBytes; }
    public long getDiskLimitBytes() { return diskLimitBytes; }
    public long getNetworkRxBytes() { return networkRxBytes; }
    public long getNetworkTxBytes() { return networkTxBytes; }
    public long getUptimeMillis() { return uptimeMillis; }
    public boolean isAvailable() { return available; }
}