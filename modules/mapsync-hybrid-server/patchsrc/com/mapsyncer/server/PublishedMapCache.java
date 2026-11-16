package com.mapsyncer.server;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class PublishedMapCache {
    private static final Logger LOGGER = LoggerFactory.getLogger(PublishedMapCache.class);
    private static final String ACTIVE = "motiquies.mapsync.active";
    private static final String SNAPSHOT = "motiquies.mapsync.snapshot";

    private PublishedMapCache() {}

    static boolean isEnabled() {
        return !System.getProperty(ACTIVE, "").isBlank();
    }

    static boolean isConverter() {
        return !System.getProperty(SNAPSHOT, "").isBlank();
    }

    static Path converterWorld(Path actual) {
        return isConverter() ? Path.of(System.getProperty(SNAPSHOT)).toAbsolutePath().normalize() : actual;
    }

    static Path currentRoot() {
        Path state = Path.of(System.getProperty(ACTIVE)).toAbsolutePath().normalize();
        Path unavailable = state.getParent().resolve(".motiquies-map-unavailable");
        if (!Files.isRegularFile(state)) return unavailable;
        try {
            Path root = state.getParent().toRealPath();
            var manifest = JsonParser.parseString(Files.readString(state)).getAsJsonObject();
            Path configured = Path.of(manifest.getAsJsonObject("artifacts").get("xaero").getAsString());
            if (configured.isAbsolute()) throw new IOException("Xaero cache path must be relative");
            Path directory = root.resolve(configured).normalize();
            if (!directory.startsWith(root) || !Files.isDirectory(directory)) {
                throw new IOException("Xaero cache path outside publisher workspace or absent");
            }
            Path pinned = directory.toRealPath();
            if (!pinned.startsWith(root) || !Files.isRegularFile(pinned.resolve("generation_cache.properties"))) {
                throw new IOException("Xaero cache has no published generation metadata");
            }
            return pinned;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Cannot load paired Xaero publication {}", state, e);
            return unavailable;
        }
    }
}
