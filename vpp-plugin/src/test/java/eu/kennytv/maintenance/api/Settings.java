package eu.kennytv.maintenance.api;

import java.util.UUID;

public interface Settings {
    boolean isWhitelisted(UUID uuid);
}
