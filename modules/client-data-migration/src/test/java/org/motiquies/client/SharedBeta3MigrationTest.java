package org.motiquies.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

public final class SharedBeta3MigrationTest {
    private static final String CONFIG = "config/motiquies-client/";
    private static final String WAYPOINT = "xaero/minimap/Multiplayer_Any Address/dim%0/mw$beta3_1.txt";
    private static final String CACHE = "Distant_Horizons_server_data/-1921347326_Motiquies/overworld/DistantHorizons.sqlite";

    public static void main(String[] args) throws Exception {
        verifyUpgrade("3.3.0-Beta3");
        verifyUpgrade("3.3.0-Beta4");
    }

    private static void verifyUpgrade(String version) throws Exception {
        Path root = Files.createTempDirectory("upgrade-");
        Path target = Files.createDirectory(root.resolve("target"));
        Path source = Files.createDirectory(root.resolve("source"));
        Migration migration = new Migration(target);

        put(target, CONFIG + "previous-version.txt", "Motiquies-" + version + "\n");
        require(migration.upgradeSource().filter(target::equals).isPresent(), "Empty upgrade should recognize " + version);
        require(migration.preview(target).isEmpty(), "Empty upgrade should have no personal data");

        put(target, CONFIG + "previous-version.txt", version + "\n");
        put(target, CONFIG + "previous-instance.txt", source.toString());
        String point = "waypoint:Beta3 Home:H:12:70:34:0:false:0:gui.xaero_default:false:0:0:false\n";
        put(source, WAYPOINT, point);
        put(source, CACHE, "Beta3 source cache fixture");
        put(source, CONFIG + "migration-v1.done", "1\n");
        require(migration.upgradeSource().filter(source::equals).isPresent(), "A separate source should migrate automatically for " + version);
        var preview = migration.preview(source);
        migration.migrate(source, migration.automaticSelection(preview), Set.of());
        migration.markComplete();
        require(Files.readString(target.resolve(WAYPOINT)).equals(point), "Shared waypoint should be migrated");
        require(Files.readString(source.resolve(WAYPOINT)).equals(point), "Source waypoint should remain byte-identical");
        require(Files.readString(source.resolve(CACHE)).equals("Beta3 source cache fixture"), "Source remote cache should stay intact");
        require(Files.notExists(target.resolve(CACHE)), "Personal data import should leave the target's remote cache independent");
        require(migration.upgradeSource().isEmpty() && !migration.introSeen(), "Completed upgrades should not repeat");

        Path existing = Files.createDirectory(root.resolve("Existing"));
        put(existing, CONFIG + "dh-native-v1.done", "motiquies-native-dh-v1\n");
        put(existing, CACHE, "Existing Beta3 download");
        require(DhCacheMigration.migrate(existing, DhCacheMigration.defaultFolders()).isEmpty(), "An in-place upgrade must keep existing native-cache marker semantics");
        require(Files.readString(existing.resolve(CACHE)).equals("Existing Beta3 download"), "Existing download must be preserved");
        System.out.println(version + " empty upgrade, separate-instance import, completion and native-cache protection passed");
    }

    private static void put(Path root, String name, String contents) throws Exception {
        Path path = root.resolve(name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, contents);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
