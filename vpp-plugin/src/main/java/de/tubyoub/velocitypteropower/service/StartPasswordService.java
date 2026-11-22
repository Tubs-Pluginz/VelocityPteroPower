/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.service;

import de.tubyoub.velocitypteropower.VelocityPteroPower;
import de.tubyoub.velocitypteropower.model.PteroServerInfo;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single-use per-server start password (#32).
 *
 * <p>When a managed server has a {@code startPassword}, cold-starts require a successful
 * {@code /ptero unlock <server> <password>}. The password is consumed after one successful
 * unlock; operators set a new one via config reload or {@code /ptero password set|generate}.
 */
public class StartPasswordService {

    private static final char[] PASSWORD_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final VelocityPteroPower plugin;
    private final ComponentLogger logger;

    /** Runtime passwords (config defaults + admin overrides). Blank/null = no gate. */
    private final Map<String, String> passwords = new ConcurrentHashMap<>();
    /** Players authorized for the current password cycle (until disconnect or consume). */
    private final Map<String, Set<UUID>> authorized = new ConcurrentHashMap<>();

    public StartPasswordService(VelocityPteroPower plugin) {
        this.plugin = plugin;
        this.logger = plugin.getFilteredLogger();
        reloadFromConfig();
    }

    public void reloadFromConfig() {
        passwords.clear();
        authorized.clear();
        Map<String, PteroServerInfo> map = plugin.getServerInfoMap();
        if (map == null) return;
        for (Map.Entry<String, PteroServerInfo> e : map.entrySet()) {
            String pwd = e.getValue().getStartPassword();
            if (pwd != null && !pwd.isBlank()) {
                passwords.put(normalize(e.getKey()), pwd);
            }
        }
        logger.debug("Loaded start passwords for {} server(s).", passwords.size());
    }

    public boolean requiresPassword(String serverName) {
        String pwd = passwords.get(normalize(serverName));
        return pwd != null && !pwd.isBlank();
    }

    public boolean isAuthorized(UUID playerId, String serverName) {
        if (playerId == null || serverName == null) return false;
        Set<UUID> set = authorized.get(normalize(serverName));
        return set != null && set.contains(playerId);
    }

    public boolean canBypass(com.velocitypowered.api.proxy.Player player) {
        return player != null && player.hasPermission("ptero.password.bypass");
    }

    /**
     * Attempt unlock. On success: authorize player and consume the password (single-use).
     *
     * @return result code for messaging
     */
    public UnlockResult unlock(UUID playerId, String serverName, String attempt) {
        String key = normalize(serverName);
        String expected = passwords.get(key);
        if (expected == null || expected.isBlank()) {
            return UnlockResult.NOT_REQUIRED;
        }
        if (attempt == null || !expected.equals(attempt)) {
            return UnlockResult.INVALID;
        }
        authorized.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(playerId);
        passwords.remove(key);
        // Clear other authorizations for this server — password is spent
        Set<UUID> set = authorized.get(key);
        if (set != null) {
            set.clear();
            set.add(playerId);
        }
        logger.info("Start password for '{}' consumed by player {}.", serverName, playerId);
        return UnlockResult.SUCCESS;
    }

    public void clearPlayer(UUID playerId) {
        if (playerId == null) return;
        for (Set<UUID> set : authorized.values()) {
            set.remove(playerId);
        }
    }

    public void setPassword(String serverName, String password) {
        String key = normalize(serverName);
        if (password == null || password.isBlank()) {
            passwords.remove(key);
            authorized.remove(key);
            return;
        }
        passwords.put(key, password);
        authorized.remove(key);
    }

    public String generatePassword(String serverName, int length) {
        int len = Math.max(6, Math.min(32, length));
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(PASSWORD_ALPHABET[RANDOM.nextInt(PASSWORD_ALPHABET.length)]);
        }
        String pwd = sb.toString();
        setPassword(serverName, pwd);
        return pwd;
    }

    public boolean hasPassword(String serverName) {
        return requiresPassword(serverName);
    }

    private static String normalize(String serverName) {
        return serverName == null ? "" : serverName.toLowerCase(Locale.ROOT);
    }

    public enum UnlockResult {
        SUCCESS,
        INVALID,
        NOT_REQUIRED
    }
}
