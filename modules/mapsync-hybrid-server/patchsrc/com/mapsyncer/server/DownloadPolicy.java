package com.mapsyncer.server;

import java.nio.file.*;
import java.util.*;
import java.io.*;

/** No game dependencies; all mutable scheduler state is owned by this monitor. */
public final class DownloadPolicy {
    public enum Mode { NEARBY, CUSTOM, DIMENSION, GLOBAL, PAUSED }
    public static final int MAX_RADIUS = 32;
    public interface Region { String dimension(); int regionX(); int regionZ(); int caveLayer(); }
    public static Comparator<Region> priority(String currentDimension,int centerX,int centerZ) {
        return Comparator.comparingInt((Region r) -> r.dimension().equals(currentDimension) ? 0 : 1)
                .thenComparingLong(r -> Math.max(Math.abs((long)r.regionX()-centerX),Math.abs((long)r.regionZ()-centerZ)))
                .thenComparingInt(r -> r.caveLayer() == Integer.MAX_VALUE ? 0 : 1)
                .thenComparing(Region::dimension).thenComparingInt(Region::regionX)
                .thenComparingInt(Region::regionZ).thenComparingInt(Region::caveLayer);
    }
    public static final class Job {
        public final UUID player;
        public final Mode mode;
        public final int radius;
        public final DownloadEstimate estimate = new DownloadEstimate();
        public final Pacer playerPacer = new Pacer();
        private Job(UUID player, Mode mode,int radius) { this.player = player; this.mode = mode; this.radius=radius; }
        public boolean large() { return mode == Mode.DIMENSION || mode == Mode.GLOBAL || mode == Mode.CUSTOM && radius>1; }
    }
    private final Path file;
    private Properties preferences = new Properties();
    private final Map<UUID, Job> jobs = new HashMap<>();
    private final LinkedList<Job> queue = new LinkedList<>();
    private Job owner;
    public DownloadPolicy(Path file) throws IOException {
        this.file = file.toAbsolutePath().normalize();
        if (Files.exists(file)) try (var in = Files.newInputStream(file)) { preferences.load(in); }
    }
    public synchronized Mode mode(UUID id) { return value(id.toString(), Mode.NEARBY); }
    public synchronized Mode previous(UUID id) { return value(id+".previous", Mode.NEARBY); }
    public synchronized int radius(UUID id) {
        try { int n=Integer.parseInt(preferences.getProperty(id+".radius","1"));return n>=0&&n<=MAX_RADIUS?n:1; }
        catch(NumberFormatException e){return 1;}
    }
    public synchronized void selectCustom(UUID id,int radius) throws IOException {
        if(radius<0||radius>MAX_RADIUS)throw new IllegalArgumentException("radius must be 0..32");
        saveSelection(id,Mode.CUSTOM,radius);
    }
    private Mode value(String key, Mode fallback) {
        try { return Mode.valueOf(preferences.getProperty(key, fallback.name())); }
        catch (IllegalArgumentException e) { return fallback; }
    }
    public synchronized Mode resume(UUID id) throws IOException {
        Mode restored = mode(id) == Mode.PAUSED ? previous(id) : mode(id);
        if (restored == Mode.PAUSED) restored = Mode.NEARBY;
        select(id, restored); return restored;
    }
    public synchronized void select(UUID id, Mode selected) throws IOException {
        saveSelection(id,selected,radius(id));
    }
    private void saveSelection(UUID id,Mode selected,int radius) throws IOException {
        Properties next = new Properties(); next.putAll(preferences);
        Mode before = mode(id);
        if (selected == Mode.PAUSED && before != Mode.PAUSED) next.setProperty(id+".previous", before.name());
        else if (selected != Mode.PAUSED) next.setProperty(id+".previous", selected.name());
        next.setProperty(id.toString(), selected.name());
        next.setProperty(id+".radius",Integer.toString(radius));
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), ".download-policy-", ".tmp");
        try {
            try (var out = Files.newOutputStream(temp)) { next.store(out, "Map download preferences by authenticated UUID"); }
            try { Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING); }
            preferences = next;
        } finally { Files.deleteIfExists(temp); }
    }
    public synchronized Job begin(UUID id) {
        cancel(id); Job job = new Job(id, mode(id),radius(id));
        jobs.put(id, job); if (job.large()) queue.add(job); return job;
    }
    public synchronized boolean tryAcquire(Job job) {
        if (!current(job) || job.mode == Mode.PAUSED) return false;
        if (!job.large() || owner == job) return true;
        if (owner == null && queue.peekFirst() == job) { queue.removeFirst(); owner = job; return true; }
        return false;
    }
    public synchronized Job job(UUID id) { return jobs.get(id); }
    public synchronized boolean hasNearby() { return jobs.values().stream().anyMatch(j -> !j.large() && j.mode!=Mode.PAUSED); }
    public synchronized int position(Job job) { return job == null ? 0 : Math.max(0, queue.indexOf(job)+1); }
    public synchronized void cancel(UUID id) { Job job = jobs.remove(id); if (job != null) release(job); }
    public synchronized void release(Job job) {
        jobs.remove(job.player, job); queue.remove(job); if (owner == job) owner = null;
    }
    public synchronized boolean current(Job job) { return jobs.get(job.player) == job; }
    public synchronized void clear() { jobs.clear(); queue.clear(); owner = null; }
    public static boolean allows(Mode mode, String dimension, int centerX, int centerZ,
                                  String regionDimension, int regionX, int regionZ) {
        return allows(mode,1,dimension,centerX,centerZ,regionDimension,regionX,regionZ);
    }
    public static boolean allows(Mode mode,int radius,String dimension,int centerX,int centerZ,
                                  String regionDimension,int regionX,int regionZ) {
        if (mode == Mode.PAUSED) return false;
        if (mode == Mode.GLOBAL) return true;
        if (!dimension.equals(regionDimension)) return false;
        int r=mode==Mode.CUSTOM?radius:1;
        return mode == Mode.DIMENSION || (r>=0&&r<=MAX_RADIUS&&Math.abs((long)regionX-centerX) <= r && Math.abs((long)regionZ-centerZ) <= r);
    }
    /** Reservation deadlines, with no initial burst or banking while idle. */
    public static final class Pacer {
        private long deadline;
        public synchronized long reserve(int bytes, int bytesPerSecond, long now) {
            if (bytes < 0 || bytesPerSecond <= 0) throw new IllegalArgumentException();
            deadline = Math.max(now, deadline) + (bytes * 1_000_000_000L + bytesPerSecond-1) / bytesPerSecond;
            return deadline;
        }
    }
}
