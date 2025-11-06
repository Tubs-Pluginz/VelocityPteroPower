package de.tubyoub.vpp.api;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Facade for making HTTP requests against the configured panel with auth pre-applied.
 */
public interface PanelHttpFacade {
    /**
     * Build a full URI relative to the panel base URL.
     */
    URI resolve(String pathOrRelativeUri);

    /**
     * Create a request builder with Authorization applied and provided method/body.
     */
    HttpRequest.Builder request(URI uri);

    /**
     * Execute the request and return a response wrapper with status/body/headers.
     */
    CompletableFuture<PanelHttpResponse> execute(HttpRequest request);

    /**
     * Convenience helpers
     */
    default CompletableFuture<PanelHttpResponse> get(String relative) {
        return execute(request(resolve(relative)).GET().build());
    }
    default CompletableFuture<PanelHttpResponse> delete(String relative) {
        return execute(request(resolve(relative)).DELETE().build());
    }
    default CompletableFuture<PanelHttpResponse> postJson(String relative, String jsonBody) {
        String body = jsonBody == null ? "" : jsonBody;
        return execute(request(resolve(relative)).POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }
    default CompletableFuture<PanelHttpResponse> putJson(String relative, String jsonBody) {
        String body = jsonBody == null ? "" : jsonBody;
        return execute(request(resolve(relative)).PUT(HttpRequest.BodyPublishers.ofString(body)).build());
    }
}
