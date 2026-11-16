package com.mapsyncer.server;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

/** Never substitutes serialization callbacks for successfully completed native writes. */
public final class SavedWriteTracker {
    public static void track(CompletableFuture<Void> written,DirtyRegionQueue queue,DirtyRegionQueue.Key key,LongSupplier clock){
        written.whenComplete((ignored,failure)->{if(failure==null)queue.mark(key,clock.getAsLong());});
    }
    private SavedWriteTracker(){}
}
