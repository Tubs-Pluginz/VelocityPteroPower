package de.tubyoub.velocitypteropower.addon;

import java.util.ArrayList;
import java.util.List;

/**
 * One entry from the official (or extra) addon catalog.
 */
public final class AddonCatalogEntry {
    private String id;
    private String name;
    private String description;
    private List<String> authors = new ArrayList<>();
    private String latestVersion;
    private String downloadUrl;
    private String homepage;
    private String apiVersion;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public List<String> getAuthors() { return authors; }
    public void setAuthors(List<String> authors) { this.authors = authors != null ? authors : new ArrayList<>(); }
    public String getLatestVersion() { return latestVersion; }
    public void setLatestVersion(String latestVersion) { this.latestVersion = latestVersion; }
    public String getDownloadUrl() { return downloadUrl; }
    public void setDownloadUrl(String downloadUrl) { this.downloadUrl = downloadUrl; }
    public String getHomepage() { return homepage; }
    public void setHomepage(String homepage) { this.homepage = homepage; }
    public String getApiVersion() { return apiVersion; }
    public void setApiVersion(String apiVersion) { this.apiVersion = apiVersion; }

    public String authorsJoined() {
        if (authors == null || authors.isEmpty()) return "unknown";
        return String.join(", ", authors);
    }
}
