package de.tubyoub.velocitypteropower.addon;

import java.util.ArrayList;
import java.util.List;

public final class AddonCatalogDocument {
    private int schemaVersion = 1;
    private List<AddonCatalogEntry> addons = new ArrayList<>();

    public int getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(int schemaVersion) { this.schemaVersion = schemaVersion; }
    public List<AddonCatalogEntry> getAddons() { return addons; }
    public void setAddons(List<AddonCatalogEntry> addons) {
        this.addons = addons != null ? addons : new ArrayList<>();
    }
}
