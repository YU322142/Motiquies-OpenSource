package org.motiquies.client;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class DhCacheMigration {
    private static final String COMPLETED = "motiquies-native-dh-v1\n";
    private static final Set<String> CACHE_FILES = Set.of("DistantHorizons.sqlite", "DistantHorizons.sqlite-wal",
            "DistantHorizons.sqlite-shm", "DistantHorizons.sqlite-journal");

    static Set<String> defaultFolders() {
        var result = new LinkedHashSet<String>();
        result.add("-1921347326_Motiquies");
        for (String name : List.of("直连上海", "镇江中转 / Zhenjiang(CN)", "湖北中转 / Hubei(CN)",
                "徐州中转 / Xuzhou(CN)", "北京中转 / Peking(CN)", "美国中转 US")) {
            result.add(legacyFolder(name));
        }
        return Set.copyOf(result);
    }

    // Matches DH 3.2.0-b's PercentEscaper("", true) after Windows-invalid name characters are removed.
    static String legacyFolder(String displayName) {
        String safe = displayName.replaceAll("[\\\\/:*?\"<>|]", "");
        var out = new StringBuilder();
        for (byte value : safe.getBytes(StandardCharsets.UTF_8)) {
            int b = value & 255;
            if (b >= 'a' && b <= 'z' || b >= 'A' && b <= 'Z' || b >= '0' && b <= '9') out.append((char)b);
            else if (b == ' ') out.append('+');
            else out.append('%').append("0123456789ABCDEF".charAt(b >> 4)).append("0123456789ABCDEF".charAt(b & 15));
        }
        return out.toString();
    }

    static List<String> migrate(Path instance, Set<String> confirmedFolders) throws IOException {
        Path root = instance.toAbsolutePath().normalize();
        for (String name : confirmedFolders) {
            if (name.isBlank() || name.equals(".") || name.equals("..") || name.indexOf('/') >= 0
                    || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0 || !Path.of(name).getFileName().toString().equals(name)) {
                throw new IOException("Invalid confirmed DH folder name");
            }
        }
        Path config = root.resolve("config/motiquies-client");
        Path marker = config.resolve("dh-native-v1.done");
        verify(root, marker);
        if (completed(marker)) return List.of();
        Files.createDirectories(config);
        Path lockPath = config.resolve("dh-native-v1.lock");
        verify(root, lockPath);
        try (var channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var migrationLock = channel.tryLock()) {
            if (migrationLock == null) throw new IOException("DH migration is already running");
            if (completed(marker)) return List.of();
            Path dataRoot = root.resolve("Distant_Horizons_server_data");
            verify(root, dataRoot);
            var files = new ArrayList<Path>();
            for (String name : confirmedFolders.stream().sorted().toList()) {
                Path folder = dataRoot.resolve(name);
                verify(root, folder);
                if (!Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) continue;
                if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) throw new IOException("DH folder is not a directory: " + name);
                try (var walk = Files.walk(folder)) {
                    for (Path item : walk.toList()) {
                        verify(root, item);
                        if (Files.isRegularFile(item, LinkOption.NOFOLLOW_LINKS) && CACHE_FILES.contains(item.getFileName().toString())) files.add(item);
                    }
                }
            }
            // Validate every candidate before deleting any file. SQLite/open-file failures leave the completion marker absent.
            var channels = new ArrayList<FileChannel>();
            var locks = new ArrayList<FileLock>();
            try {
                for (Path file : files) {
                    FileChannel opened = FileChannel.open(file, StandardOpenOption.WRITE);
                    channels.add(opened);
                    FileLock lock = opened.tryLock();
                    if (lock == null) throw new IOException("DH cache is in use: " + root.relativize(file));
                    locks.add(lock);
                }
            } catch (OverlappingFileLockException busy) {
                throw new IOException("DH cache is in use; close other instances and retry", busy);
            } finally {
                for (FileLock lock : locks) lock.release();
                for (FileChannel opened : channels) opened.close();
            }
            var removed = new ArrayList<String>();
            for (Path file : files) {
                verify(root, file);
                Files.delete(file);
                removed.add(root.relativize(file).toString());
            }
            Path pending = Files.createTempFile(config, ".dh-native-v1-", ".tmp");
            try {
                Files.writeString(pending, COMPLETED, StandardCharsets.UTF_8);
                Files.move(pending, marker, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(pending);
            }
            return List.copyOf(removed);
        } catch (OverlappingFileLockException busy) {
            throw new IOException("DH migration is already running", busy);
        }
    }

    private static boolean completed(Path marker) throws IOException {
        if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) return false;
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) || !Files.readString(marker).equals(COMPLETED)) {
            throw new IOException("Invalid DH migration completion marker");
        }
        return true;
    }

    private static void verify(Path root, Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        if (!absolute.startsWith(root)) throw new IOException("DH migration path outside instance");
        Path part = root;
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || !root.toRealPath().equals(root)) throw new IOException("Linked or invalid instance root");
        for (Path segment : root.relativize(absolute)) {
            part = part.resolve(segment);
            if (!Files.exists(part, LinkOption.NOFOLLOW_LINKS)) break;
            if (Files.isSymbolicLink(part) || !part.toRealPath().equals(part)) throw new IOException("Linked DH migration path: " + root.relativize(part));
        }
    }
}
