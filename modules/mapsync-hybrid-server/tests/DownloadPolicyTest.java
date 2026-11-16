package com.mapsyncer.server;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DownloadPolicyTest {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]); Files.createDirectories(root);
        DownloadPolicy p = new DownloadPolicy(root.resolve("preferences.properties"));
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        check(p.mode(a) == DownloadPolicy.Mode.NEARBY, "automatic legacy requests default to NEARBY");
        check(DownloadPolicy.allows(p.mode(a), "null", -1, -1, "null", -1, -1), "negative center");
        check(DownloadPolicy.allows(p.mode(a), "null", -1, -1, "null", 0, 0), "negative edge included");
        check(!DownloadPolicy.allows(p.mode(a), "null", -1, -1, "null", 1, 0), "outside 3x3 excluded");
        check(!DownloadPolicy.allows(p.mode(a), "null", 0, 0, "DIM-1", 0, 0), "other dim excluded");
        check(DownloadPolicy.allows(DownloadPolicy.Mode.DIMENSION,"null",0,0,"null",999,999), "dimension unlimited radius");
        check(!DownloadPolicy.allows(DownloadPolicy.Mode.DIMENSION,"null",0,0,"DIM1",0,0), "dimension restricted");
        check(DownloadPolicy.allows(DownloadPolicy.Mode.GLOBAL,"null",0,0,"DIM1",999,999), "global all");
        record Region(String dimension,int regionX,int regionZ,int caveLayer) implements DownloadPolicy.Region { }
        Region other=new Region("DIM1",0,0,Integer.MAX_VALUE), far=new Region("null",5,0,Integer.MAX_VALUE),
                cave=new Region("null",1,0,-1),surface=new Region("null",0,1,Integer.MAX_VALUE),center=new Region("null",0,0,Integer.MAX_VALUE);
        List<Region> ordered=new ArrayList<>(List.of(other,far,cave,surface,center));
        ordered.sort(DownloadPolicy.priority("null",0,0));
        check(ordered.equals(List.of(center,surface,cave,far,other)), "current dimension, distance, surface before cave");
        p.select(a, DownloadPolicy.Mode.GLOBAL); p.select(a, DownloadPolicy.Mode.PAUSED);
        check(p.mode(a) == DownloadPolicy.Mode.PAUSED, "pause persists");
        DownloadPolicy loaded = new DownloadPolicy(root.resolve("preferences.properties"));
        check(loaded.mode(a) == DownloadPolicy.Mode.PAUSED, "paused after reconnect/restart");
        check(loaded.resume(a) == DownloadPolicy.Mode.GLOBAL, "resume previous selection");
        p.resume(a); p.select(b, DownloadPolicy.Mode.DIMENSION); p.select(c, DownloadPolicy.Mode.GLOBAL);
        var ja = p.begin(a); var jb = p.begin(b); var jc = p.begin(c);
        check(p.tryAcquire(ja), "first large admitted");
        check(!p.tryAcquire(jb) && !p.tryAcquire(jc), "single large slot");
        check(p.position(jb) == 1 && p.position(jc) == 2, "FIFO positions");
        p.cancel(a); check(p.tryAcquire(jb), "cancel immediately releases slot");
        var next = p.begin(a); p.release(ja);
        check(!p.tryAcquire(next), "old completion cannot release new owner's slot");
        p.release(jb); check(p.tryAcquire(jc), "FIFO prevents replacement jumping queue");
        p.cancel(c); check(p.tryAcquire(next), "queue progresses");
        var old = next; next = p.begin(a); p.release(old);
        check(p.current(next) && p.tryAcquire(next), "old finally cannot cancel replacement");
        UUID small = UUID.randomUUID(); var nearby = p.begin(small);
        check(p.tryAcquire(nearby), "nearby never queues behind full download");
        DownloadPolicy.Pacer limit = new DownloadPolicy.Pacer();
        check(limit.reserve(256*1024, 256*1024, 0) == 1_000_000_000L, "first native rate reservation paced");
        check(limit.reserve(256*1024, 256*1024, 0) == 2_000_000_000L, "concurrent aggregate reservations serialize");
        check(limit.reserve(512*1024, 512*1024, 5_000_000_000L) == 6_000_000_000L, "idle time cannot bank burst");
        var fence = new DownloadGenerationGate(); var running = new AtomicBoolean(false);
        check(fence.tryBegin(running), "generation can begin");
        var capture = fence.block(); check(!fence.tryBegin(new AtomicBoolean()), "capture blocks future starts");
        check(running.get(), "capture never changes an existing generation");
        capture.close(); check(fence.tryBegin(new AtomicBoolean()), "capture release restores generation");
        capture.close(); check(fence.blocking() == 0, "fence close idempotent");
        System.out.println("PASS policy: radius, dimensions, persistence, pause/resume, FIFO, stale release, pacing, generation fence");
    }
}
