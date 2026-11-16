package org.motiquies.client;

import com.mapsyncer.client.ClientSyncSession;
import com.mapsyncer.client.RegionPipelineTracker;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xaero.map.MapProcessor;
import xaero.map.WorldMap;
import xaero.map.WorldMapSession;
import xaero.map.file.MapSaveLoad;
import xaero.map.region.MapRegion;
import xaero.map.task.MapRunnerTask;

/** Adapts MapSyncer's disk-write notifications to Xaero's native reload lifecycle. */
public final class NativeMapReloadBridge {
    private static final Logger LOG = LogManager.getLogger();
    private static final NativeMapReloadQueue QUEUE = new NativeMapReloadQueue();
    private static final NativeReloadNoticeQueue NOTICES = new NativeReloadNoticeQueue();
    // The queue and this flag belong to the render thread; native tasks only consume tickets.
    private static boolean taskInFlight;
    private static volatile NativeMapReloadQueue.Context activeContext;

    private NativeMapReloadBridge() {}

    static void register() {
        NeoForge.EVENT_BUS.addListener(NativeMapReloadBridge::frame);
        NeoForge.EVENT_BUS.addListener(NativeMapReloadBridge::logout);
    }

    /** Called only from MapPacketHandler's main-thread write/reload callbacks. */
    public static void enqueue(int x, int z, int caveLayer, Path sourceDirectory) {
        Minecraft client = Minecraft.getInstance();
        var context = context();
        var last = context != null ? context : captureContext();
        Object dimensionId = client.level == null ? null : client.level.dimension();
        NOTICES.enqueue(client.getConnection(), last == null ? null : last.processor(), dimensionId,
                sourceDirectory, new NativeMapReloadQueue.Key(x, z, caveLayer));
        resolveNotices(context);
    }

    private static void resolveNotices(NativeMapReloadQueue.Context context) {
        if (context == null) return;
        MapProcessor processor = (MapProcessor) context.processor();
        requireMainThread();
        synchronized (processor.renderThreadPauseSync) {
            if (!mainViewReady(processor, context)) return;
            var dimension = processor.getMapWorld().getCurrentDimension();
            if (dimension == null || processor.getCurrentWorldId() == null || processor.getCurrentDimId() == null
                    || processor.getCurrentMWId() == null) return;
            Path directory = processor.getMapSaveLoad().getMWSubFolder(processor.getCurrentWorldId(),
                    processor.getCurrentDimId(), processor.getCurrentMWId());
            NOTICES.resolve(context, dimension.getDimId(), directory, key -> QUEUE.enqueue(context, key));
        }
    }

    private static NativeMapReloadQueue.Context context() {
        Minecraft client = Minecraft.getInstance();
        var connection = client.getConnection();
        WorldMapSession session = WorldMapSession.getCurrentSession();
        if (connection == null || client.level == null || session == null) return null;
        MapProcessor processor = session.getMapProcessor();
        if (processor == null || processor.getMapWorld() == null) return null;
        var dimension = processor.getMapWorld().getCurrentDimension();
        if (dimension == null) return null;
        return new NativeMapReloadQueue.Context(connection, processor, dimension,
                processor.getCurrentMWId(), ClientSyncSession.get().generation());
    }
    static NativeMapReloadQueue.Context captureContext() {
        var resolved = context();
        if (resolved != null) { activeContext = resolved; return resolved; }
        var last = activeContext;
        if (last == null || Minecraft.getInstance().getConnection() != last.connection()) return null;
        return new NativeMapReloadQueue.Context(last.connection(), last.processor(), last.dimension(), last.multiworld(),
                ClientSyncSession.get().generation());
    }
    static NativeMapReloadQueue.Context activeContext() { return activeContext; }
    static void retainCompleted(NativeMapReloadQueue.Context context, NativeMapReloadQueue.Key key, Object prepared) {
        QUEUE.enqueuePrepared(context, key, prepared);
    }
    static void nativeTaskFinished() { taskInFlight = false; }

    private static void frame(RenderFrameEvent.Post event) {
        var context = context();
        // Dimension/session setup can be transiently unavailable during a transition.
        // Logout or a changed connection/processor clears obsolete region identities.
        if (context == null) {
            var connection = Minecraft.getInstance().getConnection();
            if (connection == null || (activeContext != null && activeContext.connection() != connection)) {
                activeContext = null;
                NativeMapWriteBridge.cancelAll();
                NOTICES.clear();
            } else NativeMapWriteBridge.cancelStale(); // Proven generation changes still cancel.
            return;
        }
        activeContext = context;
        NativeMapWriteBridge.cancelStale();
        QUEUE.updateContext(context);
        resolveNotices(context);
        if (taskInFlight || WorldMap.mapRunner == null) return;
        var ticket = QUEUE.next(context);
        if (ticket != null && ticket.prepared() != null) {
            var handoff = handoffOnMain(ticket);
            QUEUE.completeHandoff(ticket, handoff);
            if (handoff != NativeMapReloadQueue.Handoff.WAIT) return;
            ticket = QUEUE.next(context);
        }
        MapRunnerTask write = NativeMapWriteBridge.nextTask();
        if (write != null) {
            taskInFlight = true;
            WorldMap.mapRunner.addTask(write);
            return;
        }
        if (ticket == null || ticket.prepared() != null) return;
        MapProcessor processor = (MapProcessor) context.processor();
        MapRegion region = createOnMain(processor, context, ticket.key());
        if (region == null) return;
        taskInFlight = true;
        WorldMap.mapRunner.addTask(new ReloadTask(ticket, region));
    }
    private static void requireMainThread() {
        if (!Minecraft.getInstance().isSameThread()) throw new IllegalStateException("Xaero region creation/load submission requires the main thread");
    }
    private static boolean mainViewReady(MapProcessor processor, NativeMapReloadQueue.Context context) {
        return !processor.isRenderingPaused() && !processor.isUploadingPaused() && !processor.isWaitingForWorldUpdate()
                && context.sameMap(activeContext) && processor.getMapWorld().getCurrentDimension() == context.dimension()
                && Objects.equals(processor.getCurrentMWId(), context.multiworld());
    }
    private static NativeMapReloadQueue.Handoff handoffOnMain(NativeMapReloadQueue.Ticket ticket) {
        requireMainThread();
        MapProcessor processor = (MapProcessor) ticket.context().processor();
        synchronized (processor.renderThreadPauseSync) {
            if (!mainViewReady(processor, ticket.context())) return NativeMapReloadQueue.Handoff.WAIT;
            return NativeMapReloadQueue.handoff(ticket, access(processor, ticket.context()));
        }
    }
    private static MapRegion createOnMain(MapProcessor processor, NativeMapReloadQueue.Context context, NativeMapReloadQueue.Key key) {
        requireMainThread();
        synchronized (processor.renderThreadPauseSync) {
            return mainViewReady(processor, context) ? leafOnMain(processor, key) : null;
        }
    }
    private static MapRegion leafOnMain(MapProcessor processor, NativeMapReloadQueue.Key key) {
        requireMainThread();
        return processor.getLeafMapRegion(key.caveLayer(), key.x(), key.z(), true);
    }

    /** One bounded attempt between complete native IO/cache/refresh cycles, never a spin loop. */
    private static final class ReloadTask extends MapRunnerTask {
        private final NativeMapReloadQueue.Ticket ticket;
        private final MapRegion region;
        private ReloadTask(NativeMapReloadQueue.Ticket ticket, MapRegion region) { this.ticket = ticket; this.region = region; }

        @Override
        public void run(MapProcessor processor) {
            Object prepared = null;
            try {
                var expected = ticket.context();
                var active = activeContext;
                if (processor == expected.processor() && processor.getMapWorld() != null && expected.sameMap(active)
                        && processor.getMapWorld().getCurrentDimension() == expected.dimension()
                        && Objects.equals(processor.getCurrentMWId(), expected.multiworld())
                        && processor.getLeafMapRegion(ticket.key().caveLayer(), ticket.key().x(), ticket.key().z(), false) == region) {
                    prepared = NativeMapReloadQueue.prepare(ticket, access(processor, expected, region, true));
                }
            } finally {
                Object result = prepared;
                Minecraft.getInstance().execute(() -> {
                    if (result != null) QUEUE.prepared(ticket, result);
                    else QUEUE.complete(ticket, false);
                    nativeTaskFinished();
                });
            }
        }
    }

    private static NativeMapReloadQueue.Access access(MapProcessor processor, NativeMapReloadQueue.Context context) {
        return access(processor, context, null, true);
    }
    static NativeMapReloadQueue.Access existingAccess(MapProcessor processor, NativeMapReloadQueue.Context context, MapRegion existing) {
        return access(processor, context, existing, false);
    }
    private static NativeMapReloadQueue.Access access(MapProcessor processor, NativeMapReloadQueue.Context context,
                                                     MapRegion existing, boolean requireCurrent) {
        MapSaveLoad saveLoad = processor.getMapSaveLoad();
        return new NativeMapReloadQueue.Access() {
            public void pauseRender() { processor.pushRenderPause(true, true); }
            public void resumeRender() { processor.popRenderPause(true, true); }
            public boolean mayLoadAnother() {
                var next = saveLoad.getNextToLoadByViewing();
                return next == null || next.shouldAllowAnotherRegionToLoad();
            }
            public NativeMapReloadQueue.Region find(NativeMapReloadQueue.Key key) {
                // Each exact surface/cave layer has its own region; an inactive cave layer is
                // still eligible for native loading while its owning dimension remains current.
                MapRegion region = existing != null ? existing : leafOnMain(processor, key);
                if (region == null || region.getDim() != context.dimension()
                        || !Objects.equals(region.getMwId(), context.multiworld())) return null;
                return new NativeMapReloadQueue.Region() {
                    public Object lock() { return region; }
                    public void pauseWriter() { region.pushWriterPause(); }
                    public void resumeWriter() { region.popWriterPause(); }
                    public void markTerrain() { region.setHasHadTerrain(); }
                    public boolean canReload() {
                        // isBeingWritten is a sticky "contains live terrain" state, not an IO lock.
                        // Stable state2 regions with either value must be eligible for disk reload.
                        byte state = region.getLoadState();
                        return context.sameSession(activeContext)
                                && (!requireCurrent || (context.sameMap(activeContext)
                                && processor.getMapWorld().getCurrentDimension() == context.dimension()
                                && Objects.equals(processor.getCurrentMWId(), context.multiworld())))
                                && (state == 0 || state == 2 || state == 4)
                                && !region.reloadHasBeenRequested() && !region.recacheHasBeenRequested()
                                && !region.isRefreshing() && !saveLoad.toCacheContains(region)
                                && !saveLoad.getToSave().contains(region)
                                && (!context.sameMap(activeContext) || saveLoad.getNextToLoadByViewing() == null
                                || saveLoad.getNextToLoadByViewing().shouldAllowAnotherRegionToLoad());
                    }
                    public byte loadState() { return region.getLoadState(); }
                    public boolean invalidateCache() {
                        File cache = region.getCacheFile();
                        if (cache == null) {
                            try {
                                cache = saveLoad.getCacheFile(region, key.caveLayer(), false, false);
                            } catch (IOException failure) {
                                LOG.warn("Motiquies native map cache path is not ready: {},{} layer={}", key.x(), key.z(), key.caveLayer(), failure);
                                return false;
                            }
                            region.setCacheFile(cache);
                        }
                        // The native invalidator handles cache names/queues and marks disk loading
                        // necessary; calling clear+requestLoad alone can select old .xwmc textures.
                        region.convertCacheToOutdated(saveLoad, "Motiquies server map source changed");
                        File outdated = region.getCacheFile();
                        boolean invalidated = region.shouldCache() && outdated != null
                                && outdated.getName().endsWith(".outdated");
                        if (!invalidated) LOG.warn("Motiquies native map cache invalidation deferred: {},{} layer={}", key.x(), key.z(), key.caveLayer());
                        return invalidated;
                    }
                    public void clearLoadedData() { region.clearRegion(processor); }
                    public boolean loadStarted() {
                        byte state = region.getLoadState();
                        return region.reloadHasBeenRequested() || state == 1 || state == 2;
                    }
                    public void load() {
                        requireMainThread();
                        RegionPipelineTracker.onReflectionLoadStart(key.x(), key.z(), key.caveLayer());
                        saveLoad.requestLoad(region, "Motiquies server map reload", true);
                        submitted("main disk load");
                    }
                    public void prioritize() { saveLoad.setNextToLoadByViewing(region); }
                    private void submitted(String action) {
                        RegionPipelineTracker.onReflectionLoadDone(key.x(), key.z(), key.caveLayer(), true);
                        LOG.info("Motiquies native map {} requested: {},{} layer={}", action, key.x(), key.z(), key.caveLayer());
                    }
                };
            }
        };
    }

    private static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        LocalMapObservations.logout();
        activeContext = null;
        NativeMapWriteBridge.cancelAll();
        NOTICES.clear();
        QUEUE.clear();
    }
}
