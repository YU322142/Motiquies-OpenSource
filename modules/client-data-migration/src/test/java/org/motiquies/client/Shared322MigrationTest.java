package org.motiquies.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

public final class Shared322MigrationTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("shared322-");
        Path source = Files.createDirectory(root.resolve("Motiquies-3.2.2-release"));
        Path target = Files.createDirectory(root.resolve("Motiquies-3.2.3-release"));
        String waypoint = "waypoint:Keep 322:K:12:70:34:0:false:0:gui.xaero_default:false:0:0:false\n";
        String bookmark = "[{\"version\":2},{\"ingredient\":{\"id\":\"minecraft:stone\"},\"type\":\"item_stack\",\"bookmarkType\":\"INGREDIENT\"}]";
        String history = bookmark.replace("\"version\":2", "\"version\":1");
        put(source, "xaero/minimap/Multiplayer_Any Address/dim%0/mw$322_1.txt", waypoint);
        put(source, "config/jei/world/server/Motiquies/bookmarks.json", bookmark);
        put(source, "config/jei/world/server/Motiquies/lookupHistory.json", history);
        put(source, "Distant_Horizons_server_data/old/DistantHorizons.sqlite", "Do not migrate this DH cache");
        Migration migration = new Migration(target);
        var preview = migration.preview(source);
        check(preview.waypoints().contains("Multiplayer_Any Address"), "3.2.2 shared waypoint source must be discoverable");
        check(preview.bookmarks().contains("Motiquies") && preview.history().contains("Motiquies"), "3.2.2 shared JEI sources must be discoverable");
        migration.migrate(source, preview.selection(true, true, true, false, false, false), Set.of());
        migration.migrate(source, preview.selection(true, true, true, false, false, false), Set.of());
        check(Files.readString(target.resolve("xaero/minimap/Multiplayer_Any Address/dim%0/mw$322_1.txt")).equals(waypoint), "322 waypoint survives and retries do not duplicate");
        check(Files.readString(target.resolve("config/jei/world/server/Motiquies/bookmarks.json")).contains("minecraft:stone"), "322 bookmark survives");
        check(Files.readString(target.resolve("config/jei/world/server/Motiquies/lookupHistory.json")).contains("minecraft:stone"), "322 history survives");
        check(Files.readString(source.resolve("config/jei/world/server/Motiquies/bookmarks.json")).equals(bookmark), "Source JEI unchanged");
        check(!Files.exists(target.resolve("Distant_Horizons_server_data")), "Old DH must not be imported");
        put(target, "config/motiquies-client/previous-version.txt", "3.2.2\n");
        put(target, "config/motiquies-client/previous-instance.txt", source.toString());
        check(migration.upgradeSource().orElseThrow().equals(source), "Explicit 322 handoff must identify old instance");
        Path empty = Files.createDirectory(root.resolve("empty"));
        put(empty, "config/motiquies-client/previous-version.txt", "3.2.2\n");
        Migration emptyMigration = new Migration(empty);
        check(emptyMigration.upgradeSource().orElseThrow().equals(empty), "Empty 322 upgrade must not depend on personal data");
        emptyMigration.markComplete();
        check(emptyMigration.upgradeSource().isEmpty(), "Finished upgrade must not repeat");
        check(!Migration.isLegacy("3.2.2"), "Historical <=321 definition remains unchanged");
        System.out.println("Shared 3.2.2 migration tests passed");
    }
    private static void put(Path root, String name, String value) throws Exception {
        Path f=root.resolve(name);Files.createDirectories(f.getParent());Files.writeString(f,value);
    }
    private static void check(boolean result,String message) { if(!result) throw new AssertionError(message); }
}
