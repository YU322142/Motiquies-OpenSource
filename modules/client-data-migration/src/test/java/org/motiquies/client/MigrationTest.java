package org.motiquies.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

public final class MigrationTest {
    private static final String HEADER = "#\n#waypoint:name:initials:x:y:z:color:disabled:type:set:rotate_on_tp:tp_yaw:visibility_type:destination\n#\n";
    private static final String FIRST = "waypoint:Home:H:1:64:1:0:false:0:gui.xaero_default:false:0:0:false";
    private static final String SECOND = "waypoint:Home:H:2:64:2:0:false:0:gui.xaero_default:false:0:0:false";
    private static final String ITEM = "{\"ingredient\":{\"id\":\"minecraft:stone\"},\"type\":\"item_stack\",\"bookmarkType\":\"INGREDIENT\"}";

    public static void main(String[] args) throws Exception {
        Path work = Files.createTempDirectory("motiquies-client-test-");
        Path old = Files.createDirectory(work.resolve("source"));
        Path target = Files.createDirectory(work.resolve("target"));
        Path first = write(old, "xaero/minimap/Multiplayer_mc.322142.xyz/dim%0/mw$123_1.txt", HEADER + FIRST + "\n");
        write(old, "xaero/minimap/Multiplayer_frp-web.com/dim%0/mw$123_1.txt", HEADER + FIRST + "\n" + SECOND + "\n");
        write(old, "xaero/minimap/Multiplayer_unrelated.example/dim%0/mw$123_1.txt", HEADER + FIRST.replace("Home", "OtherServer") + "\n");
        write(old, "config/jei/world/server/Shanghai (mc_322142_xyz)/lookupHistory.json", "[{\"version\":1}," + ITEM + "]");
        write(old, "config/jei/world/server/Zhenjiang (frp-web_com 43765)/lookupHistory.json", "[{\"version\":1}," + ITEM + "]");
        write(old, "config/jei/world/server/Zhenjiang (frp-web_com 43765)/bookmarks.json", "[{\"version\":2}," + ITEM + "]");
        write(old, "options.txt", "key_key.jump:key.keyboard.j\nkey_unregistered:key.keyboard.a\nfov:100\n");
        write(target, "options.txt", "key_key.jump:key.keyboard.space\nkey_key.sneak:key.keyboard.left.shift\nfov:70\n");
        write(target, "config/xaero/minimap/client.cfg", "differentiate_by_server_address = true\nother = true\n");
        write(target, "config/xaero/world-map/client.cfg", "differentiate_by_server_address = true\n");

        Migration migration = new Migration(target);
        var preview = migration.preview(old);
        check(preview.waypoints().size() == 2, "Only known Motiquies waypoint routes must be offered");
        check(preview.history().size() == 2, "History routes must be discovered");
        var all = preview.selection(true, true, true, true, false, false);
        migration.migrate(old, all);
        migration.migrate(old, all);
        String waypoints = Files.readString(target.resolve("xaero/minimap/Multiplayer_Any Address/dim%0/mw$123_1.txt"));
        check(waypoints.lines().filter(line -> line.startsWith("waypoint:")).count() == 2, "Retry must not duplicate waypoints");
        check(waypoints.contains(FIRST) && waypoints.contains(SECOND), "Same-name different coordinates must survive");
        check(Files.readString(first).equals(HEADER + FIRST + "\n"), "Source must be preserved");
        check(Files.readString(target.resolve("config/jei/world/server/Motiquies/lookupHistory.json")).split("minecraft:stone", -1).length == 2,
                "Identical history entries must be merged once");
        var bookmarks = com.google.gson.JsonParser.parseString(Files.readString(
                target.resolve("config/jei/world/server/Motiquies/bookmarks.json"))).getAsJsonArray();
        check(bookmarks.size() == 2 && bookmarks.get(0).getAsJsonObject().get("version").getAsInt() == 2,
                "Bookmarks produced by installed JEI use version 2 and must survive migration and retry");
        check(Files.readString(target.resolve("options.txt")).equals("key_key.jump:key.keyboard.j\nkey_key.sneak:key.keyboard.left.shift\nfov:70\n"),
                "Import must update existing keys only and leave unrelated settings");
        check(Files.readString(target.resolve("config/xaero/minimap/client.cfg")).equals("differentiate_by_server_address = false\nother = true\n"),
                "Only Xaero address differentiation must change");

        Path emptyUpgrade = Files.createDirectory(work.resolve("empty-upgrade"));
        write(emptyUpgrade, "config/motiquies-client/previous-version.txt", "3.2.1\n");
        Migration empty = new Migration(emptyUpgrade);
        check(empty.upgradeSource().orElseThrow().equals(emptyUpgrade), "Empty upgrade is recognized by metadata");
        check(empty.preview(emptyUpgrade).isEmpty(), "No data must be a valid preview");
        empty.markComplete();
        check(empty.isComplete() && empty.upgradeSource().isEmpty(), "Completed migration must not repeat");

        Path chosen = Files.createDirectory(work.resolve("chosen"));
        Migration selected = new Migration(chosen);
        selected.migrate(old, new Migration.Selection(Set.of("Multiplayer_mc.322142.xyz"), Set.of(), Set.of(), false, false, false));
        check(Files.readString(chosen.resolve("xaero/minimap/Multiplayer_Any Address/dim%0/mw$123_1.txt")).contains(FIRST),
                "A single selected route must be imported");
        check(!Files.readString(chosen.resolve("xaero/minimap/Multiplayer_Any Address/dim%0/mw$123_1.txt")).contains(SECOND),
                "An unselected route must not be imported");
        check(!Files.exists(chosen.resolve("config/jei/world/server/Motiquies/lookupHistory.json")), "Unselected category must not write");
        Path reviewed = Files.createDirectory(work.resolve("reviewed"));
        Migration review = new Migration(reviewed);
        var conflicts = review.conflicts(old, all);
        check(conflicts.size() == 2, "Both differing same-name waypoint records must be shown for review");
        var exclusion = conflicts.stream().filter(item -> item.key().content().equals(SECOND)).findFirst().orElseThrow();
        check(exclusion.source().contains("frp-web.com"), "Conflict must show the source route");
        review.migrate(old, all.withExcluded(Set.of(exclusion.key())));
        String afterReview = Files.readString(reviewed.resolve("xaero/minimap/Multiplayer_Any Address/dim%0/mw$123_1.txt"));
        check(afterReview.contains(FIRST) && !afterReview.contains(SECOND), "Unchecked conflicting record must not be imported");
        write(old, "saves/SharedName/level.dat", "Old world");
        write(old, "saves/SharedName/region/r.0.0.mca", "Only in the old world");
        write(chosen, "saves/SharedName/level.dat", "New world");
        var filesOnly = new Migration.Selection(Set.of(), Set.of(), Set.of(), false, true, false);
        selected.migrate(old, filesOnly);
        selected.migrate(old, filesOnly);
        check(!Files.exists(chosen.resolve("saves/SharedName/region/r.0.0.mca")),
                "An old region must never be injected into a different same-named save");
        check(Files.readString(chosen.resolve("saves/SharedName (导入 1)/level.dat")).equals("Old world"),
                "The old save must be retained as a complete separate save");
        check(!Files.exists(chosen.resolve("saves/SharedName (导入 2)")), "Retry must not duplicate a completed save");
        System.out.println("Migration tests passed");
    }

    private static Path write(Path root, String relative, String body) throws Exception {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        return Files.writeString(path, body);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
