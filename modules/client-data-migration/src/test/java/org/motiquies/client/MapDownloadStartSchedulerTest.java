package org.motiquies.client;

import java.util.ArrayList;
import java.util.List;

/** Exercises the actual deferred single-shot state without loading Minecraft. */
public final class MapDownloadStartSchedulerTest {
    public static void main(String[] args) {
        List<String> failures = new ArrayList<>();
        run(failures, "ACK waits through old hash/write/sync work then starts once", MapDownloadStartSchedulerTest::waitForNativeIdle);
        run(failures, "pause and new selection cancel a deferred start", MapDownloadStartSchedulerTest::cancelStart);
        run(failures, "connection and permission changes invalidate a deferred start", MapDownloadStartSchedulerTest::connectionGate);
        run(failures, "periodic sync cannot steal the pending start", MapDownloadStartSchedulerTest::periodicGate);
        if (!failures.isEmpty()) throw new AssertionError(String.join("\n", failures));
        System.out.println("Map download start scheduler tests passed (4 scenarios)");
    }

    private static void waitForNativeIdle() {
        var s = new MapDownloadStartScheduler(); Object connection = new Object();
        s.arm(connection);
        for (int tick = 0; tick < 100; tick++) {
            check(!s.take(connection, true, true, false, false), "hash scan must finish before sending the new request");
            check(s.pending(), "busy native work must not discard a confirmed request");
        }
        check(!s.take(connection, true, false, true, false) && s.pending(), "region writes must finish before sending the new request");
        check(!s.take(connection, true, false, false, true) && s.pending(), "receiving/apply callbacks must drain before sending the new request");
        check(s.take(connection, true, false, false, false), "first idle tick dispatches without needing any periodic timer");
        check(!s.pending() && !s.take(connection, true, false, false, false), "later idle ticks cannot dispatch twice");
    }

    private static void cancelStart() {
        var s = new MapDownloadStartScheduler(); Object connection = new Object();
        s.arm(connection); s.cancel();
        check(!s.pending() && !s.take(connection, true, false, false, false), "pause cancels even after ACK before native idle");
        s.arm(connection); s.cancel();
        check(!s.take(connection, true, false, false, false), "new selection cannot use the previous choice's ACK");
        s.arm(connection);
        check(s.take(connection, true, false, false, false), "new selection starts only after separately armed by its ACK");
    }

    private static void connectionGate() {
        var s = new MapDownloadStartScheduler(); Object old = new Object(), next = new Object();
        s.arm(old);
        check(!s.take(next, true, false, false, false) && !s.pending(), "connection change cancels pending work");
        check(!s.take(old, true, false, false, false), "returning to the old identity does not resurrect work");
        s.arm(old);
        check(!s.take(old, false, false, false, false) && !s.pending(), "revoked controller permission cancels pending work");
        check(!s.take(old, true, false, false, false), "later permission cannot resurrect a canceled request");
        s.arm(old);
        check(!s.take(null, true, false, false, false), "disconnect cannot dispatch");
    }

    private static void periodicGate() {
        var s = new MapDownloadStartScheduler(); Object connection = new Object();
        s.arm(connection);
        check(!s.canRunPeriodic(connection, true, false, false, false), "already-queued periodic callback cannot preempt pending manual start");
        check(s.take(connection, true, false, false, false), "manual start retains the first idle opportunity");
        check(!s.canRunPeriodic(connection, true, true, false, false), "periodic sync waits for hash scan");
        check(!s.canRunPeriodic(connection, true, false, true, false), "periodic sync waits for writes");
        check(!s.canRunPeriodic(connection, true, false, false, true), "periodic sync waits for receiving/apply work");
        check(!s.canRunPeriodic(connection, false, false, false, false), "paused controller cannot run periodic sync");
        check(!s.canRunPeriodic(null, true, false, false, false), "disconnected client cannot run periodic sync");
        check(s.canRunPeriodic(connection, true, false, false, false), "periodic sync may resume after manual start and native work have drained");
    }

    private static void run(List<String> failures, String name, Runnable test) {
        try { test.run(); System.out.println("PASS " + name); }
        catch (AssertionError e) { failures.add(name + ": " + e.getMessage()); System.out.println("FAIL " + name + ": " + e.getMessage()); }
    }
    private static void check(boolean result, String message) { if (!result) throw new AssertionError(message); }
}
