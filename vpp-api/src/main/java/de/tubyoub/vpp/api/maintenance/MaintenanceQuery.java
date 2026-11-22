package de.tubyoub.vpp.api.maintenance;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Immutable query passed to {@link MaintenanceProvider}s when VPP decides whether
 * a cold-start / join should be blocked.
 *
 * <p>Contains no Velocity types so addon JARs stay portable.
 */
public final class MaintenanceQuery {
    private final UUID playerId;
    private final String username;
    private final String serverName;
    private final Predicate<String> hasPermission;

    public MaintenanceQuery(
            UUID playerId, String username, String serverName, Predicate<String> hasPermission) {
        this.playerId = playerId;
        this.username = username == null ? "" : username;
        this.serverName = serverName;
        this.hasPermission = hasPermission != null ? hasPermission : perm -> false;
    }

    /** Player UUID, or {@code null} when the check is not player-scoped (e.g. idle sweep). */
    public UUID playerId() {
        return playerId;
    }

    public String username() {
        return username;
    }

    /** Velocity server name being started / joined. */
    public String serverName() {
        return serverName;
    }

    public boolean hasPermission(String permission) {
        return permission != null && hasPermission.test(permission);
    }

    public MaintenanceQuery withoutPlayer() {
        return new MaintenanceQuery(null, "", serverName, hasPermission);
    }

    @Override
    public String toString() {
        return "MaintenanceQuery{server=" + serverName + ", player=" + username + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MaintenanceQuery that)) return false;
        return Objects.equals(playerId, that.playerId)
                && Objects.equals(username, that.username)
                && Objects.equals(serverName, that.serverName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(playerId, username, serverName);
    }
}
