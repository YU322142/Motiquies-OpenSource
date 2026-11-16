package org.motiquies.client;

import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

public final class ServerFilesTest {
    public static void main(String[] args) throws Exception {
        Path sandbox = Files.createTempDirectory("motiquies-servers-test-");
        Path target = Files.createDirectory(sandbox.resolve("target"));
        Path source = Files.createDirectory(sandbox.resolve("source"));
        write(target, server("My Shanghai", "mc.322142.xyz", true, "original"));
        write(source, server("Duplicate", "MC.322142.XYZ:25565", true, "duplicate"),
                server("Personal", "example.invalid:25577", true, "custom"));
        ServerFiles.merge(target, source);
        ServerFiles.merge(target, source);
        ListTag result = NbtIo.read(target.resolve("servers.dat")).getList("servers", Tag.TAG_COMPOUND);
        if (result.size() != 7) throw new AssertionError("Duplicate endpoints and official routes must not accumulate");
        if (!result.getCompound(0).getString("name").equals("My Shanghai")) {
            throw new AssertionError("Existing names and order must survive");
        }
        if (!result.getCompound(1).getString("custom").equals("custom")) {
            throw new AssertionError("Unknown user server data must survive");
        }
        for (int i = 0; i < result.size(); i++) {
            var entry = result.getCompound(i);
            if (!entry.contains("acceptTextures", Tag.TAG_BYTE) || entry.getBoolean("acceptTextures")) {
                throw new AssertionError("All entries must reject server resource packs");
            }
        }
        if (!NbtIo.read(source.resolve("servers.dat")).getList("servers", Tag.TAG_COMPOUND)
                .getCompound(0).getBoolean("acceptTextures")) {
            throw new AssertionError("Old instance must not be changed");
        }
        Path collisions = Files.createDirectory(sandbox.resolve("collisions"));
        write(collisions, server("生存服", "mc.322142.xyz", false, ""),
                server("生存服", "other.example", false, ""),
                server("直连上海", "another.example", false, ""),
                server("Shared Name", "frp-web.com:43765", false, ""),
                server("shared name", "third.example", false, ""));
        var owned = ServerFiles.knownDhFolders(collisions);
        if (owned.contains(DhCacheMigration.legacyFolder("生存服"))
                || owned.contains(DhCacheMigration.legacyFolder("直连上海"))
                || owned.contains(DhCacheMigration.legacyFolder("Shared Name"))) {
            throw new AssertionError("DH names shared with other servers must remain ambiguous and must not be cleared");
        }
        if (!owned.contains("-1921347326_Motiquies")) throw new AssertionError("Stable native identity remains eligible");
        System.out.println("Server list tests passed");
    }

    private static CompoundTag server(String name, String address, boolean accept, String custom) {
        CompoundTag tag = new CompoundTag();
        tag.putString("name", name);
        tag.putString("ip", address);
        tag.putBoolean("acceptTextures", accept);
        tag.putString("custom", custom);
        return tag;
    }

    private static void write(Path root, CompoundTag... entries) throws Exception {
        ListTag list = new ListTag();
        for (CompoundTag entry : entries) list.add(entry);
        CompoundTag file = new CompoundTag();
        file.put("servers", list);
        NbtIo.write(file, root.resolve("servers.dat"));
    }
}
