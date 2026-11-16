package org.motiquies.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

final class ServerFiles {
    private static final String[][] OFFICIAL = {
            {"直连上海", "mc.322142.xyz"},
            {"镇江中转 / Zhenjiang(CN)", "frp-web.com:43765"},
            {"湖北中转 / Hubei(CN)", "frp-tag.com:26963"},
            {"徐州中转 / Xuzhou(CN)", "mcxz.322142.xyz:25566"},
            {"北京中转 / Peking(CN)", "mcb.322142.xyz:25566"},
            {"美国中转 US", "mcus.322142.xyz:25566"}
    };

    private ServerFiles() {}

    static Set<String> knownDhFolders(Path instance) throws IOException {
        var result = new LinkedHashSet<>(DhCacheMigration.defaultFolders());
        var known = new LinkedHashSet<String>();
        var ambiguous = new LinkedHashSet<String>();
        for (String[] entry : OFFICIAL) known.add(Migration.endpoint(entry[1]));
        var servers = read(instance.resolve("servers.dat")).getList("servers", Tag.TAG_COMPOUND);
        for (int i = 0; i < servers.size(); i++) {
            var entry = servers.getCompound(i);
            if (known.contains(Migration.endpoint(entry.getString("ip")))) {
                result.add(DhCacheMigration.legacyFolder(entry.getString("name")));
            } else {
                ambiguous.add(DhCacheMigration.legacyFolder(entry.getString("name")));
            }
        }
        // Windows DH directories are case-insensitive; preserve any ambiguous spelling.
        result.removeIf(name -> ambiguous.stream().anyMatch(other -> other.equalsIgnoreCase(name)));
        if (!ambiguous.isEmpty()) org.apache.logging.log4j.LogManager.getLogger(ServerFiles.class)
                .info("DH legacy names also used by other servers are excluded from reset: {}", ambiguous);
        return Set.copyOf(result);
    }

    static void merge(Path instance, Path source) throws IOException {
        Path path = instance.resolve("servers.dat");
        Migration migration = new Migration(instance);
        migration.verifyDestination(path);
        CompoundTag existing = read(path);
        ListTag entries = existing.getList("servers", Tag.TAG_COMPOUND);
        ListTag merged = entries.copy();
        Set<String> endpoints = new LinkedHashSet<>();
        for (int i = 0; i < merged.size(); i++) {
            endpoints.add(Migration.endpoint(merged.getCompound(i).getString("ip")));
        }
        if (source != null && !source.toAbsolutePath().normalize().equals(instance.toAbsolutePath().normalize())) {
            ListTag previous = read(source.resolve("servers.dat")).getList("servers", Tag.TAG_COMPOUND);
            for (int i = 0; i < previous.size(); i++) {
                CompoundTag entry = previous.getCompound(i);
                if (endpoints.add(Migration.endpoint(entry.getString("ip")))) merged.add(entry.copy());
            }
        }
        for (String[] official : OFFICIAL) {
            if (!endpoints.add(Migration.endpoint(official[1]))) continue;
            CompoundTag entry = new CompoundTag();
            entry.putString("name", official[0]);
            entry.putString("ip", official[1]);
            merged.add(entry);
        }
        for (int i = 0; i < merged.size(); i++) merged.getCompound(i).putBoolean("acceptTextures", false);
        if (entries.equals(merged)) return;
        existing.put("servers", merged);
        migration.backupExisting(path);
        Path temporary = Files.createTempFile(instance, ".motiquies-servers-", ".dat");
        try {
            NbtIo.write(existing, temporary);
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static CompoundTag read(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return new CompoundTag();
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("无效服务器列表：" + path);
        CompoundTag tag = NbtIo.read(path);
        if (tag == null || !tag.contains("servers", Tag.TAG_LIST)) throw new IOException("无效服务器列表：" + path);
        return tag;
    }
}
