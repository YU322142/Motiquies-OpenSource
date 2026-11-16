package com.mapsyncer.server;
import java.util.concurrent.atomic.AtomicBoolean;
public final class DownloadGenerationGate {
 public static final DownloadGenerationGate INSTANCE = new DownloadGenerationGate();
 private int count;
 public synchronized boolean tryBegin(AtomicBoolean running) {
     return count == 0 && running.compareAndSet(false,true);
 }
 public final class Lease implements AutoCloseable {
     private boolean closed;
     public void close() { synchronized (DownloadGenerationGate.this) { if (!closed) { closed=true; count--; } } }
 }
 public synchronized Lease block() { count++; return new Lease(); }
 public synchronized int blocking() { return count; }
}
