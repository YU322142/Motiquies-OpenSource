package org.motiquies.client;

import java.util.ArrayList;
import java.util.List;

public final class NativeMapCommitTest {
    public static void main(String[] args) {
        List<String> failures = new ArrayList<>();
        run(failures, "far map source commits without allocating or loading a region", NativeMapCommitTest::diskOnly);
        run(failures, "source commit and loaded memory replacement share one boundary", NativeMapCommitTest::replaceLoaded);
        run(failures, "busy and failed invalidation do not issue premature receipts", NativeMapCommitTest::deferredReceipt);
        run(failures, "stale connection generation or canceled request cannot commit", NativeMapCommitTest::cancelAndFailure);
        run(failures, "inactive dimension clears old source memory and retains deferred load", NativeMapCommitTest::inactive);
        run(failures, "temporary native setup gap defers authorized write instead of dropping its receipt", NativeMapCommitTest::temporarySetupGap);
        if (!failures.isEmpty()) throw new AssertionError(String.join("\n", failures));
        System.out.println("Native map source commit tests passed (6 scenarios)");
    }
    private static void diskOnly() {
        var access = new Access(); access.existing = false;
        var result = NativeMapCommit.attempt(access);
        check(!result.deferred() && "real-crc".equals(result.receipt()) && access.commits == 1, "disk-only commit returns the original real receipt");
        check(access.loads == 0 && access.clears == 0 && access.lookups == 1, "no existing region means no allocation, clear, or load");
        check(access.coldInvalidations==1,"disk-only source commit must invalidate old on-disk textures without allocating a region");
        check(access.steps.equals(List.of("commit", "detect")), "cold source must be visible to native viewing without allocating pixels");
        check(!access.renderPaused, "disk-only commit releases render isolation");
        var blocked=new Access();blocked.existing=false;blocked.invalidationReady=false;
        var request=new NativeMapWriteRequest<String>();
        check(!request.attempt(()->true,()->NativeMapCommit.attempt(blocked))&&!request.future().isDone(),"cold invalidation failure must defer actual receipt");
        blocked.invalidationReady=true;
        check(request.attempt(()->true,()->NativeMapCommit.attempt(blocked))&&"real-crc".equals(request.future().join()),"retry acknowledges only after cold cache invalidation succeeds");
    }
    private static void replaceLoaded() {
        var access = new Access();
        // An old native save completed BEFORE the boundary. The authoritative new commit must win.
        access.disk = 1;
        var result = NativeMapCommit.attempt(access);
        check(!result.deferred() && access.disk == 2 && access.memory == 0 && access.state == 4 && access.clears == 1 && access.loads == 0 && access.dirty,
                "native commit must clear old terrain into state4 and retain main-thread handoff before returning real receipt");
        check(access.steps.equals(List.of("invalidate", "snapshot", "commit", "clear", "terrain")), "capture and replacement use short native pauses around the source commit");
        check(!access.renderPaused && !access.writerPaused, "both native pauses balance");
        finishOnMain(access);
        check(access.memory == 2 && access.loads == 1, "main-thread handoff reads new source after native worker releases all locks");
    }
    private static void deferredReceipt() {
        var access = new Access(); var request = new NativeMapWriteRequest<String>();
        access.ready = false;
        check(!request.attempt(() -> true, () -> NativeMapCommit.attempt(access)), "busy native region must defer the whole commit");
        check(!request.future().isDone() && access.commits == 0, "no CRC receipt or source overwrite while native state is busy");
        access.ready = true; access.invalidationReady = false;
        check(!request.attempt(() -> true, () -> NativeMapCommit.attempt(access)) && !request.future().isDone(), "failed invalidation must not acknowledge a commit");
        check(access.clears == 0 && access.loads == 0 && !access.writerPaused && !access.renderPaused, "failed invalidation retains terrain and releases pauses");
        check(access.commits == 0 && access.disk == 1, "failed invalidation must occur BEFORE merge/source write, avoiding old-memory retry rollback");
        access.disk = 1; // A native save between deferred attempts still belongs to the original source.
        access.invalidationReady = true;
        check(request.attempt(() -> true, () -> NativeMapCommit.attempt(access)), "ready retry completes");
        check("real-crc".equals(request.future().join()) && access.memory == 0 && access.disk == 2 && access.commits == 1, "retry commits exactly once after cache preflight and clears old memory before receipt");
        finishOnMain(access);
        check(access.memory == 2, "deferred write later reads fresh data on main");
    }
    private static void cancelAndFailure() {
        var access = new Access(); var stale = new NativeMapWriteRequest<String>();
        check(stale.attempt(() -> false, () -> NativeMapCommit.attempt(access)) && stale.future().join() == null && access.commits == 0, "stale token produces no receipt or source write");
        var canceled = new NativeMapWriteRequest<String>(); canceled.cancel();
        canceled.attempt(() -> true, () -> NativeMapCommit.attempt(access));
        check(access.commits == 0, "timeout/disconnect cancellation cannot revive work");
        var failing = new NativeMapWriteRequest<String>(); access.failLoad = true;
        boolean propagated = false;
        try { failing.attempt(() -> true, () -> NativeMapCommit.attempt(access)); }
        catch (IllegalStateException expected) { propagated = true; }
        check(propagated && failing.future().isCompletedExceptionally(), "native exceptions remain visible to both the native runner and original IO callback");
        check(!access.writerPaused && !access.renderPaused, "failure releases native pauses");
    }
    private static void inactive() {
        var access = new Access(); access.active = false;
        var result = NativeMapCommit.attempt(access);
        check(!result.deferred() && access.disk == 2 && access.state == 4 && access.clears == 1 && access.dirty && access.loads == 0, "inactive loaded map must not later save stale memory or load into the current dimension");
    }
    private static void finishOnMain(Access region) {
        var queue = new NativeMapReloadQueue();
        var context = new NativeMapReloadQueue.Context(new Object(), new Object(), new Object(), "mw$1", 1);
        var key = new NativeMapReloadQueue.Key(0, 0, Integer.MAX_VALUE);
        queue.enqueuePrepared(context, key, region);
        region.nativePhase = false;
        var result = NativeMapReloadQueue.handoff(queue.next(context), new NativeMapReloadQueue.Access() {
            public void pauseRender() { region.pauseRender(); }
            public void resumeRender() { region.resumeRender(); }
            public boolean mayLoadAnother() { return true; }
            public NativeMapReloadQueue.Region find(NativeMapReloadQueue.Key ignored) { return region; }
        });
        check(result == NativeMapReloadQueue.Handoff.DONE, "main handoff must finish prepared source");
        region.nativePhase = true;
    }
    private static void temporarySetupGap() {
        var access = new Access(); var request = new NativeMapWriteRequest<String>();
        check(!request.attempt(() -> false, () -> true, () -> NativeMapCommit.attempt(access)), "same-session missing native processor is a retry, not cancellation");
        check(!request.future().isDone() && access.commits == 0, "transient setup must preserve pending payload/receipt");
        check(request.attempt(() -> true, () -> true, () -> NativeMapCommit.attempt(access)), "native setup recovery dispatches the preserved write");
        check("real-crc".equals(request.future().join()) && access.commits == 1, "recovered write returns only its actual receipt");
    }
    private static final class Access implements NativeMapCommit.Access<String>, NativeMapReloadQueue.Region {
        boolean existing = true, ready = true, invalidationReady = true, renderPaused, writerPaused, active = true, dirty, invalidated, failLoad, nativePhase = true;
        int disk = 1, memory = 1, commits, loads, clears, lookups, coldInvalidations; final List<String> steps = new ArrayList<>();
        byte state = 2;
        public void pauseRender() { check(!renderPaused, "render isolation must not nest"); renderPaused = true; }
        public void resumeRender() { renderPaused = false; }
        public NativeMapReloadQueue.Region existingRegion() { check(renderPaused, "lookup must be isolated"); lookups++; return existing ? this : null; }
        public void captureMemory() { check(!renderPaused && writerPaused && Thread.holdsLock(this), "memory capture is isolated to the source leaf"); steps.add("snapshot"); }
        public void publishColdRegion(String receipt) { check(renderPaused && !writerPaused, "native detection must publish under the render boundary"); steps.add("detect"); }
        public String commitSource() {
            check(!renderPaused && writerPaused == existing && !Thread.holdsLock(this), "ZIP merge and source IO must leave the world map drawable");
            steps.add("commit"); commits++; disk = 2; return "real-crc";
        }
        public boolean invalidateColdCache(String receipt){check(!renderPaused&&!writerPaused,"cold cache IO must leave the world map drawable");coldInvalidations++;return invalidationReady;}
        public void retainDirtyRegion(String receipt) { locked(); dirty = true; }
        public Object lock() { return this; }
        public void pauseWriter() { check(nativePhase, "main handoff must not acquire native writer pause"); check(renderPaused, "render pause precedes writer"); writerPaused = true; }
        public void resumeWriter() { writerPaused = false; }
        public boolean canReload() { locked(); return ready; }
        public byte loadState() { locked(); return state; }
        public boolean loadStarted() { locked(); return state == 1 || state == 2; }
        public boolean invalidateCache() { locked(); if (failLoad) throw new IllegalStateException("native invalidation failure"); steps.add("invalidate"); invalidated = invalidationReady; return invalidated; }
        public void clearLoadedData() { locked(); check(invalidated, "invalidate before clear"); steps.add("clear"); clears++; state = 4; memory = 0; }
        public void markTerrain() { locked(); steps.add("terrain"); }
        public void load() { check(!nativePhase, "requestLoad/highlight preparation must run on main, never the native source-commit worker"); locked(); steps.add("load"); loads++; memory = disk; state = 2; }
        public void prioritize() { locked(); steps.add("prioritize"); }
        private void locked() { check(renderPaused && (nativePhase ? writerPaused : !writerPaused) && Thread.holdsLock(this), "worker commit uses writer isolation; main load uses native render-to-region order"); }
    }
    private static void run(List<String> failures, String name, Runnable test) {
        try { test.run(); System.out.println("PASS " + name); }
        catch (AssertionError e) { failures.add(name + ": " + e.getMessage()); System.out.println("FAIL " + name + ": " + e.getMessage()); }
    }
    private static void check(boolean result, String message) { if (!result) throw new AssertionError(message); }
}
