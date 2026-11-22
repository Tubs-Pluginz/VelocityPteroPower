package de.tubyoub.vpp.api;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable metadata describing a VPP-managed addon.
 */
public final class AddonMeta {
    private final String id;
    private final String name;
    private final String version;
    private final List<String> authors;
    private final String description;
    private final String apiVersion;

    public AddonMeta(String id, String name, String version, List<String> authors, String description, String apiVersion) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("addon id must not be blank");
        this.id = id.toLowerCase(java.util.Locale.ROOT).trim();
        this.name = name == null || name.isBlank() ? this.id : name;
        this.version = version == null || version.isBlank() ? "0.0.0" : version;
        this.authors = authors == null ? List.of() : List.copyOf(authors);
        this.description = description == null ? "" : description;
        this.apiVersion = apiVersion == null || apiVersion.isBlank() ? "1.0.0" : apiVersion;
    }

    public String id() { return id; }
    public String name() { return name; }
    public String version() { return version; }
    public List<String> authors() { return authors; }
    public String description() { return description; }
    public String apiVersion() { return apiVersion; }

    public String authorsJoined() {
        return authors.isEmpty() ? "unknown" : String.join(", ", authors);
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    public static final class Builder {
        private final String id;
        private String name;
        private String version = "1.0.0";
        private List<String> authors = Collections.emptyList();
        private String description = "";
        private String apiVersion = "1.0.0";

        private Builder(String id) {
            this.id = Objects.requireNonNull(id, "id");
        }

        public Builder name(String name) { this.name = name; return this; }
        public Builder version(String version) { this.version = version; return this; }
        public Builder authors(List<String> authors) { this.authors = authors; return this; }
        public Builder authors(String... authors) { this.authors = List.of(authors); return this; }
        public Builder description(String description) { this.description = description; return this; }
        public Builder apiVersion(String apiVersion) { this.apiVersion = apiVersion; return this; }

        public AddonMeta build() {
            return new AddonMeta(id, name, version, authors, description, apiVersion);
        }
    }
}
