package org.motiquies.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class Migration {
    static final String SHARED_XAERO = "Multiplayer_Any Address";
    static final Path JEI_SHARED = Path.of("world", "server", "Motiquies");
    static final Path CONFIG = Path.of("config", "motiquies-client");
    private static final Path WAYPOINT_ROOT = Path.of("xaero", "minimap");
    private static final Path JEI_ROOT = Path.of("config", "jei", "world", "server");
    private static final Set<String> OFFICIAL_ROUTES = Set.of(
            "mc.322142.xyz:25565", "frp-web.com:43765", "frp-tag.com:26963",
            "mcxz.322142.xyz:25566", "mcb.322142.xyz:25566", "mcus.322142.xyz:25566");
    private static final List<String> PERSONAL_DIRS = List.of("saves", "screenshots", "schematics",
            "resourcepacks", "shaderpacks");
    private static final Pattern VERSION = Pattern.compile("^(?:Motiquies-)?(\\d+)\\.(\\d+)\\.(\\d+)(?:-release)?$");
    private static final Pattern XAERO_FLAG = Pattern.compile("(?m)^([\\t ]*differentiate_by_server_address[\\t ]*=[\\t ]*)[^\\r\\n]*");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path target;

    record RecordKey(String category, String destination, String content) {}

    record Conflict(RecordKey key, String source, String description, boolean selectable) {}

    record Selection(Set<String> waypoints, Set<String> history, Set<String> bookmarks,
                     boolean keys, boolean files, boolean servers, Set<RecordKey> excluded) {
        Selection(Set<String> waypoints, Set<String> history, Set<String> bookmarks,
                  boolean keys, boolean files, boolean servers) {
            this(waypoints, history, bookmarks, keys, files, servers, Set.of());
        }

        Selection withExcluded(Set<RecordKey> records) {
            return new Selection(waypoints, history, bookmarks, keys, files, servers, Set.copyOf(records));
        }
    }

    private record RouteFile(String route, Path path) {}

    record Preview(List<String> waypoints, List<String> history, List<String> bookmarks,
                   boolean keysAvailable, boolean filesAvailable, boolean serversAvailable) {
        boolean isEmpty() {
            return waypoints.isEmpty() && history.isEmpty() && bookmarks.isEmpty()
                    && !keysAvailable && !filesAvailable && !serversAvailable;
        }

        Selection selection(boolean paths, boolean lookups, boolean favorites, boolean keys,
                            boolean files, boolean servers) {
            return new Selection(paths ? Set.copyOf(waypoints) : Set.of(),
                    lookups ? Set.copyOf(history) : Set.of(), favorites ? Set.copyOf(bookmarks) : Set.of(),
                    keys && keysAvailable, files && filesAvailable, servers && serversAvailable);
        }
    }

    Migration(Path target) {
        this.target = target.toAbsolutePath().normalize();
    }

    static boolean isLegacy(String version) {
        Matcher match = VERSION.matcher(version.strip());
        if (!match.matches()) return false;
        try {
            int major = Integer.parseInt(match.group(1));
            int minor = Integer.parseInt(match.group(2));
            int patch = Integer.parseInt(match.group(3));
            return major < 3 || major == 3 && (minor < 2 || minor == 2 && patch <= 1);
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    Optional<Path> upgradeSource() throws IOException {
        if (isComplete()) return Optional.empty();
        Path marker = target.resolve(CONFIG).resolve("previous-version.txt");
        if (regular(marker) && isSupportedPrevious(Files.readString(marker))) {
            Path sourcePath = target.resolve(CONFIG).resolve("previous-instance.txt");
            if (!regular(sourcePath)) return Optional.of(target);
            Path previous = Path.of(Files.readString(sourcePath).strip());
            if (previous.isAbsolute() && Files.isDirectory(previous, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.of(previous.normalize());
            }
        }
        if (isLegacy(target.getFileName().toString()) && regular(target.resolve("PCL/Setup.ini"))) {
            return Optional.of(target);
        }
        return Optional.empty();
    }

    private static boolean isSupportedPrevious(String version) {
        String value = version.strip();
        return isLegacy(value) || value.equals("3.2.2") || value.equals("Motiquies-3.2.2-release")
                || value.equals("3.2.3") || value.equals("Motiquies-3.2.3-release")
                || value.equals("3.3.0-Beta3") || value.equals("Motiquies-3.3.0-Beta3");
    }

    boolean isComplete() {
        return regular(target.resolve(CONFIG).resolve("migration-v1.done"));
    }

    void markComplete() throws IOException {
        put(target.resolve(CONFIG).resolve("migration-v1.done"), "1\n");
    }

    boolean introSeen() {
        return regular(target.resolve(CONFIG).resolve("intro-v1.seen"));
    }

    void markIntroSeen() throws IOException {
        put(target.resolve(CONFIG).resolve("intro-v1.seen"), "1\n");
    }

    Selection automaticSelection(Preview preview) throws IOException {
        Selection defaults = preview.selection(true, true, true, true, true, true);
        Path preferences = target.resolve(CONFIG).resolve("preferred-routes.json");
        if (!regular(preferences)) return defaults;
        try {
            JsonObject saved = JsonParser.parseString(Files.readString(preferences)).getAsJsonObject();
            var excluded = new LinkedHashSet<RecordKey>();
            if (saved.has("excluded")) {
                for (JsonElement value : saved.getAsJsonArray("excluded")) {
                    RecordKey record = GSON.fromJson(value, RecordKey.class);
                    if (record != null && record.category() != null && record.destination() != null && record.content() != null) {
                        excluded.add(record);
                    }
                }
            }
            return new Selection(preference(saved, "waypoints", defaults.waypoints()),
                    preference(saved, "history", defaults.history()),
                    preference(saved, "bookmarks", defaults.bookmarks()),
                    defaults.keys(), defaults.files(), defaults.servers(), Set.copyOf(excluded));
        } catch (RuntimeException invalid) {
            throw new IOException("无法读取已保存的迁移选择：" + preferences, invalid);
        }
    }

    void savePreferences(Selection selection) throws IOException {
        var saved = new JsonObject();
        saved.add("waypoints", GSON.toJsonTree(selection.waypoints()));
        saved.add("history", GSON.toJsonTree(selection.history()));
        saved.add("bookmarks", GSON.toJsonTree(selection.bookmarks()));
        saved.add("excluded", GSON.toJsonTree(selection.excluded()));
        put(target.resolve(CONFIG).resolve("preferred-routes.json"), GSON.toJson(saved) + "\n");
    }

    private static Set<String> preference(JsonObject saved, String name, Set<String> available) {
        if (!saved.has(name)) return available;
        Set<String> selected = new LinkedHashSet<>();
        saved.getAsJsonArray(name).forEach(value -> {
            String route = value.getAsString();
            if (available.contains(route)) selected.add(route);
        });
        return Set.copyOf(selected);
    }

    Preview preview(Path source) throws IOException {
        Set<String> known = routes(source);
        List<String> waypoints = new ArrayList<>();
        List<String> history = new ArrayList<>();
        List<String> bookmarks = new ArrayList<>();
        for (Path path : directories(source.resolve(WAYPOINT_ROOT))) {
            String route = path.getFileName().toString();
            if (xaeroRoute(route, known) && files(path).stream().anyMatch(Migration::isWaypointFile)) {
                waypoints.add(route);
            }
        }
        for (Path path : directories(source.resolve(JEI_ROOT))) {
            String route = path.getFileName().toString();
            if (!jeiRoute(route, known)) continue;
            if (regular(path.resolve("lookupHistory.json"))) history.add(route);
            if (regular(path.resolve("bookmarks.json"))) bookmarks.add(route);
        }
        boolean copyFiles = PERSONAL_DIRS.stream().anyMatch(dir ->
                Files.isDirectory(source.resolve(dir), LinkOption.NOFOLLOW_LINKS));
        return new Preview(List.copyOf(waypoints), List.copyOf(history), List.copyOf(bookmarks),
                regular(source.resolve("options.txt")), copyFiles, regular(source.resolve("servers.dat")));
    }

    void migrate(Path source, Selection selected, Set<String> registeredKeys) throws IOException {
        Preview available = preview(source);
        for (var group : waypointSources(source, available, selected).entrySet()) {
            mergeWaypoints(group.getKey(), group.getValue(), selected.excluded());
        }
        mergeJei(source, available.history(), selected.history(), "lookupHistory.json", selected.excluded());
        mergeJei(source, available.bookmarks(), selected.bookmarks(), "bookmarks.json", selected.excluded());
        if (selected.keys() && !source.equals(target)) migrateKeys(source.resolve("options.txt"), registeredKeys);
        if (selected.files() && !source.equals(target)) migratePersonal(source);
        configureXaero();
    }

    private Map<Path, List<RouteFile>> waypointSources(Path source, Preview available, Selection selected)
            throws IOException {
        Map<Path, List<RouteFile>> groups = new LinkedHashMap<>();
        for (String route : available.waypoints()) {
            if (!selected.waypoints().contains(route)) continue;
            Path root = source.resolve(WAYPOINT_ROOT).resolve(route);
            for (Path path : files(root)) {
                if (!isWaypointFile(path)) continue;
                Path destination = target.resolve(WAYPOINT_ROOT).resolve(SHARED_XAERO).resolve(root.relativize(path));
                groups.computeIfAbsent(destination, ignored -> new ArrayList<>()).add(new RouteFile(route, path));
            }
        }
        return groups;
    }

    List<Conflict> conflicts(Path source, Selection selected) throws IOException {
        Preview available = preview(source);
        Map<String, Map<RecordKey, Conflict>> groups = new LinkedHashMap<>();
        for (var group : waypointSources(source, available, selected).entrySet()) {
            Path destination = group.getKey();
            if (regular(destination)) {
                collectWaypointConflicts(groups, destination, new RouteFile("目标实例已有", destination), false);
            }
            for (RouteFile route : group.getValue()) collectWaypointConflicts(groups, destination, route, true);
        }
        collectJeiConflicts(groups, source, available.history(), selected.history(), "lookupHistory.json");
        collectJeiConflicts(groups, source, available.bookmarks(), selected.bookmarks(), "bookmarks.json");
        return groups.values().stream().filter(group -> group.size() > 1)
                .flatMap(group -> group.values().stream()).toList();
    }

    private void collectWaypointConflicts(Map<String, Map<RecordKey, Conflict>> groups, Path destination,
                                          RouteFile input, boolean selectable) throws IOException {
        for (String line : Files.readAllLines(input.path())) {
            if (!line.startsWith("waypoint:")) continue;
            String[] fields = line.split(":", -1);
            if (fields.length < 6) continue;
            RecordKey key = waypointKey(destination, line);
            String description = fields[1] + " @ " + fields[3] + ", " + fields[4] + ", " + fields[5]
                    + " (" + destination.getParent().getFileName() + "/" + destination.getFileName() + ")";
            String group = key.destination() + '\0' + fields[1] + '\0' + (fields.length > 9 ? fields[9] : "");
            groups.computeIfAbsent(group, ignored -> new LinkedHashMap<>())
                    .putIfAbsent(key, new Conflict(key, input.route(), description, selectable));
        }
    }

    private RecordKey waypointKey(Path destination, String line) {
        return new RecordKey("Xaero 路标", target.relativize(destination).toString().replace('\\','/'), line);
    }

    private void collectJeiConflicts(Map<String, Map<RecordKey, Conflict>> groups, Path source,
                                     List<String> available, Set<String> selected, String file) throws IOException {
        if (selected.isEmpty()) return;
        Path targetFile = target.resolve(JEI_ROOT).resolve("Motiquies").resolve(file);
        if (regular(targetFile)) collectJeiFile(groups, file, new RouteFile("目标实例已有", targetFile), false);
        for (String route : available) {
            if (selected.contains(route)) {
                collectJeiFile(groups, file, new RouteFile(route, source.resolve(JEI_ROOT).resolve(route).resolve(file)), true);
            }
        }
    }

    private static void collectJeiFile(Map<String, Map<RecordKey, Conflict>> groups, String file,
                                        RouteFile input, boolean selectable) throws IOException {
        for (JsonElement value : readJei(input.path())) {
            if (!value.isJsonObject()) continue;
            JsonObject entry = value.getAsJsonObject();
            String name = scalar(entry, "recipeId");
            if (name.isEmpty()) name = scalar(entry, "recipe");
            if (name.isEmpty() && entry.has("ingredient") && entry.get("ingredient").isJsonObject()) {
                name = scalar(entry.getAsJsonObject("ingredient"), "id");
            }
            if (name.isEmpty()) continue;
            RecordKey key = jeiKey(file, value);
            String group = file + '\0' + scalar(entry, "bookmarkType") + '\0' + name;
            groups.computeIfAbsent(group, ignored -> new LinkedHashMap<>())
                    .putIfAbsent(key, new Conflict(key, input.route(), name, selectable));
        }
    }

    private static String scalar(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static RecordKey jeiKey(String file, JsonElement value) {
        String kind = file.equals("lookupHistory.json") ? "JEI 查询" : "JEI 收藏";
        return new RecordKey(kind, file, canonicalJson(value).toString());
    }

    private static JsonElement canonicalJson(JsonElement value) {
        if (value.isJsonArray()) {
            JsonArray result = new JsonArray();
            value.getAsJsonArray().forEach(element -> result.add(canonicalJson(element)));
            return result;
        }
        if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            JsonObject result = new JsonObject();
            object.keySet().stream().sorted().forEach(key -> result.add(key, canonicalJson(object.get(key))));
            return result;
        }
        return value;
    }

    void configureXaero() throws IOException {
        for (String mod : List.of("minimap", "world-map")) {
            Path file = target.resolve(Path.of("config", "xaero", mod, "client.cfg"));
            String current = regular(file) ? Files.readString(file) : "";
            Matcher matcher = XAERO_FLAG.matcher(current);
            String updated = matcher.find() ? matcher.replaceAll("$1false")
                    : current + (current.isEmpty() || current.endsWith("\n") ? "" : "\n")
                    + "differentiate_by_server_address = false\n";
            if (!current.equals(updated)) put(file, updated);
        }
    }

    private static Set<String> routes(Path instance) throws IOException {
        Set<String> routes = new LinkedHashSet<>(OFFICIAL_ROUTES);
        Path local = instance.resolve(CONFIG).resolve("routes.txt");
        if (regular(local)) {
            for (String line : Files.readAllLines(local)) {
                if (!line.isBlank() && !line.stripLeading().startsWith("#")) routes.add(endpoint(line));
            }
        }
        return routes;
    }

    static String endpoint(String address) {
        String value = address.strip().toLowerCase(Locale.ROOT);
        return value.contains(":") ? value : value + ":25565";
    }

    private static boolean xaeroRoute(String folder, Set<String> known) {
        if (folder.equals(SHARED_XAERO)) return true;
        return known.stream().anyMatch(address -> {
            int index = address.lastIndexOf(':');
            String host = address.substring(0, index);
            String port = address.substring(index + 1);
            return folder.equalsIgnoreCase("Multiplayer_" + host)
                    || folder.equalsIgnoreCase("Multiplayer_" + host + "_" + port)
                    || folder.equalsIgnoreCase("Multiplayer_" + host + "." + port);
        });
    }

    private static boolean jeiRoute(String folder, Set<String> known) {
        if (folder.equals("Motiquies")) return true;
        return known.stream().anyMatch(address -> {
            int index = address.lastIndexOf(':');
            String host = address.substring(0, index).replaceAll("[^a-z0-9_-]", "_");
            String port = address.substring(index + 1);
            String suffix = port.equals("25565") ? " (" + host + ")" : " (" + host + " " + port + ")";
            return folder.toLowerCase(Locale.ROOT).endsWith(suffix);
        });
    }

    private static boolean isWaypointFile(Path path) {
        return path.getFileName().toString().matches("mw.*\\.txt");
    }

    private void mergeWaypoints(Path targetFile, List<RouteFile> sourceFiles, Set<RecordKey> excluded) throws IOException {
        List<String> current = regular(targetFile) ? Files.readAllLines(targetFile) : List.of();
        List<String> result = new ArrayList<>(current);
        Set<String> seen = new LinkedHashSet<>(current);
        for (RouteFile source : sourceFiles) {
            for (String line : Files.readAllLines(source.path())) {
                if (line.startsWith("waypoint:") && excluded.contains(waypointKey(targetFile, line))) continue;
                if (seen.add(line)) result.add(line);
            }
        }
        if (!result.equals(current)) put(targetFile, String.join("\n", result) + "\n");
    }

    private void mergeJei(Path source, List<String> available, Set<String> selected, String file,
                          Set<RecordKey> excluded) throws IOException {
        if (selected.isEmpty()) return;
        Path targetFile = target.resolve(JEI_ROOT).resolve("Motiquies").resolve(file);
        var entries = new LinkedHashSet<JsonElement>();
        if (regular(targetFile)) entries.addAll(readJei(targetFile));
        int before = entries.size();
        for (String route : available) {
            if (selected.contains(route)) {
                for (JsonElement value : readJei(source.resolve(JEI_ROOT).resolve(route).resolve(file))) {
                    if (!excluded.contains(jeiKey(file, value))) entries.add(value);
                }
            }
        }
        if (entries.size() == before) return;
        var output = new JsonArray();
        var version = new JsonObject();
        version.addProperty("version", jeiFormatVersion(file));
        output.add(version);
        entries.forEach(output::add);
        put(targetFile, GSON.toJson(output) + "\n");
    }

    private static List<JsonElement> readJei(Path path) throws IOException {
        try {
            JsonArray array = JsonParser.parseString(Files.readString(path)).getAsJsonArray();
            int expectedVersion = jeiFormatVersion(path.getFileName().toString());
            if (array.isEmpty() || array.get(0).getAsJsonObject().get("version").getAsInt() != expectedVersion) {
                throw new IOException("不兼容的 JEI 文件：" + path);
            }
            List<JsonElement> result = new ArrayList<>();
            for (int i = 1; i < array.size(); i++) result.add(array.get(i));
            return result;
        } catch (RuntimeException invalid) {
            throw new IOException("无法读取 JEI 文件：" + path, invalid);
        }
    }

    private static int jeiFormatVersion(String file) {
        return switch (file) {
            case "lookupHistory.json" -> 1;
            case "bookmarks.json" -> 2;
            default -> throw new IllegalArgumentException("Unsupported JEI file: " + file);
        };
    }

    private void migrateKeys(Path source, Set<String> registeredKeys) throws IOException {
        if (registeredKeys.isEmpty()) return;
        if (!regular(source)) throw new IOException("无法读取旧实例键位：" + source);
        Map<String, String> keys = new LinkedHashMap<>();
        for (String line : Files.readAllLines(source)) {
            String name = optionKey(line);
            if (name != null && registeredKeys.contains(name)) keys.put(name, line);
        }
        if (keys.isEmpty()) return;
        Path options = target.resolve("options.txt");
        verifyDestination(options);
        List<String> old = Files.readAllLines(options);
        List<String> result = new ArrayList<>(old.size() + keys.size());
        Set<String> existingKeys = new LinkedHashSet<>();
        for (String line : old) {
            String name = optionKey(line);
            result.add(keys.getOrDefault(name, line));
            existingKeys.add(name);
        }
        keys.keySet().removeAll(existingKeys);
        result.addAll(keys.values());
        if (!old.equals(result)) put(options, String.join("\n", result) + "\n");
    }

    private static String optionKey(String line) {
        int colon = line.indexOf(':');
        return line.startsWith("key_") && colon > 4 ? line.substring(4, colon) : null;
    }

    private void migratePersonal(Path source) throws IOException {
        migrateSaves(source.resolve("saves"));
        for (String dir : PERSONAL_DIRS) {
            if (dir.equals("saves")) continue;
            for (Path child : files(source.resolve(dir))) {
                Path to = target.resolve(source.relativize(child));
                verifyDestination(to);
                if (regular(to) && Files.mismatch(child, to) == -1) continue;
                if (Files.exists(to, LinkOption.NOFOLLOW_LINKS)) {
                    Path conflict = target.resolve(CONFIG).resolve("personal-import-conflicts").resolve(source.relativize(child));
                    verifyDestination(conflict);
                    if (!Files.exists(conflict, LinkOption.NOFOLLOW_LINKS)) {
                        Files.createDirectories(conflict.getParent());
                        Files.copy(child, conflict);
                    } else if (!regular(conflict) || Files.mismatch(child, conflict) != -1) {
                        throw new IOException("已有不同的冲突备份：" + conflict);
                    }
                } else {
                    Files.createDirectories(to.getParent());
                    Path temp = Files.createTempFile(to.getParent(), ".motiquies-", ".tmp");
                    try {
                        Files.copy(child, temp, StandardCopyOption.REPLACE_EXISTING);
                        Files.move(temp, to, StandardCopyOption.ATOMIC_MOVE);
                    } finally {
                        Files.deleteIfExists(temp);
                    }
                }
            }
        }
    }

    private void migrateSaves(Path oldSaves) throws IOException {
        Path newSaves = target.resolve("saves");
        for (Path world : directories(oldSaves)) {
            Path destination = newSaves.resolve(world.getFileName());
            List<Path> sourceFiles = files(world).stream()
                    .map(world::relativize)
                    .filter(path -> !path.toString().equals("session.lock"))
                    .toList();
            int conflict = 0;
            while (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                verifyDestination(destination);
                if (sameWorld(world, destination, sourceFiles)) break;
                conflict++;
                destination = newSaves.resolve(world.getFileName() + " (导入 " + conflict + ")");
            }
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) continue;
            verifyDestination(destination);
            Files.createDirectories(newSaves);
            Path staging = Files.createTempDirectory(newSaves, ".motiquies-save-");
            try {
                for (Path relative : sourceFiles) {
                    Path file = staging.resolve(relative);
                    Files.createDirectories(file.getParent());
                    Files.copy(world.resolve(relative), file);
                }
                Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
                    try (Stream<Path> remaining = Files.walk(staging)) {
                        for (Path path : remaining.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                    }
                }
            }
        }
    }

    private static boolean sameWorld(Path source, Path destination, List<Path> sourceFiles) throws IOException {
        if (!Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)) return false;
        List<Path> targetFiles = files(destination).stream().map(destination::relativize)
                .filter(path -> !path.toString().equals("session.lock")).toList();
        if (!sourceFiles.equals(targetFiles)) return false;
        for (Path path : sourceFiles) {
            if (Files.mismatch(source.resolve(path), destination.resolve(path)) != -1) return false;
        }
        return true;
    }

    void verifyDestination(Path file) throws IOException {
        Path absolute = file.toAbsolutePath().normalize();
        if (!absolute.startsWith(target)) throw new IOException("目标超出实例目录：" + file);
        for (Path part = absolute; part != null && part.startsWith(target); part = part.getParent()) {
            if (Files.isSymbolicLink(part)) throw new IOException("目标包含符号链接：" + part);
        }
    }

    void backupExisting(Path file) throws IOException {
        verifyDestination(file);
        if (!regular(file)) return;
        Path backup = target.resolve(CONFIG).resolve("backup-v1").resolve(target.relativize(file));
        verifyDestination(backup);
        if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) return;
        Files.createDirectories(backup.getParent());
        Files.copy(file, backup);
    }

    private void put(Path file, String value) throws IOException {
        verifyDestination(file);
        Files.createDirectories(file.getParent());
        backupExisting(file);
        Path temporary = Files.createTempFile(file.getParent(), ".motiquies-", ".tmp");
        try {
            Files.writeString(temporary, value, StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static boolean regular(Path path) {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
    }

    private static List<Path> directories(Path path) throws IOException {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return List.of();
        try (Stream<Path> all = Files.list(path)) {
            return all.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)).sorted().toList();
        }
    }

    private static List<Path> files(Path root) throws IOException {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return List.of();
        try (Stream<Path> all = Files.walk(root)) {
            return all.filter(Migration::regular).sorted().toList();
        }
    }
}
