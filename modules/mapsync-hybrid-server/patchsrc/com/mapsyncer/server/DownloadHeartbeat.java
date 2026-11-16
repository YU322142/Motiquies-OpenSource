package com.mapsyncer.server;
import java.util.function.LongSupplier;
/** Worker-owned heartbeat cadence with an immediate first response. */
public final class DownloadHeartbeat {
    private final LongSupplier clock;
    private final Runnable notify;
    private final long intervalNanos;
    private boolean started;
    private long last;
    public DownloadHeartbeat(LongSupplier clock, Runnable notify, long intervalNanos) {
        if (intervalNanos<=0) throw new IllegalArgumentException("heartbeat interval");
        this.clock=java.util.Objects.requireNonNull(clock);
        this.notify=java.util.Objects.requireNonNull(notify); this.intervalNanos=intervalNanos;
    }
    public void start() {
        if (started) return;
        started=true; last=clock.getAsLong(); notify.run();
    }
    public void poll() {
        if (!started) { start(); return; }
        long now=clock.getAsLong();
        if (now-last>=intervalNanos) { last=now; notify.run(); }
    }
}
