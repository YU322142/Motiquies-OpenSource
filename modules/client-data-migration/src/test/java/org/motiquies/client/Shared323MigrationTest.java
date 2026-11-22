package org.motiquies.client;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class Shared323MigrationTest {
    private static final String WAYPOINT = "xaero/minimap/Multiplayer_Any Address/dim%0/mw$323_1.txt";
    private static final String BOOKMARKS = "config/jei/world/server/Motiquies/bookmarks.json";
    private static final String HISTORY = "config/jei/world/server/Motiquies/lookupHistory.json";
    private static final String SOURCE_POINT = "waypoint:Home:H:12:70:34:0:false:0:gui.xaero_default:false:0:0:false\n";
    private static final String TARGET_POINT = "waypoint:Home:H:56:80:78:0:false:0:gui.xaero_default:false:0:0:false\n";
    private static final String CACHE = "Distant_Horizons_server_data/-1921347326_Motiquies/world/DistantHorizons.sqlite";
    private static final String MIGRATION_MARKER = "config/motiquies-client/migration-v1.done";
    private static final String DH_MARKER = "config/motiquies-client/dh-native-v1.done";

    public static void main(String[] args) throws Exception {
        int failures = 0;
        failures += run("3.2.3 numeric marker identifies an empty in-place upgrade",
                () -> emptyUpgrade("3.2.3\r\n"));
        failures += run("3.2.3 release ID marker identifies an empty in-place upgrade",
                () -> emptyUpgrade(" \tMotiquies-3.2.3-release\r\n"));
        failures += run("3.2.3 cross-directory handoff merges shared data without changing the source",
                Shared323MigrationTest::crossDirectoryUpgrade);
        failures += run("completed migration and DH rules retain markers and new native data",
                Shared323MigrationTest::completedRulesStayComplete);
        failures += run("fresh installation remains distinct from an empty upgrade",
                Shared323MigrationTest::freshInstall);
        failures += run("unsupported version markers do not authorize automatic migration",
                Shared323MigrationTest::unsupportedVersions);
        failures += run("historical legacy and 3.2.2 upgrade recognition are retained",
                Shared323MigrationTest::olderUpgrades);
        if (failures != 0) throw new AssertionError("Shared 3.2.3 migration failures: " + failures);
        System.out.println("Shared 3.2.3 migration tests passed (7 scenarios)");
    }

    private static void emptyUpgrade(String previousVersion) throws Exception {
        Path root = Files.createTempDirectory("shared323-empty-");
        Path target = Files.createDirectory(root.resolve("Motiquies-3.3.0-release"));
        put(target, "config/motiquies-client/previous-version.txt", previousVersion);
        var migration = new Migration(target);
        check(migration.upgradeSource().filter(target::equals).isPresent(),
                "An explicit supported previous version must identify the in-place upgrade without personal data");
        var preview = migration.preview(target);
        check(preview.isEmpty(), "Empty personal data must be a valid upgrade preview");
        migration.migrate(target, migration.automaticSelection(preview), Set.of());
        migration.markComplete();
        check(migration.upgradeSource().isEmpty() && migration.isComplete(),
                "Completed empty upgrades must not repeat");
        check(!migration.introSeen(), "The upgrade flow must not need a new-install intro marker");
    }

    private static void crossDirectoryUpgrade() throws Exception {
        Path root = Files.createTempDirectory("shared323-handoff-");
        Path source = Files.createDirectory(root.resolve("Motiquies-3.2.3-release"));
        Path target = Files.createDirectory(root.resolve("Motiquies-3.3.0-release"));
        put(source, WAYPOINT, SOURCE_POINT);
        put(source, BOOKMARKS, jei(2, "minecraft:stone"));
        put(source, HISTORY, jei(1, "minecraft:stone"));
        put(source, MIGRATION_MARKER, "1\n");
        put(source, DH_MARKER, "motiquies-native-dh-v1\n");
        put(source, CACHE, "Native source cache stays with its instance");
        put(source, "xaero/minimap/Multiplayer_unrelated.example/dim%0/mw$323_1.txt", "Unrelated server");
        put(source, "config/jei/world/server/Other (unrelated_example)/bookmarks.json", jei(2, "minecraft:diamond"));
        put(target, WAYPOINT, TARGET_POINT);
        put(target, BOOKMARKS, jei(2, "minecraft:glass"));
        put(target, HISTORY, jei(1, "minecraft:glass"));
        put(target, "config/motiquies-client/previous-version.txt", "Motiquies-3.2.3-release\n");
        put(target, "config/motiquies-client/previous-instance.txt", source.toString() + "\n");
        Map<String, String> originalSource = snapshot(source);
        var migration = new Migration(target);
        check(migration.upgradeSource().filter(source::equals).isPresent(),
                "Supported version and absolute handoff must select the old instance");
        var preview = migration.preview(source);
        check(preview.waypoints().equals(java.util.List.of("Multiplayer_Any Address")),
                "Shared waypoints must be offered while unrelated route data remains excluded");
        check(preview.history().equals(java.util.List.of("Motiquies"))
                        && preview.bookmarks().equals(java.util.List.of("Motiquies")),
                "Shared JEI history and bookmarks must be offered independently");
        var selection = migration.automaticSelection(preview);
        migration.migrate(source, selection, Set.of());
        migration.migrate(source, selection, Set.of());
        String waypoints = Files.readString(target.resolve(WAYPOINT));
        check(waypoints.contains(SOURCE_POINT) && waypoints.contains(TARGET_POINT)
                        && waypoints.lines().filter(line -> line.startsWith("waypoint:")).count() == 2,
                "Shared merge must retain different coordinates and deduplicate retries");
        assertJeiMerged(target, BOOKMARKS, 2);
        assertJeiMerged(target, HISTORY, 1);
        check(snapshot(source).equals(originalSource), "All source data and rule markers must be unchanged");
        check(!Files.exists(target.resolve("Distant_Horizons_server_data")),
                "Cross-instance personal import must not import DH cache");
        check(!migration.isComplete(), "The source completion marker must not bypass target migration");
        migration.markComplete();
        check(migration.upgradeSource().isEmpty() && !migration.introSeen(),
                "Completed cross-instance upgrade must not repeat or require an intro marker");
    }

    private static void completedRulesStayComplete() throws Exception {
        Path target = Files.createTempDirectory("shared323-completed-");
        put(target, "config/motiquies-client/previous-version.txt", "3.2.3\n");
        Path personalMarker = put(target, MIGRATION_MARKER, "1\n");
        Path dhMarker = put(target, DH_MARKER, "motiquies-native-dh-v1\n");
        put(target, WAYPOINT, SOURCE_POINT);
        put(target, BOOKMARKS, jei(2, "minecraft:stone"));
        put(target, CACHE, "Fresh native data downloaded after the previous reset");
        FileTime completedAt = FileTime.fromMillis(1_700_000_000_000L);
        Files.setLastModifiedTime(personalMarker, completedAt);
        Files.setLastModifiedTime(dhMarker, completedAt);
        Map<String, String> before = snapshot(target);
        var migration = new Migration(target);
        check(migration.isComplete() && migration.upgradeSource().isEmpty() && !migration.introSeen(),
                "An existing completed rule must suppress automatic migration and the new-install branch");
        check(DhCacheMigration.migrate(target, Set.of("-1921347326_Motiquies")).isEmpty(),
                "A completed DH rule must skip its reset");
        check(snapshot(target).equals(before), "Existing native cache, personal data and markers must survive");
        check(Files.getLastModifiedTime(personalMarker).equals(completedAt)
                        && Files.getLastModifiedTime(dhMarker).equals(completedAt),
                "Upgrade recognition must not rewrite completed rules");
    }

    private static void freshInstall() throws Exception {
        Path target = Files.createTempDirectory("shared323-fresh-");
        var migration = new Migration(target);
        check(migration.upgradeSource().isEmpty() && migration.preview(target).isEmpty()
                        && !migration.isComplete() && !migration.introSeen(),
                "An empty new installation must remain eligible for its first intro");
        migration.markIntroSeen();
        check(migration.introSeen() && !migration.isComplete(),
                "Showing the new-install intro must not pretend personal migration completed");
    }

    private static void unsupportedVersions() throws Exception {
        Path target = Files.createTempDirectory("shared323-unsupported-");
        var migration = new Migration(target);
        for (String version : java.util.List.of("3.2.4", "3.3.0", "Motiquies-3.2.3-release-extra", "unknown")) {
            put(target, "config/motiquies-client/previous-version.txt", version);
            check(migration.upgradeSource().isEmpty(), "Unconfirmed version must not trigger auto import: " + version);
        }
    }

    private static void olderUpgrades() throws Exception {
        check(Migration.isLegacy("3.2.1") && Migration.isLegacy("Motiquies-3.2.0-release")
                        && !Migration.isLegacy("3.2.2") && !Migration.isLegacy("3.2.3"),
                "The historical <=3.2.1 legacy definition must not grow with supported upgrades");
        Path target = Files.createTempDirectory("shared323-older-");
        var migration = new Migration(target);
        for (String version : java.util.List.of("3.2.1", "Motiquies-3.2.0-release", "3.2.2", "Motiquies-3.2.2-release")) {
            put(target, "config/motiquies-client/previous-version.txt", version);
            check(migration.upgradeSource().orElseThrow().equals(target), "Existing upgrade support must remain: " + version);
        }
    }

    private static void assertJeiMerged(Path target, String file, int version) throws Exception {
        var records = JsonParser.parseString(Files.readString(target.resolve(file))).getAsJsonArray();
        check(records.size() == 3 && records.get(0).getAsJsonObject().get("version").getAsInt() == version,
                "JEI merge must retain both unique records and the installed format version");
        var ids = new java.util.LinkedHashSet<String>();
        for (int index = 1; index < records.size(); index++) {
            ids.add(records.get(index).getAsJsonObject().getAsJsonObject("ingredient").get("id").getAsString());
        }
        check(ids.equals(Set.of("minecraft:stone", "minecraft:glass")), "Existing target JEI and imported JEI must survive");
    }

    private static String jei(int version, String item) {
        return "[{\"version\":" + version + "},{\"ingredient\":{\"id\":\"" + item
                + "\"},\"type\":\"item_stack\",\"bookmarkType\":\"INGREDIENT\"}]";
    }

    private static Map<String, String> snapshot(Path root) throws Exception {
        var result = new LinkedHashMap<String, String>();
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                result.put(root.relativize(file).toString(), Files.readString(file));
            }
        }
        return result;
    }

    private interface Scenario { void run() throws Exception; }

    private static int run(String name, Scenario scenario) {
        try {
            scenario.run();
            System.out.println("PASS " + name);
            return 0;
        } catch (Exception | AssertionError failure) {
            System.err.println("FAIL " + name + " - " + failure);
            return 1;
        }
    }

    private static Path put(Path root, String name, String value) throws Exception {
        Path file = root.resolve(name);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, value);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
