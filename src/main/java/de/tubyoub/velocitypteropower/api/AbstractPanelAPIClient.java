package de.tubyoub.velocitypteropower.api;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import de.tubyoub.velocitypteropower.VelocityPteroPower;
import de.tubyoub.velocitypteropower.manager.ConfigurationManager;
import de.tubyoub.velocitypteropower.util.RateLimitTracker;
import org.slf4j.Logger;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.*;

public abstract class AbstractPanelAPIClient implements PanelAPIClient {

    protected final Logger logger;
    protected final ConfigurationManager configurationManager;
    protected final ProxyServer proxyServer;
    protected final VelocityPteroPower plugin;
    protected final RateLimitTracker rateLimitTracker;

    protected final HttpClient httpClient;
    protected final ExecutorService executorService;

    /**
     * Constructs a base API client with common dependencies.
     *
     * @param plugin The main VelocityPteroPower plugin instance.
     */
    public AbstractPanelAPIClient(VelocityPteroPower plugin) {
        this.plugin = plugin;
        this.logger = plugin.getFilteredLogger();
        this.configurationManager = plugin.getConfigurationManager();
        this.proxyServer = plugin.getProxyServer();
        this.rateLimitTracker = plugin.getRateLimitTracker();

        this.executorService = Executors.newFixedThreadPool(configurationManager.getApiThreads());
        this.httpClient = HttpClient.newBuilder()
                .executor(executorService)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Checks if a server is online using the configured method (Velocity Ping or Panel API).
     *
     * @param serverName The name of the server as registered in Velocity.
     * @param serverId   The panel server identifier (UUID).
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
     * Checks if a server is online by pinging it through Velocity.
     *
     * @param serverName The name of the server as registered in Velocity.
     * @return {@code true} if the server responds to ping, {@code false} otherwise.
     */
    protected boolean checkOnlineViaVelocityPing(String serverName) {
        Optional<RegisteredServer> serverOptional =
            proxyServer.getServer(serverName);
        if (serverOptional.isEmpty()) {
            logger.debug(
                "Cannot perform PING check: Server '{}' not registered in Velocity.",
                serverName
            );
            return false; // Server not known to Velocity
        }

        RegisteredServer server = serverOptional.get();
        try {
            CompletableFuture<ServerPing> pingFuture = server.ping();
            ServerPing pingResult = pingFuture.get(
                configurationManager.getPingTimeout(),
                TimeUnit.MILLISECONDS
            );
            boolean online = pingResult != null;
            logger.debug(
                "Ping check for {}: {}",
                serverName,
                online ? "Success" : "Failed (No result/Timeout)"
            );
            return online;
        } catch (TimeoutException e) {
            logger.debug(
                "Ping check for {} timed out after {}ms.",
                serverName,
                configurationManager.getPingTimeout()
            );
            return false;
        } catch (ExecutionException e) {
            // Log the underlying cause if possible
            Throwable cause = e.getCause();
            logger.debug(
                "Ping check for {} failed: {}",
                serverName,
                cause != null ? cause.getMessage() : e.getMessage()
            );
            return false;
        } catch (InterruptedException e) {
            logger.warn("Ping check for {} interrupted.", serverName);
            Thread.currentThread().interrupt(); // Re-interrupt the thread
            return false;
        } catch (Exception e) { // Catch unexpected errors during ping
            logger.warn(
                "Unexpected error pinging server {}: {}",
                serverName,
                e.getMessage(),
                e
            );
            return false;
        }
    }

    /**
     * Checks if a server is online by querying the panel API.
     * This method should be implemented by each specific API client.
     *
     * @param serverName The name of the server as registered in Velocity.
     * @param serverId   The panel server identifier (UUID).
     * @return {@code true} if the server is online according to the panel, {@code false} otherwise.
     */
    protected abstract boolean checkOnlineViaPanelApi(String serverName, String serverId);

    /**
     * Checks if a server registered in Velocity has any players connected.
     *
     * @param serverName The name of the server as registered in Velocity.
     * @return {@code true} if the server has no players or is not found, {@code false} otherwise.
     */
    @Override
    public boolean isServerEmpty(String serverName) {
        return proxyServer.getServer(serverName)
            .map(server -> server.getPlayersConnected().isEmpty())
            .orElse(true); // Treat non-existent server as empty
    }

    /**
     * Shuts down the executor service used for API requests.
     */
    public void shutdown() {
        executorService.shutdownNow();
    }
}
