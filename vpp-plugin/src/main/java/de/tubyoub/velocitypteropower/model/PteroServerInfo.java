/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.model;

import de.tubyoub.velocitypteropower.manager.ConfigurationManager.ServerCheckMethod;

import java.util.Collections;
import java.util.List;

/**
 * Server information for a managed panel server.
 */
public class PteroServerInfo {
    private final String serverId;
    private final int timeout;
    private final int joinDelay;
    private final boolean whitelist;
    private final Integer pollIntervalSeconds;
    private final Integer startupTimeoutSeconds;
    private final ServerCheckMethod checkMethodOverride;
    private final String profileName;
    private final String group;
    private final List<String> linkedServers;
    private final String startPassword;

    public PteroServerInfo(String serverId, int timeout, int joinDelay, boolean whitelist) {
        this(serverId, timeout, joinDelay, whitelist, null, null, null, null, null, null, null);
    }

    public PteroServerInfo(
            String serverId,
            int timeout,
            int joinDelay,
            boolean whitelist,
            Integer pollIntervalSeconds,
            Integer startupTimeoutSeconds,
            ServerCheckMethod checkMethodOverride,
            String profileName) {
        this(
                serverId,
                timeout,
                joinDelay,
                whitelist,
                pollIntervalSeconds,
                startupTimeoutSeconds,
                checkMethodOverride,
                profileName,
                null,
                null,
                null);
    }

    public PteroServerInfo(
            String serverId,
            int timeout,
            int joinDelay,
            boolean whitelist,
            Integer pollIntervalSeconds,
            Integer startupTimeoutSeconds,
            ServerCheckMethod checkMethodOverride,
            String profileName,
            String group,
            List<String> linkedServers,
            String startPassword) {
        this.serverId = serverId;
        this.timeout = timeout;
        this.joinDelay = joinDelay;
        this.whitelist = whitelist;
        this.pollIntervalSeconds = pollIntervalSeconds;
        this.startupTimeoutSeconds = startupTimeoutSeconds;
        this.checkMethodOverride = checkMethodOverride;
        this.profileName = profileName;
        this.group = group;
        this.linkedServers =
                linkedServers == null ? List.of() : Collections.unmodifiableList(List.copyOf(linkedServers));
        this.startPassword = startPassword;
    }

    public String getServerId() {
        return serverId;
    }

    public int getTimeout() {
        return timeout;
    }

    /** Post-online settle delay in seconds before auto-connecting players. */
    public int getJoinDelay() {
        return joinDelay;
    }

    public boolean isWhitelistEnabled() {
        return whitelist;
    }

    /** Per-server poll interval override, or null to use global default. */
    public Integer getPollIntervalSeconds() {
        return pollIntervalSeconds;
    }

    /** Per-server startup timeout override, or null to use global default. */
    public Integer getStartupTimeoutSeconds() {
        return startupTimeoutSeconds;
    }

    /** Per-server check method override, or null to use global default. */
    public ServerCheckMethod getCheckMethodOverride() {
        return checkMethodOverride;
    }

    public String getProfileName() {
        return profileName;
    }

    /** Shared group name for linked idle-shutdown / start peers, or null. */
    public String getGroup() {
        return group;
    }

    /** Explicitly linked servers started alongside this one. */
    public List<String> getLinkedServers() {
        return linkedServers;
    }

    /** Optional single-use start password from config (may be overridden at runtime). */
    public String getStartPassword() {
        return startPassword;
    }
}
