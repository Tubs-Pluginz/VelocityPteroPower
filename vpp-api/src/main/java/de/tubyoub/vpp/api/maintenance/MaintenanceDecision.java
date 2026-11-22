package de.tubyoub.vpp.api.maintenance;

/**
 * Result of consulting all registered {@link MaintenanceProvider}s.
 */
public final class MaintenanceDecision {
    private final boolean blocked;
    private final String sourceId;
    private final String detail;

    private MaintenanceDecision(boolean blocked, String sourceId, String detail) {
        this.blocked = blocked;
        this.sourceId = sourceId;
        this.detail = detail == null ? "" : detail;
    }

    public static MaintenanceDecision allow() {
        return new MaintenanceDecision(false, null, "");
    }

    public static MaintenanceDecision block(String sourceId, String detail) {
        return new MaintenanceDecision(true, sourceId, detail);
    }

    public boolean isBlocked() {
        return blocked;
    }

    /** Provider id that produced the detail, or {@code null} when allowed. */
    public String sourceId() {
        return sourceId;
    }

    public String detail() {
        return detail;
    }
}
