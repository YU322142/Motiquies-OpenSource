package com.mapsyncer.server;
import java.nio.file.*;
import java.util.*;

public final class DownloadSnapshotTest {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]); Path source = root.resolve("source");
        Files.createDirectories(source.resolve("null"));
        Files.writeString(source.resolve("null/0_0.zip"), "generation-one");
        Files.writeString(source.resolve("null/9_9.zip"), "far");
        Files.writeString(source.resolve("generation_cache.properties"), "generation=one");
        Files.writeString(source.resolve("personal.txt"), "keep");
        var snapshot = DownloadSnapshot.capture(source, root.resolve("snapshots"), p -> p.toString().endsWith("0_0.zip"), () -> true);
        Path index = snapshot.root();
        DownloadPolicyTest.check(Files.isSameFile(source.resolve("null/0_0.zip"),index.resolve("null/0_0.zip")), "ZIP must hardlink");
        DownloadPolicyTest.check(!Files.isSameFile(source.resolve("generation_cache.properties"),index.resolve("generation_cache.properties")), "metadata must copy");
        DownloadPolicyTest.check(!Files.exists(index.resolve("null/9_9.zip")), "snapshot captures selected scope only");
        Files.writeString(source.resolve("null/new.zip"), "generation-two");
        Files.move(source.resolve("null/new.zip"), source.resolve("null/0_0.zip"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        Files.writeString(source.resolve("generation_cache.properties"), "generation=two");
        DownloadPolicyTest.check(Files.readString(index.resolve("null/0_0.zip")).equals("generation-one"), "pinned old inode survives native replace");
        DownloadPolicyTest.check(Files.readString(index.resolve("generation_cache.properties")).equals("generation=one"), "metadata matches pinned generation");
        try { DownloadSnapshot.readVerified(index.resolve("null/0_0.zip"),"00000000"); throw new AssertionError("metadata/content mismatch sent"); }
        catch (java.io.IOException expected) { }
        DownloadPolicyTest.check(snapshot.repairMissingMetadata(() -> true)==1,"missing pinned metadata repaired");
        Properties repaired=new Properties();
        try (var in=Files.newInputStream(index.resolve("generation_cache.properties"))) { repaired.load(in); }
        String value=repaired.getProperty("null/0_0");
        DownloadPolicyTest.check(value!=null && value.contains(":"),"native timestamp:CRC metadata key");
        byte[] verified=DownloadSnapshot.readVerified(index.resolve("null/0_0.zip"),value.split(":")[1]);
        DownloadPolicyTest.check(new String(verified,java.nio.charset.StandardCharsets.UTF_8).equals("generation-one"),"repair hashes pinned version");
        DownloadPolicyTest.check(Files.readString(source.resolve("generation_cache.properties")).equals("generation=two"),"repair never changes source metadata");
        DownloadPolicyTest.check(snapshot.repairMissingMetadata(() -> true)==0,"complete metadata avoids rehash");
        snapshot.close(); snapshot.close();
        DownloadPolicyTest.check(!Files.exists(index), "owned index cleaned on completion");
        DownloadPolicyTest.check(Files.readString(source.resolve("personal.txt")).equals("keep"), "source preserved");
        DownloadPolicyTest.check(Files.readString(source.resolve("null/0_0.zip")).equals("generation-two"), "new live generation preserved");
        try { DownloadSnapshot.capture(source, root.resolve("snapshots"), p -> true, () -> false); throw new AssertionError("cancel accepted"); }
        catch (java.io.InterruptedIOException expected) { }
        try (var dirs = Files.list(root.resolve("snapshots"))) { DownloadPolicyTest.check(dirs.findAny().isEmpty(), "partial cancelled snapshot cleaned"); }
        System.out.println("PASS snapshots: scope, ZIP hardlinks, metadata copy, atomic replacement consistency, owned cleanup, cancellation");
    }
}
