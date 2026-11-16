package com.mapsyncer.server;

import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.io.*;
import java.util.function.*;

/** Requires an acquired generation fence and an idle converter for the entire capture. */
public final class DownloadSnapshot implements AutoCloseable {
    private final Path root;
    private boolean closed;
    private DownloadSnapshot(Path root) { this.root = root; }
    public static DownloadSnapshot capture(Path source, Path owner, Predicate<Path> selected,
                                           BooleanSupplier current) throws IOException {
        source = source.toAbsolutePath().normalize(); owner = owner.toAbsolutePath().normalize();
        if (owner.startsWith(source) || source.startsWith(owner)) throw new IOException("Snapshot and live roots overlap");
        noLinks(source); noLinks(owner);
        Files.createDirectories(owner); noLinks(owner);
        var snapshot = new DownloadSnapshot(Files.createTempDirectory(owner,"job-"));
        final Path live = source;
        try {
            ensureCurrent(current);
            Path metadata = live.resolve("generation_cache.properties"); noLinks(metadata);
            Files.copy(metadata,snapshot.root.resolve("generation_cache.properties"));
            Files.walkFileTree(live, new SimpleFileVisitor<>() {
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    ensureCurrent(current); if (Files.isSymbolicLink(dir)) throw new IOException("Linked map directory");
                    return FileVisitResult.CONTINUE;
                }
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    ensureCurrent(current);
                    if (attrs.isSymbolicLink() || attrs.isOther()) throw new IOException("Linked or special map entry");
                    if (attrs.isRegularFile() && file.toString().endsWith(".zip") && selected.test(live.relativize(file))) {
                        noLinks(file);
                        Path target = snapshot.root.resolve(live.relativize(file));
                        Files.createDirectories(target.getParent()); Files.createLink(target,file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            ensureCurrent(current); return snapshot;
        } catch (IOException | RuntimeException failure) {
            try { snapshot.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    private static void ensureCurrent(BooleanSupplier current) throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted() || !current.getAsBoolean()) throw new InterruptedIOException("Snapshot cancelled");
    }
    private static void noLinks(Path path) throws IOException {
        for (Path p = path; p != null; p = p.getParent()) {
            if (Files.isSymbolicLink(p) || (Files.exists(p,LinkOption.NOFOLLOW_LINKS) && Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).isOther()))
                throw new IOException("Linked or special snapshot path");
        }
    }
    public Path root() { return root; }
    /** Run after releasing the live-generation fence. Only missing/invalid pinned entries are hashed. */
    public int repairMissingMetadata(BooleanSupplier current) throws IOException {
        var metadata=new java.util.Properties();
        Path file=root.resolve("generation_cache.properties");
        try (var in=Files.newInputStream(file)) { metadata.load(in); }
        int repaired=0;
        try (var files=Files.walk(root)) {
            for (Path zip : files.filter(p -> p.toString().endsWith(".zip")).toList()) {
                ensureCurrent(current);
                String key=root.relativize(zip).toString().replace('\\','/');
                key=key.substring(0,key.length()-4);
                String existing=metadata.getProperty(key,"");
                if (existing.matches("\\d{1,18}:[0-9a-fA-F]{8}")) continue;
                var crc=new java.util.zip.CRC32(); byte[] buffer=new byte[8192];
                try (var in=Files.newInputStream(zip)) {
                    int count;
                    while ((count=in.read(buffer))!=-1) { ensureCurrent(current); crc.update(buffer,0,count); }
                }
                long timestamp=Math.max(0,Files.getLastModifiedTime(zip).toMillis()/1000);
                metadata.setProperty(key,timestamp+":"+String.format("%08x",crc.getValue())); repaired++;
            }
        }
        if (repaired>0) {
            ensureCurrent(current);
            Path temp=root.resolve("generation_cache.properties.repair.tmp");
            try {
                try (var out=Files.newOutputStream(temp)) { metadata.store(out,"Pinned download metadata; repairs only affect this private index"); }
                try { Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException failure) { Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING); }
            } finally { Files.deleteIfExists(temp); }
        }
        return repaired;
    }
    public static byte[] readVerified(Path path,String hash) throws IOException {
        byte[] data=Files.readAllBytes(path);
        var crc=new java.util.zip.CRC32(); crc.update(data);
        if (hash == null || !String.format("%08x",crc.getValue()).equals(hash)) throw new IOException("Map content does not match pinned generation metadata");
        return data;
    }
    public synchronized void close() throws IOException {
        if (closed) return;
        // Walk never follows links, and root was created by this object, never taken from a manifest.
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            public FileVisitResult visitFile(Path file,BasicFileAttributes attrs) throws IOException { Files.delete(file); return FileVisitResult.CONTINUE; }
            public FileVisitResult postVisitDirectory(Path dir,IOException failure) throws IOException { if (failure != null) throw failure; Files.delete(dir); return FileVisitResult.CONTINUE; }
        }); closed = true;
    }
}
