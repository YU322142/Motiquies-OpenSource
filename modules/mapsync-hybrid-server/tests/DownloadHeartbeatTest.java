package com.mapsyncer.server;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.TimeUnit;

public final class DownloadHeartbeatTest {
    public static void main(String[] args) {
        AtomicLong clock=new AtomicLong();
        List<Long> queuedAt=new ArrayList<>();
        List<String> order=new ArrayList<>();
        Queue<Runnable> server=new ArrayDeque<>();
        AtomicBoolean firstPacketTimer=new AtomicBoolean(true);
        var heartbeat=new DownloadHeartbeat(clock::get, () -> {
            queuedAt.add(clock.get()); order.add("progress-queued");
            server.add(() -> firstPacketTimer.set(false));
        },TimeUnit.SECONDS.toNanos(5));
        heartbeat.start();
        DownloadPolicyTest.check(queuedAt.equals(List.of(0L)),"first progress must queue immediately before snapshot work");
        order.add("snapshot-start");
        DownloadPolicyTest.check(order.equals(List.of("progress-queued","snapshot-start")),"slow capture begins after progress scheduling");
        // A server tick delivers already-queued progress while the worker continues linking.
        server.remove().run();
        for (int i=1;i<=70000;i++) {
            clock.set(TimeUnit.MILLISECONDS.toNanos(i));
            heartbeat.poll();
            if (i==60000) DownloadPolicyTest.check(!firstPacketTimer.get(),"capture beyond native first-packet timeout cannot cause retry");
        }
        DownloadPolicyTest.check(queuedAt.size()==15,"70-second capture has initial progress and throttled 5-second heartbeats");
        for (int i=1;i<queuedAt.size();i++) DownloadPolicyTest.check(queuedAt.get(i)-queuedAt.get(i-1)==TimeUnit.SECONDS.toNanos(5),"no per-file heartbeat flooding or long silence");
        heartbeat.poll();
        DownloadPolicyTest.check(queuedAt.size()==15,"repeated poll at same time is throttled");
        System.out.println("PASS snapshot heartbeat: initial response before capture; simulated 70000-file/70-second capture; five-second cadence; native first-packet timer stopped");
    }
}
