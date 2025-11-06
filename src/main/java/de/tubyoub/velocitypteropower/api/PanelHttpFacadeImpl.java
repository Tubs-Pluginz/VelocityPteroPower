package de.tubyoub.velocitypteropower.api;

import de.tubyoub.vpp.api.PanelHttpFacade;
import de.tubyoub.vpp.api.PanelHttpResponse;
import de.tubyoub.velocitypteropower.manager.ConfigurationManager;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class PanelHttpFacadeImpl implements PanelHttpFacade {
    private final ConfigurationManager config;
    private final HttpClient client;

    public PanelHttpFacadeImpl(ConfigurationManager config, HttpClient client) {
        this.config = config;
        this.client = client;
    }

    @Override
    public URI resolve(String pathOrRelativeUri) {
        String base = config.getPterodactylUrl();
        if (!base.endsWith("/")) base += "/";
        if (pathOrRelativeUri.startsWith("/")) pathOrRelativeUri = pathOrRelativeUri.substring(1);
        return URI.create(base + pathOrRelativeUri);
    }

    @Override
    public HttpRequest.Builder request(URI uri) {
        String apiKey = config.getPterodactylApiKey();
        return HttpRequest.newBuilder(uri)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                // Apply Pterodactyl/Pelican style Bearer header; McServerSoft will ignore the extra header
                .header("Authorization", "Bearer " + apiKey)
                // McServerSoft also uses apiKey header; adding both keeps compatibility
                .header("apiKey", apiKey);
    }

    @Override
    public CompletableFuture<PanelHttpResponse> execute(HttpRequest request) {
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> new PanelHttpResponse() {
                    @Override public int statusCode() { return resp.statusCode(); }
                    @Override public String body() { return resp.body(); }
                    @Override public Map<String, List<String>> headers() { return resp.headers().map(); }
                });
    }
}
