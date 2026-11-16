package org.motiquies.client;

import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** A receipt is completed only after a real, still-authorized native-boundary commit. */
final class NativeMapWriteRequest<R> {
    private final CompletableFuture<R> future = new CompletableFuture<>();
    private boolean committing;
    CompletableFuture<R> future() { return future; }
    synchronized void cancel() { future.complete(null); }
    synchronized boolean mayCommit(BooleanSupplier permitted) { return !future.isDone() && permitted.getAsBoolean(); }
    /** Claim is linearized with cancel, but the filesystem operation never holds this lock. */
    <T> T finalCommit(BooleanSupplier permitted,MapTileFreshness.SourceWrite<T> operation)throws java.io.IOException {
        synchronized(this) {
            if(committing || !mayCommit(permitted)) return null;
            committing = true;
        }
        try { return operation.write(); }
        finally { synchronized(this) { committing = false; } }
    }
    boolean attempt(BooleanSupplier ready, BooleanSupplier permitted, Supplier<NativeMapCommit.Outcome<R>> operation) {
        if (future.isDone()) return true;
        if (!permitted.getAsBoolean()) { cancel(); return true; }
        if (!ready.getAsBoolean()) return false;
        return attempt(permitted, operation);
    }
    boolean attempt(BooleanSupplier permitted, Supplier<NativeMapCommit.Outcome<R>> operation) {
        if (future.isDone()) return true;
        if (!permitted.getAsBoolean()) { cancel(); return true; }
        try {
            var result = operation.get();
            if (result.deferred()) return false;
            future.complete(permitted.getAsBoolean() ? result.receipt() : null);
            return true;
        } catch (RuntimeException | Error failure) {
            future.completeExceptionally(failure);
            throw failure;
        }
    }
}
