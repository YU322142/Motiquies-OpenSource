package org.motiquies.client;

/** One source commit and its in-memory handoff, all inside the native processor boundary. */
final class NativeMapCommit {
    record Outcome<R>(boolean deferred, R receipt) {}
    interface Access<R> {
        void pauseRender(); void resumeRender(); NativeMapReloadQueue.Region existingRegion();
        void captureMemory(); R commitSource(); void retainDirtyRegion(R receipt);
        boolean invalidateColdCache(R receipt);
        void publishColdRegion(R receipt);
    }
    static <R> Outcome<R> attempt(Access<R> access) {
        NativeMapReloadQueue.Region region = null;
        boolean writerPaused = false;
        try {
            access.pauseRender();
            try {
                region = access.existingRegion();
                if (region != null) {
                    region.pauseWriter();
                    writerPaused = true;
                    synchronized (region.lock()) {
                        // Preflight before the merge so a retry cannot read outdated memory.
                        if (!region.canReload() || !region.invalidateCache()) return new Outcome<>(true, null);
                    }
                }
            } finally {
                access.resumeRender();
            }

            // The native runner serializes source IO. Pausing the whole renderer here
            // would make GuiMap skip terrain drawing through every ZIP decode and write.
            if (region != null) {
                synchronized (region.lock()) { access.captureMemory(); }
            }
            R receipt = access.commitSource();
            if (receipt == null) return new Outcome<>(false, null);
            if (region == null && !access.invalidateColdCache(receipt)) return new Outcome<>(true, null);

            access.pauseRender();
            try {
                if (region == null) access.publishColdRegion(receipt);
                else synchronized (region.lock()) {
                    if (region.loadState() == 2) region.clearLoadedData();
                    region.markTerrain();
                    access.retainDirtyRegion(receipt);
                }
            } finally {
                access.resumeRender();
            }
            return new Outcome<>(false, receipt);
        } finally {
            if (writerPaused) region.resumeWriter();
        }
    }
}
