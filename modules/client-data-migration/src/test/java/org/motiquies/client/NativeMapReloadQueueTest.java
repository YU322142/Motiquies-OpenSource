package org.motiquies.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Boundary tests for the native region lifecycle; the production queue owns locking/retries. */
public final class NativeMapReloadQueueTest {
    public static void main(String[] args) {
        List<String> failures = new ArrayList<>();
        run(failures, "busy native recache is retained then retried under region lock", NativeMapReloadQueueTest::busyRegionRetries);
        run(failures, "loaded and unloaded regions read new disk data instead of stale memory/cache", NativeMapReloadQueueTest::nativeStateBranches);
        run(failures, "native viewing throttle and missing region defer without loss", NativeMapReloadQueueTest::nativeThrottle);
        run(failures, "surface and cave layers stay separate with deduplicated requests", NativeMapReloadQueueTest::layersAndDuplicates);
        run(failures, "changed connection or map processor cancels stale work", NativeMapReloadQueueTest::contextGate);
        run(failures, "completed writes survive generation dimension and multiworld transitions", NativeMapReloadQueueTest::completedWritesSurviveTransitions);
        run(failures, "new write during an in-flight reload retains a newer ticket", NativeMapReloadQueueTest::newWriteDuringTask);
        run(failures, "failed native cache invalidation retries without clearing terrain", NativeMapReloadQueueTest::invalidationMustSucceed);
        run(failures, "native exceptions unwind writer and render pauses", NativeMapReloadQueueTest::pauseUnwind);
        run(failures, "prepared identity prevents stale loaded replacement from being acknowledged", NativeMapReloadQueueTest::replacementNeedsPrepare);
        run(failures, "main handoff waits for busy native state and accepts same-object fresh load", NativeMapReloadQueueTest::mainHandoffState);
        run(failures, "busy prepared handoff rotates without losing its identity", NativeMapReloadQueueTest::busyPreparedFairness);
        if (!failures.isEmpty()) throw new AssertionError(String.join("\n", failures));
        System.out.println("Native map reload queue tests passed (12 scenarios)");
    }

    private static void busyRegionRetries() {
        var queue = new NativeMapReloadQueue(); var context = context(); var key = new NativeMapReloadQueue.Key(0, 0, Integer.MAX_VALUE);
        var region = new Region((byte) 2); region.ready = false; var access = new Access(); access.regions.put(key, region);
        queue.enqueue(context, key);
        drain(queue, context, access);
        check(queue.size() == 1 && region.loads == 0, "a recache/reload/refresh-busy region must stay pending");
        region.ready = true;
        drain(queue, context, access);
        check(queue.size() == 0 && region.loads == 1 && region.memoryVersion == 2, "the pending write is reloaded from disk when native lifecycle permits it");
        check(region.prioritized == 1 && region.terrain, "native lifecycle is marked and viewing priority updated");
    }

    private static void nativeStateBranches() {
        for (byte state : new byte[]{2, 0, 4}) {
            var queue = new NativeMapReloadQueue(); var context = context(); var key = new NativeMapReloadQueue.Key(-1, 0, 0);
            var region = new Region(state); var access = new Access(); access.regions.put(key, region);
            queue.enqueue(context, key); drain(queue, context, access); drain(queue, context, access);
            check(region.loads == 1 && region.clears == (state == 2 ? 1 : 0), "state " + state + " must invalidate native cache, clear loaded data and load exactly once");
            check(region.memoryVersion == 2 && region.invalidated, "new ZIP data must replace stale in-memory and texture-cache content");
            check(region.state == 2, "native load must finish with fresh loaded terrain");
        }
    }

    private static void nativeThrottle() {
        var queue = new NativeMapReloadQueue(); var context = context(); var key = new NativeMapReloadQueue.Key(1, -1, Integer.MAX_VALUE);
        var access = new Access(); access.allowAnother = false; var region = new Region((byte) 0); access.regions.put(key, region);
        queue.enqueue(context, key); drain(queue, context, access);
        check(queue.size() == 1 && !region.terrain, "native nextToLoadByViewing throttle prevents starting work");
        access.allowAnother = true; access.regions.clear(); drain(queue, context, access);
        check(queue.size() == 1, "region not yet available must retry later");
        access.regions.put(key, region); drain(queue, context, access);
        check(queue.size() == 0 && region.loads == 1, "missing-region retry eventually loads downloaded data");
    }

    private static void layersAndDuplicates() {
        var queue = new NativeMapReloadQueue(); var context = context(); var access = new Access();
        var surface = new NativeMapReloadQueue.Key(0, 0, Integer.MAX_VALUE); var cave = new NativeMapReloadQueue.Key(0, 0, 0);
        var surfaceRegion = new Region((byte) 4); var caveRegion = new Region((byte) 0);
        access.regions.put(surface, surfaceRegion); access.regions.put(cave, caveRegion);
        queue.enqueue(context, surface); queue.enqueue(context, surface); queue.enqueue(context, cave);
        check(queue.size() == 2, "same coordinates on different layers must not collapse");
        drain(queue, context, access);
        check(queue.size() == 1, "at most one region is submitted per render pass");
        drain(queue, context, access);
        check(surfaceRegion.loads == 1 && caveRegion.loads == 1, "surface and cave data both reach native loading");
    }

    private static void contextGate() {
        var original = context();
        var changed = new NativeMapReloadQueue.Context[]{
                new NativeMapReloadQueue.Context(new Object(), original.processor(), original.dimension(), "mw$1", 7),
                new NativeMapReloadQueue.Context(original.connection(), new Object(), original.dimension(), "mw$1", 7),
                null
        };
        for (var next : changed) {
            var queue = new NativeMapReloadQueue(); var key = new NativeMapReloadQueue.Key(0, 0, 0);
            var access = new Access(); var region = new Region((byte) 4); access.regions.put(key, region);
            queue.enqueue(original, key); drain(queue, next, access);
            check(queue.size() == 0 && region.loads == 0, "changed context must cancel stale region work");
            drain(queue, original, access);
            check(region.loads == 0, "returning to previous context cannot resurrect a canceled reload");
        }
    }

    private static void completedWritesSurviveTransitions() {
        var queue = new NativeMapReloadQueue(); var original = context();
        var sameMapNewGeneration = new NativeMapReloadQueue.Context(original.connection(), original.processor(), original.dimension(), "mw$1", 8);
        var key = new NativeMapReloadQueue.Key(0, 0, Integer.MAX_VALUE);
        var originalAccess = new Access(); var originalRegion = new Region((byte) 4); originalAccess.regions.put(key, originalRegion);
        queue.enqueue(original, key); drain(queue, sameMapNewGeneration, originalAccess);
        check(originalRegion.loads == 1, "pause/new sync generation cannot lose the refresh of an already completed disk write");
        for (var other : new NativeMapReloadQueue.Context[]{
                new NativeMapReloadQueue.Context(original.connection(), original.processor(), new Object(), "mw$1", 8),
                new NativeMapReloadQueue.Context(original.connection(), original.processor(), original.dimension(), "mw$2", 8)
        }) {
            queue = new NativeMapReloadQueue(); originalRegion = new Region((byte) 4); originalAccess.regions.put(key, originalRegion);
            var otherAccess = new Access(); var otherRegion = new Region((byte) 0); otherAccess.regions.put(key, otherRegion);
            queue.enqueue(original, key); drain(queue, other, otherAccess);
            check(queue.size() == 1 && otherRegion.loads == 0, "leaving a dimension/multiworld suspends its dirty notification");
            queue.enqueue(other, key);
            check(queue.size() == 2, "identical coordinates in different maps remain distinct");
            drain(queue, original, originalAccess);
            check(originalRegion.loads == 1 && otherRegion.loads == 0 && queue.size() == 1, "returning to the owning map applies its retained completed write");
            drain(queue, other, otherAccess);
            check(otherRegion.loads == 1 && queue.size() == 0, "the other map also retains and applies its own write");
        }
    }

    private static NativeMapReloadQueue.Context context() {
        return new NativeMapReloadQueue.Context(new Object(), new Object(), new Object(), "mw$1", 7);
    }
    private static void newWriteDuringTask() {
        var queue = new NativeMapReloadQueue(); var context = context(); var key = new NativeMapReloadQueue.Key(0, 0, 0);
        queue.enqueue(context, key); var old = queue.next(context);
        queue.enqueue(context, key); queue.complete(old, true);
        check(queue.size() == 1, "a completed older task cannot acknowledge a newer on-disk write");
        var next = queue.next(context);
        check(next.revision() != old.revision(), "new disk write requires a distinct completion ticket");
        queue.complete(next, true);
        check(queue.size() == 0, "the latest write can be acknowledged normally");
    }

    private static void invalidationMustSucceed() {
        var queue = new NativeMapReloadQueue(); var context = context(); var key = new NativeMapReloadQueue.Key(0, 0, 0);
        var access = new Access(); var region = new Region((byte) 2); access.regions.put(key, region);
        region.allowInvalidation = false; queue.enqueue(context, key); drain(queue, context, access);
        check(queue.size() == 1 && region.clears == 0 && region.loads == 0, "failed invalidation must not clear terrain or select old texture cache");
        check(!region.renderPaused && !region.writerPaused, "deferred invalidation releases both native pauses");
        region.allowInvalidation = true; drain(queue, context, access);
        check(queue.size() == 0 && region.memoryVersion == 2, "successful invalidation retries actual disk load");
    }

    private static void pauseUnwind() {
        var queue = new NativeMapReloadQueue(); var context = context(); var key = new NativeMapReloadQueue.Key(0, 0, 0);
        var access = new Access(); var region = new Region((byte) 2); access.regions.put(key, region);
        region.failLoad = true; queue.enqueue(context, key);
        boolean propagated = false;
        try { drain(queue, context, access); }
        catch (IllegalStateException expected) { propagated = true; }
        check(propagated, "native load exception must remain visible");
        check(!region.renderPaused && !region.writerPaused && queue.size() == 1, "exception must release native pauses without acknowledging unfinished work");
    }
    private static void replacementNeedsPrepare() {
        var queue = new NativeMapReloadQueue(); var context = context(); var key = new NativeMapReloadQueue.Key(0, 0, 0);
        var access = new Access(); var old = new Region((byte) 2); access.regions.put(key, old);
        queue.enqueue(context, key); var ticket = queue.next(context);
        queue.prepared(ticket, NativeMapReloadQueue.prepare(ticket, access));
        var replacement = new Region((byte) 2); access.regions.put(key, replacement); replacement.nativePhase = false;
        var result = NativeMapReloadQueue.handoff(queue.next(context), access);
        check(result == NativeMapReloadQueue.Handoff.REPREPARE && replacement.memoryVersion == 1 && replacement.loads == 0,
                "a different loaded2 object cannot be mistaken for the prepared object's fresh native load");
        queue.prepared(queue.next(context), null); replacement.nativePhase = true;
        drain(queue, context, access);
        check(queue.size() == 0 && replacement.clears == 1 && replacement.memoryVersion == 2,
                "replacement must cross native prepare before main loads its fresh data");
    }

    private static void mainHandoffState() {
        var queue = new NativeMapReloadQueue(); var context = context(); var key = new NativeMapReloadQueue.Key(0, 0, 0);
        var access = new Access(); var region = new Region((byte) 2); access.regions.put(key, region);
        queue.enqueue(context, key); var ticket = queue.next(context);
        queue.prepared(ticket, NativeMapReloadQueue.prepare(ticket, access));
        check(region.loads == 0 && region.state == 4 && region.memoryVersion == 0, "native prepare must not perform main-only highlight/load work");
        region.nativePhase = false; region.ready = false;
        check(NativeMapReloadQueue.handoff(queue.next(context), access) == NativeMapReloadQueue.Handoff.WAIT && queue.size() == 1,
                "busy main handoff retains prepared ticket without a second clear");
        region.ready = true; region.state = 2; region.memoryVersion = 2; // Native main view-loader beat our callback.
        check(NativeMapReloadQueue.handoff(queue.next(context), access) == NativeMapReloadQueue.Handoff.DONE && region.loads == 0 && region.clears == 1,
                "same prepared object already loaded naturally is fresh; do not clear or request again");
        queue.complete(queue.next(context), true);
        check(queue.size() == 0, "natural fresh load acknowledges only this prepared ticket");
    }
    private static void busyPreparedFairness() {
        var queue = new NativeMapReloadQueue(); var context = context(); var first = new NativeMapReloadQueue.Key(0, 0, 0);
        var second = new NativeMapReloadQueue.Key(1, 0, 0); var access = new Access();
        var busy = new Region((byte) 4); busy.ready = false; busy.nativePhase = false; busy.invalidated = true;
        var ready = new Region((byte) 4); ready.nativePhase = false; ready.invalidated = true;
        access.regions.put(first, busy); access.regions.put(second, ready);
        queue.enqueuePrepared(context, first, busy); queue.enqueuePrepared(context, second, ready);
        var blocked = queue.next(context);
        var result = NativeMapReloadQueue.handoff(blocked, access);
        check(result == NativeMapReloadQueue.Handoff.WAIT, "first target is native-busy");
        queue.completeHandoff(blocked, result);
        var next = queue.next(context);
        check(next.key().equals(second), "one busy prepared region must not block later ready targets or source-write arbitration");
        queue.completeHandoff(next, NativeMapReloadQueue.handoff(next, access));
        check(ready.loads == 1 && queue.size() == 1 && queue.next(context).prepared() == busy,
                "ready work progresses while busy target retains its prepared native identity");
    }
    private static void drain(NativeMapReloadQueue queue, NativeMapReloadQueue.Context context, Access access) {
        var ticket = queue.next(context);
        if (ticket == null) return;
        if (ticket.prepared() == null) {
            Object prepared = NativeMapReloadQueue.prepare(ticket, access);
            if (prepared == null) { queue.complete(ticket, false); return; }
            queue.prepared(ticket, prepared);
            ticket = queue.next(context);
        }
        for (Region region : access.regions.values()) region.nativePhase = false;
        try {
            var result = NativeMapReloadQueue.handoff(ticket, access);
            if (result == NativeMapReloadQueue.Handoff.DONE) queue.complete(ticket, true);
            if (result == NativeMapReloadQueue.Handoff.REPREPARE) queue.prepared(ticket, null);
        } finally { for (Region region : access.regions.values()) region.nativePhase = true; }
    }
    private static final class Access implements NativeMapReloadQueue.Access {
        final Map<NativeMapReloadQueue.Key, Region> regions = new HashMap<>(); boolean allowAnother = true, renderPaused;
        public void pauseRender() { check(!renderPaused, "render isolation cannot be nested"); renderPaused = true; }
        public void resumeRender() {
            for (Region region : regions.values()) {
                check(!region.writerPaused, "region writer pause must unwind before global render isolation");
                region.renderPaused = false;
            }
            renderPaused = false;
        }
        public boolean mayLoadAnother() { return allowAnother; }
        public NativeMapReloadQueue.Region find(NativeMapReloadQueue.Key key) {
            check(renderPaused, "region lookup/creation must occur after native render isolation");
            Region region = regions.get(key);
            if (region != null) region.renderPaused = renderPaused;
            return region;
        }
    }
    private static final class Region implements NativeMapReloadQueue.Region {
        byte state; boolean ready = true, terrain, invalidated, allowInvalidation = true, failLoad;
        boolean renderPaused, writerPaused, nativePhase = true; int loads, clears, prioritized, memoryVersion = 1;
        Region(byte state) { this.state = state; }
        public Object lock() { return this; }
        public void pauseWriter() { check(nativePhase, "main handoff must match native render-to-region lock order without writer-pause inversion"); check(renderPaused && !writerPaused, "writer pause requires render isolation"); writerPaused = true; }
        public void resumeWriter() { check(writerPaused && renderPaused, "writer pause must unwind inside render isolation"); writerPaused = false; }
        public void markTerrain() { terrain = true; }
        public boolean canReload() { locked(); return ready; }
        public byte loadState() { locked(); return state; }
        public boolean loadStarted() { locked(); return state == 1 || state == 2; }
        public void load() { check(!nativePhase, "reload request/highlight preparation must wait for main-thread handoff"); locked(); if (failLoad) throw new IllegalStateException("native load failure"); loads++; memoryVersion = invalidated ? 2 : 1; state = 2; }
        public boolean invalidateCache() { locked(); invalidated = allowInvalidation; return invalidated; }
        public void clearLoadedData() { locked(); check(invalidated, "native cache must be invalid before loaded data is cleared"); clears++; state = 4; memoryVersion = 0; }
        public void prioritize() { locked(); prioritized++; }
        private void locked() {
            check(renderPaused && (nativePhase ? writerPaused : !writerPaused), "worker uses writer isolation; main submission uses only render and region monitor");
            check(Thread.holdsLock(this), "native state check and submission must hold synchronized(region)");
        }
    }
    private static void run(List<String> failures, String name, Runnable test) {
        try { test.run(); System.out.println("PASS " + name); }
        catch (AssertionError e) { failures.add(name + ": " + e.getMessage()); System.out.println("FAIL " + name + ": " + e.getMessage()); }
    }
    private static void check(boolean result, String message) { if (!result) throw new AssertionError(message); }
}
