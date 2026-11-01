/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.http;

import de.tubyoub.velocitypteropower.VelocityPteroPower;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Panel client for <a href="https://calagopus.com">Calagopus</a>.
 *
 * <p>Calagopus keeps the familiar {@code /api/client/servers/{uuid}/...} layout but is not
 * wire-compatible with Pterodactyl/Pelican: power uses {@code {"action":"..."}} (not
 * {@code signal}), resources are wrapped as {@code {"resources":{...}}} with a {@code state}
 * field, websocket credentials use {@code url} (not {@code socket}), and API keys are
 * {@code c7sp_}-prefixed.
 */
public class CalagopusAPIClient extends AbstractPanelAPIClient {

    public CalagopusAPIClient(VelocityPteroPower plugin) {
        super(plugin);
    }

    private String baseUrl() {
        return configurationManager.getPterodactylUrl();
    }

    private String apiKey() {
        return configurationManager.getPterodactylApiKey();
    }

    private HttpRequest.Builder authorized(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey())
                .timeout(Duration.ofSeconds(10));
    }

    @Override
    public CompletableFuture<de.tubyoub.velocitypteropower.model.ServerResourceUsage> fetchServerResources(
            String serverId) {
        return fetchWithCache(serverId, () -> {
            if (serverId == null || serverId.isBlank()) {
                return de.tubyoub.velocitypteropower.model.ServerResourceUsage.unavailable();
            }
            if (!rateLimitTracker.canMakeRequest()) {
                logger.warn("Rate limit reached. Skipping Calagopus resources fetch for {}.", serverId);
                return de.tubyoub.velocitypteropower.model.ServerResourceUsage.unavailable();
            }
            try {
                long diskLimitBytes = -1L;
                try {
                    HttpResponse<String> detailsResp =
                            httpClient.send(
                                    authorized("api/client/servers/" + serverId).GET().build(),
                                    HttpResponse.BodyHandlers.ofString());
                    rateLimitTracker.updateRateLimitInfo(detailsResp);
                    if (detailsResp.statusCode() == 200 && detailsResp.body() != null) {
                        // limits.disk is MiB-style like Ptero migrations (0 = unlimited)
                        long diskMiB = extractNestedLong(detailsResp.body(), "\"limits\"", "\"disk\"");
                        if (diskMiB >= 0) {
                            diskLimitBytes = diskMiB * 1024L * 1024L;
                        }
                    }
                } catch (Exception e) {
                    logger.debug("Failed to fetch Calagopus server details for {}: {}", serverId, e.toString());
                }

                HttpResponse<String> resResp =
                        httpClient.send(
                                authorized("api/client/servers/" + serverId + "/resources").GET().build(),
                                HttpResponse.BodyHandlers.ofString());
                rateLimitTracker.updateRateLimitInfo(resResp);
                if (resResp.statusCode() != 200 || resResp.body() == null) {
                    logger.debug(
                            "Calagopus resources fetch for {} returned status {}",
                            serverId,
                            resResp.statusCode());
                    return de.tubyoub.velocitypteropower.model.ServerResourceUsage.unavailable();
                }

                String json = resResp.body();
                String state = extractString(json, "\"state\"");
                long mem = extractLong(json, "\"memory_bytes\"");
                long memLimit = extractLong(json, "\"memory_limit_bytes\"");
                double cpu = extractDouble(json, "\"cpu_absolute\"");
                double cpuLimit = extractDouble(json, "\"cpu_limit_absolute\"");
                long disk = extractLong(json, "\"disk_bytes\"");
                long rx = extractLong(json, "\"rx_bytes\"");
                long tx = extractLong(json, "\"tx_bytes\"");
                long uptime = extractLong(json, "\"uptime\"");
                boolean suspended = json.contains("\"is_suspended\":true")
                        || json.contains("\"is_suspended\": true");

                return new de.tubyoub.velocitypteropower.model.ServerResourceUsage(
                        state != null ? state : "unknown",
                        suspended,
                        mem,
                        memLimit,
                        cpu,
                        cpuLimit,
                        disk,
                        diskLimitBytes,
                        rx,
                        tx,
                        uptime,
                        true);
            } catch (Exception e) {
                logger.error("Error fetching Calagopus resources for {}: {}", serverId, e.toString());
                return de.tubyoub.velocitypteropower.model.ServerResourceUsage.unavailable();
            }
        });
    }

    @Override
    public void powerServer(String serverId, PowerSignal signal) {
        if (!rateLimitTracker.canMakeRequest()) {
            logger.warn(
                    "Rate limit reached. Cannot send power action {} to Calagopus server {}.",
                    signal,
                    serverId);
            return;
        }
        rateLimitTracker.consumeOne();
        try {
            // Calagopus uses "action", not Pterodactyl's "signal"
            String body = "{\"action\": \"" + signal.getApiSignal() + "\"}";
            HttpResponse<String> response =
                    httpClient.send(
                            authorized("api/client/servers/" + serverId + "/power")
                                    .POST(HttpRequest.BodyPublishers.ofString(body))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            rateLimitTracker.updateRateLimitInfo(response);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                logger.warn(
                        "Calagopus power {} for {} returned HTTP {}: {}",
                        signal.getApiSignal(),
                        serverId,
                        response.statusCode(),
                        truncate(response.body(), 200));
            }
        } catch (Exception e) {
            rateLimitTracker.restoreOne();
            logger.error("Error powering Calagopus server {}.", serverId, e);
        }
    }

    @Override
    protected boolean checkOnlineViaPanelApi(String serverName, String serverId) {
        if (serverId == null || serverId.isBlank()) {
            logger.error("Cannot perform Calagopus API check: Server ID missing for '{}'.", serverName);
            return false;
        }
        if (!rateLimitTracker.canMakeRequest()) {
            logger.warn(
                    "Rate limit reached. Cannot perform Calagopus status check for {} ({}).",
                    serverName,
                    serverId);
            return false;
        }

        int maxRetries = 3;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            rateLimitTracker.consumeOne();
            try {
                HttpResponse<String> response =
                        httpClient.send(
                                authorized("api/client/servers/" + serverId + "/resources").GET().build(),
                                HttpResponse.BodyHandlers.ofString());
                rateLimitTracker.updateRateLimitInfo(response);
                String body = response.body();
                if (response.statusCode() == 200 && body != null) {
                    boolean running = body.contains("\"state\":\"running\"")
                            || body.contains("\"state\": \"running\"");
                    logger.debug(
                            "Calagopus API check for {} ({}): HTTP {}, running={}",
                            serverName,
                            serverId,
                            response.statusCode(),
                            running);
                    return running;
                }
                logger.warn(
                        "Calagopus API check for {} ({}) failed: HTTP {} Body: {}",
                        serverName,
                        serverId,
                        response.statusCode(),
                        truncate(body, 100));
                return false;
            } catch (IOException e) {
                rateLimitTracker.restoreOne();
                if (e.getMessage() != null && e.getMessage().contains("GOAWAY")) {
                    logger.warn(
                            "Calagopus API check for {} ({}) GOAWAY (Attempt {}/{}).",
                            serverName,
                            serverId,
                            attempt,
                            maxRetries);
                    if (attempt == maxRetries) {
                        return false;
                    }
                    try {
                        Thread.sleep(1000L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                } else {
                    logger.error(
                            "Calagopus API check for {} ({}) failed: {}",
                            serverName,
                            serverId,
                            e.getMessage(),
                            e);
                    return false;
                }
            } catch (InterruptedException e) {
                rateLimitTracker.restoreOne();
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception e) {
                rateLimitTracker.restoreOne();
                logger.error(
                        "Unexpected Calagopus API check error for {} ({}): {}",
                        serverName,
                        serverId,
                        e.getMessage(),
                        e);
                return false;
            }
        }
        return false;
    }

    @Override
    public CompletableFuture<String> fetchWhitelistFile(String serverId) {
        CompletableFuture<String> future = new CompletableFuture<>();
        executorService.submit(() -> {
            try {
                // Calagopus OpenAPI examples use leading-slash paths; try that first.
                String encoded = URLEncoder.encode("/whitelist.json", StandardCharsets.UTF_8);
                String url = baseUrl()
                        + "api/client/servers/"
                        + serverId
                        + "/files/contents?file="
                        + encoded;
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Accept", "*/*")
                        .header("Authorization", "Bearer " + apiKey())
                        .GET()
                        .timeout(Duration.ofSeconds(10))
                        .build();
                HttpResponse<String> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                rateLimitTracker.updateRateLimitInfo(response);

                if (response.statusCode() == 200) {
                    future.complete(response.body());
                    return;
                }
                if (response.statusCode() == 404) {
                    // Retry without leading slash (Ptero-style relative name)
                    String encodedRel = URLEncoder.encode("whitelist.json", StandardCharsets.UTF_8);
                    HttpRequest retry = HttpRequest.newBuilder()
                            .uri(URI.create(
                                    baseUrl()
                                            + "api/client/servers/"
                                            + serverId
                                            + "/files/contents?file="
                                            + encodedRel))
                            .header("Accept", "*/*")
                            .header("Authorization", "Bearer " + apiKey())
                            .GET()
                            .timeout(Duration.ofSeconds(10))
                            .build();
                    HttpResponse<String> retryResp =
                            httpClient.send(retry, HttpResponse.BodyHandlers.ofString());
                    rateLimitTracker.updateRateLimitInfo(retryResp);
                    if (retryResp.statusCode() == 200) {
                        future.complete(retryResp.body());
                    } else if (retryResp.statusCode() == 404) {
                        logger.debug("Whitelist file not found for Calagopus server {}", serverId);
                        future.complete("[]");
                    } else {
                        future.completeExceptionally(
                                new RuntimeException("HTTP Error " + retryResp.statusCode()));
                    }
                    return;
                }
                logger.error(
                        "Failed to fetch Calagopus whitelist for {}: HTTP {}",
                        serverId,
                        response.statusCode());
                future.completeExceptionally(new RuntimeException("HTTP Error " + response.statusCode()));
            } catch (Exception e) {
                logger.error("Error fetching Calagopus whitelist for {}: {}", serverId, e.getMessage());
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    @Override
    public boolean isApiKeyValid(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return false;
        }
        // c7sp_ + 43 chars = 48 (documented by calagopus-mcp)
        if (!apiKey.startsWith("c7sp_") || apiKey.length() < 10) {
            logger.debug("Calagopus API key has unexpected shape (expected c7sp_...).");
        }
        if (!rateLimitTracker.canMakeRequest()) {
            logger.warn("Rate limit reached. Cannot validate Calagopus API key right now.");
            return false;
        }
        try {
            HttpResponse<String> response =
                    httpClient.send(
                            authorized("api/client/account").GET().build(),
                            HttpResponse.BodyHandlers.ofString());
            rateLimitTracker.updateRateLimitInfo(response);
            int status = response.statusCode();
            if (status == 401 || status == 403) {
                logger.debug("Calagopus API key validation failed: HTTP {}.", status);
                return false;
            }
            if (status >= 500) {
                logger.warn("Calagopus API key validation inconclusive: HTTP {}.", status);
                return true;
            }
            return status >= 200 && status < 300;
        } catch (Exception e) {
            logger.warn("Calagopus API key validation inconclusive: {}.", e.getMessage());
            return false;
        }
    }

    private String extractString(String json, String key) {
        int idx = json.indexOf(key);
        if (idx == -1) return null;
        int colon = json.indexOf(':', idx);
        if (colon == -1) return null;
        int firstQuote = json.indexOf('"', colon + 1);
        if (firstQuote == -1) return null;
        int secondQuote = json.indexOf('"', firstQuote + 1);
        if (secondQuote == -1) return null;
        return json.substring(firstQuote + 1, secondQuote);
    }

    private long extractLong(String json, String key) {
        try {
            int idx = json.indexOf(key);
            if (idx == -1) return 0L;
            int colon = json.indexOf(':', idx);
            if (colon == -1) return 0L;
            int end = json.indexOf(',', colon + 1);
            if (end == -1) end = json.indexOf('}', colon + 1);
            String val =
                    (end == -1 ? json.substring(colon + 1) : json.substring(colon + 1, end))
                            .replaceAll("[^0-9-]", "")
                            .trim();
            if (val.isEmpty()) return 0L;
            return Long.parseLong(val);
        } catch (Exception e) {
            return 0L;
        }
    }

    private double extractDouble(String json, String key) {
        try {
            int idx = json.indexOf(key);
            if (idx == -1) return 0.0;
            int colon = json.indexOf(':', idx);
            if (colon == -1) return 0.0;
            int end = json.indexOf(',', colon + 1);
            if (end == -1) end = json.indexOf('}', colon + 1);
            String val =
                    (end == -1 ? json.substring(colon + 1) : json.substring(colon + 1, end))
                            .replaceAll("[^0-9.\\-]", "")
                            .trim();
            if (val.isEmpty()) return 0.0;
            return Double.parseDouble(val);
        } catch (Exception e) {
            return 0.0;
        }
    }

    private long extractNestedLong(String json, String parentKey, String childKey) {
        try {
            int parentIdx = json.indexOf(parentKey);
            int searchStart = parentIdx >= 0 ? parentIdx : 0;
            int childIdx = json.indexOf(childKey, searchStart);
            if (childIdx == -1) return -1L;
            int colon = json.indexOf(':', childIdx);
            if (colon == -1) return -1L;
            int end = json.indexOf(',', colon + 1);
            if (end == -1) end = json.indexOf('}', colon + 1);
            String val =
                    (end == -1 ? json.substring(colon + 1) : json.substring(colon + 1, end))
                            .replaceAll("[^0-9-]", "")
                            .trim();
            if (val.isEmpty()) return -1L;
            return Long.parseLong(val);
        } catch (Exception e) {
            return -1L;
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) return "null";
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }
}
