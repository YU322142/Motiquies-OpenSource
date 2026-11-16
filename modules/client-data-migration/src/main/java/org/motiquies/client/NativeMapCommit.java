package org.motiquies.client;

/** One source commit and its in-memory handoff, all inside the native processor boundary. */
final class NativeMapCommit {
    record Outcome<R>(boolean deferred, R receipt) {}
    interface Access<R> {
        void pauseRender(); void resumeRender(); NativeMapReloadQueue.Region existingRegion();
        R commitSource(); boolean affectsRegion(R receipt); boolean currentView(); void retainDirtyRegion();
        boolean invalidateColdCache(R receipt);
    }
    static <R> Outcome<R> attempt(Access<R> access) {
        access.pauseRender();
        try {
            var region = access.existingRegion();
            if (region == null) {
                R receipt=access.commitSource();
                return receipt==null||access.invalidateColdCache(receipt)?new Outcome<>(false,receipt):new Outcome<>(true,null);
            }
            region.pauseWriter();
            try {
                synchronized (region.lock()) {
                    if (!region.canReload()) return new Outcome<>(true, null);
                    // Preflight the fallible cache operation before a source merge. Otherwise
                    // a retry could mistake the still-loaded old pixels for new exploration.
                    if (!region.invalidateCache()) return new Outcome<>(true, null);
                    R receipt = access.commitSource();
                    if (receipt == null || !access.affectsRegion(receipt)) return new Outcome<>(false, receipt);
                    if (region.loadState() == 2) region.clearLoadedData();
                    region.markTerrain();
                    // requestLoad prepares highlights and is main-thread-only. State4 already
                    // contains no old terrain, so native saves cannot undo this source commit.
                    access.retainDirtyRegion();
                    return new Outcome<>(false, receipt);
                }
            } finally {
                region.resumeWriter();
            }
        } finally {
            access.resumeRender();
        }
    }
}
