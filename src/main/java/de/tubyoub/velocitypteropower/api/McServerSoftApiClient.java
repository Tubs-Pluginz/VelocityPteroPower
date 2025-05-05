package de.tubyoub.velocitypteropower.api;

import com.velocitypowered.api.proxy.ProxyServer;
import de.tubyoub.velocitypteropower.VelocityPteroPower;

import java.awt.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.tubyoub.velocitypteropower.manager.ConfigurationManager;
import de.tubyoub.velocitypteropower.util.RateLimitTracker;
import org.slf4j.Logger;

public class McServerSoftApiClient extends AbstractPanelAPIClient {

    /**
     * Constructs a PterodactylAPIClient.
     *
     * @param plugin The main VelocityPteroPower plugin instance.
     */
    public McServerSoftApiClient(VelocityPteroPower plugin){
        super(plugin);
    }

    /**
     * Sends a power signal to a Pterodactyl server.
     *
     * @param serverId The Pterodactyl server identifier (UUID).
     * @param signal   The power action (START, STOP, RESTART, KILL).
     */
    @Override
    public void powerServer(String serverId, PowerSignal signal) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(configurationManager.getPterodactylUrl() + "api/v2/servers/" + serverId + "/execute/action"))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("apiKey",  configurationManager.getPterodactylApiKey())
                .POST(HttpRequest.BodyPublishers.ofString("{\"action\": \"" + signal.getApiSignal() + "\"}"))
                .build();

            httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            logger.error("Error powering server.", e);
        }
    }

     /**
     * Checks if a server is online using the configured method (Velocity Ping or Pterodactyl API).
     *
     * @param serverName The name of the server as registered in Velocity.
     * @param serverId   The Pterodactyl server identifier (UUID).
     * @return {@code true} if the server is considered online, {@code false} otherwise.
     */
    @Override
    public boolean isServerOnline(String serverName, String serverId) {
        ConfigurationManager.ServerCheckMethod method =
            configurationManager.getServerCheckMethod();

        switch (method) {
            case VELOCITY_PING:
                return checkOnlineViaVelocityPing(serverName);
            case PANEL_API:
                return checkOnlineViaPanelApi(serverName, serverId);
            default:
               // Should not happen with enum, but just in case
                logger.error(
                    "Unknown ServerCheckMethod: {}. Defaulting to false.",
                    method
                );
                return false;
        }
    }

    /**
     * Checks server status by querying the Pterodactyl API /resources endpoint.
     * Includes retry logic for specific HTTP/2 GOAWAY errors.
     *
     * @param serverName The name of the server (for logging).
     * @param serverId   The Pterodactyl server identifier (UUID).
     * @return True if the API reports the server state as "running", false otherwise.
     */
    protected boolean checkOnlineViaPanelApi(String serverName, String serverId) {
        if (serverId == null || serverId.isBlank()) {
            logger.error(
                "Cannot perform API check: Server ID is missing for server '{}'.",
                serverName
            );
            return false;
        }

        int maxRetries = 3; // Max retries specifically for GOAWAY errors
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(
                        URI.create(
                            configurationManager.getPterodactylUrl() +
                            "api/v2/servers/" +
                            serverId +
                            "?filter=Status"
                        )
                    )
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header(
                        "apiKey", configurationManager.getPterodactylApiKey()
                    )
                    .GET()
                    .timeout(Duration.ofSeconds(10)) // Timeout for the API request
                    .build();

                // Use synchronous send here as the result is needed immediately
                HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                String responseBody = response.body();
                if (response.statusCode() == 200 && responseBody != null) {
                    // Check if the state attribute indicates running
                    // A more robust solution would parse the JSON properly
                    boolean running =
                        responseBody.contains("\"status\":\"1\"");
                    logger.debug(
                        "API check for {} (ID {}): Status {}, State Running: {}",
                        serverName,
                        serverId,
                        response.statusCode(),
                        running
                    );
                    return running;
                } else {
                    logger.warn(
                        "API check for {} (ID {}) failed with status code: {} Body: {}",
                        serverName,
                        serverId,
                        response.statusCode(),
                        responseBody != null ? responseBody.substring(0, Math.min(responseBody.length(), 100)) + "..." : "null" // Log truncated body
                    );
                    return false; // Non-200 or null body means not running or error
                }
            } catch (IOException e) {
                // Check specifically for GOAWAY which might indicate HTTP/2 issues
                if (e.getMessage() != null && e.getMessage().contains("GOAWAY")) {
                    logger.warn(
                        "API check for {} (ID {}) received GOAWAY (Attempt {}/{}). Retrying...",
                        serverName,
                        serverId,
                        attempt,
                        maxRetries
                    );
                    if (attempt == maxRetries) {
                        logger.error(
                            "API check for {} (ID {}) failed after {} retries due to GOAWAY: {}",
                            serverName,
                            serverId,
                            maxRetries,
                            e.getMessage()
                        );
                        return false; // Failed after retries
                    }
                    try {
                        Thread.sleep(
                            1000 * attempt
                        ); // Simple exponential backoff
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        logger.warn(
                            "API check retry sleep interrupted for {} (ID {}).",
                            serverName,
                            serverId
                        );
                        return false; // Interrupted during sleep
                    }
                    // Continue to the next iteration of the loop
                } else {
                    // Other IOExceptions
                    logger.error(
                        "API check for {} (ID {}) failed with IOException: {}",
                        serverName,
                        serverId,
                        e.getMessage(),
                        e
                    );
                    return false; // Other I/O error
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn(
                    "API check for {} (ID {}) interrupted.",
                    serverName,
                    serverId
                );
                return false;
            } catch (Exception e) { // Catch unexpected errors
                logger.error(
                    "Unexpected error during API check for {} (ID {}): {}",
                    serverName,
                    serverId,
                    e.getMessage(),
                    e
                );
                return false;
            }
        }
        // Should only be reached if all retries failed
        return false;
    }


    @Override
    public CompletableFuture<String> fetchWhitelistFile(String serverId) {
        logger.warn("MC Server Soft does not Support whitelist fetching");
        return null;
    }
    public boolean isApiKeyValid(String apiKey) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(configurationManager.getPterodactylUrl() + "api/v2/"))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("apiKey",  configurationManager.getPterodactylApiKey())
                .GET()
                .build();
            HttpResponse response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            rateLimitTracker.updateRateLimitInfo(response);
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                logger.debug("Checking for valid ApiKey returned 401/403");
                return false;
            }else {
                return true;
            }
        } catch (Exception e) {
            logger.error("Error checking for valid ApiKey server.", e);
        }
        return false;
    }

}
