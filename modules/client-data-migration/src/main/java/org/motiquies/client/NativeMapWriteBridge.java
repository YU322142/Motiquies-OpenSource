package org.motiquies.client;

import com.mapsyncer.client.ClientSyncSession;
import com.mapsyncer.client.XaeroMapDataHandler;
import com.mapsyncer.network.payload.ChunkMapData;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xaero.map.MapProcessor;
import xaero.map.file.RegionDetection;
import xaero.map.region.MapRegion;
import xaero.map.task.MapRunnerTask;
import xaero.map.world.MapDimension;

/** Serializes MapSyncer's original source commit and native handoff, without changing its JAR. */
public final class NativeMapWriteBridge {
    private static final Logger LOG = LogManager.getLogger();
    private record Tag(NativeMapReloadQueue.Context context,String previousSourceHash){}
    private static final ConcurrentHashMap<ChunkMapData, Tag> TAGS = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<Write> PENDING = new ConcurrentLinkedQueue<>();
    private static final Set<Write> LIVE = ConcurrentHashMap.newKeySet();
    private NativeMapWriteBridge() {}

    /** The original submit is on the client thread, before a potentially delayed IO worker starts. */
    public static void capture(ChunkMapData chunk,com.mapsyncer.client.ClientTimestampCache cache) {
        var context = NativeMapReloadBridge.captureContext();
        var prior=cache==null?null:cache.getAll().get(XaeroMapDataHandler.buildRelativePathForCache(chunk));
        if (context != null) TAGS.put(chunk,new Tag(context,prior==null?null:prior.hash()));
    }

    /** Redirected only from the original dedicated IO lambda. Never waits on render/network. */
    public static XaeroMapDataHandler.RegionWriteResult write(ChunkMapData chunk, Path serverDir, int worldId,
                                                             boolean integratedHost, String selectedMultiworld) {
        if (!Thread.currentThread().getName().equals("mapsyncer-sync-io") || Minecraft.getInstance().isSameThread()) {
            throw new IllegalStateException("Native source commit may only wait on the original MapSyncer IO worker");
        }
        var tag = TAGS.remove(chunk);
        if (tag == null || !valid(tag.context())) return null;
        var context=tag.context();
        Write work = new Write(context, chunk, serverDir, worldId, integratedHost, selectedMultiworld,tag.previousSourceHash());
        LIVE.add(work);
        // Close the race where logout cancels LIVE just before this IO worker adds itself.
        if (!valid(context)) { work.request.cancel(); LIVE.remove(work); return null; }
        PENDING.add(work);
        try {
            return work.request.future().get(120, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            work.request.cancel();
            Thread.currentThread().interrupt();
            return null;
        } catch (TimeoutException timeout) {
            work.request.cancel();
            throw new IllegalStateException("Native map source commit did not become ready within 120 seconds", timeout);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("Native map source commit failed", cause);
        } finally {
            LIVE.remove(work);
        }
    }

    private static boolean valid(NativeMapReloadQueue.Context expected) {
        return expected.sameSession(NativeMapReloadBridge.activeContext())
                && ClientSyncSession.get().generation() == expected.generation();
    }

    static void cancelStale() {
        for (Write work : LIVE) if (!valid(work.context)) work.request.cancel();
        TAGS.entrySet().removeIf(entry -> !valid(entry.getValue().context()));
    }
    static void cancelAll() {
        for (Write work : LIVE) work.request.cancel();
        PENDING.clear();
        TAGS.clear();
    }
    static MapRunnerTask nextTask() {
        for (Write work; (work = PENDING.poll()) != null;) {
            if (work.request.future().isDone()) continue;
            if (!valid(work.context)) { work.request.cancel(); continue; }
            return new CommitTask(work);
        }
        return null;
    }

    private static final class Write {
        final NativeMapReloadQueue.Context context;
        final ChunkMapData chunk;
        final Path serverDir;
        final int worldId;
        final boolean integratedHost;
        final String selectedMultiworld;
        final String previousSourceHash;
        final NativeMapWriteRequest<XaeroMapDataHandler.RegionWriteResult> request = new NativeMapWriteRequest<>();
        NativeMapReloadQueue.Context retained;
        Object preparedIdentity;
        boolean coldWarning;
        Write(NativeMapReloadQueue.Context context, ChunkMapData chunk, Path serverDir, int worldId,
              boolean integratedHost, String selectedMultiworld,String previousSourceHash) {
            this.context = context; this.chunk = chunk; this.serverDir = serverDir; this.worldId = worldId;
            this.integratedHost = integratedHost; this.selectedMultiworld = selectedMultiworld;
            this.previousSourceHash=previousSourceHash;
        }
        NativeMapReloadQueue.Key key() { return new NativeMapReloadQueue.Key(chunk.regionX, chunk.regionZ, chunk.caveLayer); }
    }

    private static final class CommitTask extends MapRunnerTask {
        private final Write work;
        CommitTask(Write work) { this.work = work; }
        @Override
        public void run(MapProcessor processor) {
            boolean finished = false;
            try {
                finished = work.request.attempt(() -> processor != null && processor.getMapWorld() != null,
                        () -> (processor == null || processor == work.context.processor()) && valid(work.context), () -> {
                            CommitAccess access = new CommitAccess(processor, work);
                            var outcome = NativeMapCommit.attempt(access);
                            if (!outcome.deferred() && outcome.receipt() != null) {
                                LOG.info("Motiquies native source commit complete: {},{} layer={} handoff={}",
                                        work.chunk.regionX, work.chunk.regionZ, work.chunk.caveLayer, access.handling);
                            }
                            return outcome;
                        });
            } finally {
                boolean done = finished || work.request.future().isDone();
                Minecraft.getInstance().execute(() -> {
                    try {
                        if (!done && valid(work.context) && !work.request.future().isDone()) PENDING.add(work);
                        else if (!done) work.request.cancel();
                        if (done && work.retained != null && work.context.sameSession(NativeMapReloadBridge.activeContext())) {
                            NativeMapReloadBridge.retainCompleted(work.retained, work.key(), work.preparedIdentity);
                        }
                    } finally { NativeMapReloadBridge.nativeTaskFinished(); }
                });
            }
        }
    }

    private static final class CommitAccess implements NativeMapCommit.Access<XaeroMapDataHandler.RegionWriteResult> {
        private final MapProcessor processor;
        private final Write work;
        private final Path output;
        private MapRegion existing;
        private NativeMapReloadQueue.Context target;
        private NativeXaeroRegionCodec.Region memory;
        private RegionDetection coldDetection;
        private boolean hasTerrain;
        private String handling = "disk-only";
        CommitAccess(MapProcessor processor, Write work) {
            this.processor = processor;
            this.work = work;
            try {
                output = sourcePath();
            } catch (java.io.IOException failure) {
                throw new IllegalStateException("Invalid native map source path", failure);
            }
        }
        public void pauseRender() { processor.pushRenderPause(true, true); }
        public void resumeRender() { processor.popRenderPause(true, true); }
        public NativeMapReloadQueue.Region existingRegion() {
            MapDimension dimension = owningDimension();
            if (dimension == null) return null;
            var region = dimension.getLayeredMapRegions().getLeaf(work.chunk.caveLayer, work.chunk.regionX, work.chunk.regionZ);
            if (region == null || !region.isNormalMapData() || !ownsRegion(region)) return null;
            existing = region;
            target = new NativeMapReloadQueue.Context(work.context.connection(), processor, dimension, region.getMwId(), work.context.generation());
            return NativeMapReloadBridge.existingAccess(processor, target, region).find(work.key());
        }
        private MapDimension owningDimension() {
            Path dimensionPath = work.serverDir.resolve(work.chunk.dimension).toAbsolutePath().normalize();
            for (var dimension : processor.getMapWorld().getDimensionsList()) {
                Path folder = dimension.getMainFolderPath();
                if (folder != null && folder.toAbsolutePath().normalize().equals(dimensionPath)) return dimension;
            }
            return null;
        }
        private boolean ownsRegion(MapRegion region) {
            var save = processor.getMapSaveLoad();
            Path folder = save.getMWSubFolder(region.getWorldId(), region.getDimId(), region.getMwId());
            return folder != null && output.getParent().equals(save.getCaveLayerFolder(work.chunk.caveLayer, folder).toAbsolutePath().normalize());
        }
        public void captureMemory() {
            try {
                memory = NativeMapMemory.snapshot(processor.getMapSaveLoad(), existing);
            } catch (java.io.IOException failure) {
                throw new IllegalStateException("Could not snapshot existing native map tiles", failure);
            }
        }
        public XaeroMapDataHandler.RegionWriteResult commitSource() {
            if (!valid(work.context) || work.request.future().isDone()) return null;
            try {
                var source=NativeMapMerge.unzip(work.chunk.data);
                if(source.source()!=null)LocalMapObservations.epoch(output,source.source().epoch(),work.context.connection(),processor);
                byte[] disk=Files.exists(output)?Files.readAllBytes(output):null;
                var observed=LocalMapObservations.stamps(output,memory);
                var diskRegion=disk==null?new NativeXaeroRegionCodec.Region(new NativeXaeroRegionCodec.Tile[1024]):NativeMapMerge.unzip(disk).region();
                var saved=LocalMapObservations.readSafe(output,diskRegion);
                NativeMapMerge.proveUnchangedImport(disk,work.previousSourceHash,source.source(),saved);
                var merged=NativeMapMerge.merge(work.chunk.data,disk,memory,saved,observed);
                // Expensive preparation is outside the short ownership check. Cancellation
                // before this claim prevents staging and source mutation; an already started
                // filesystem replace is allowed to finish without issuing a stale receipt.
                if(!work.request.mayCommit(()->valid(work.context)))return null;
                var payload=new ChunkMapData(work.chunk.regionX,work.chunk.regionZ,work.chunk.dimension,merged.zip(),work.chunk.timestampSeconds,work.chunk.caveLayer);
                var receipt=work.request.finalCommit(()->valid(work.context),()->MapTileFreshness.commit(output,merged.stamps(),()->{
                    // A disconnect during journal IO can still cancel before the final writer.
                    if(!work.request.mayCommit(()->valid(work.context)))return null;
                    return XaeroMapDataHandler.writeChunkData(payload,work.serverDir,work.worldId,work.integratedHost,work.selectedMultiworld);
                },failure->LOG.warn("Map source committed; provenance finish deferred to content-bound journal recovery",failure)));
                if(receipt==null)return null;
                if(!receipt.outputFile().toAbsolutePath().normalize().equals(output))throw new java.io.IOException("Map merge source identity changed");
                hasTerrain = merged.retained() > 0 || merged.imported() > 0;
                LocalMapObservations.committed(output,observed);
                var crc=new java.util.zip.CRC32();crc.update(work.chunk.data);
                LOG.info("Motiquies per-chunk map merge: {},{} layer={} retained={} imported={} cleared={}",work.chunk.regionX,work.chunk.regionZ,work.chunk.caveLayer,merged.retained(),merged.imported(),merged.cleared());
                // Sync acknowledges the incoming publication, independently of local merged CRC.
                return new XaeroMapDataHandler.RegionWriteResult(receipt.mwDir(),receipt.outputFile(),String.format("%08x",crc.getValue()));
            } catch(java.io.IOException failure){
                throw new IllegalStateException("Native per-chunk merge failed; existing source preserved",failure);
            }
        }
        private Path sourcePath() throws java.io.IOException {
            if(!com.mapsyncer.util.DimensionPathMapping.isSafeXaeroFolder(work.chunk.dimension)
                    ||work.selectedMultiworld!=null&&!work.selectedMultiworld.isEmpty()&&!XaeroMapDataHandler.isSafeMultiworld(work.selectedMultiworld))
                throw new java.io.IOException("Unsafe source map identity");
            Path dim=work.serverDir.resolve(work.chunk.dimension),mw;
            if(work.selectedMultiworld!=null)mw=work.selectedMultiworld.isEmpty()?dim:dim.resolve(work.selectedMultiworld);
            else if(work.integratedHost)mw=dim;
            else {
                mw=null;
                if(java.nio.file.Files.isDirectory(dim))try(var paths=java.nio.file.Files.list(dim)){
                    mw=paths.filter(p->p.getFileName().toString().startsWith("mw$")&&java.nio.file.Files.isDirectory(p)).max((a,b)->{
                        try{return Long.compare(java.nio.file.Files.getLastModifiedTime(a).toMillis(),java.nio.file.Files.getLastModifiedTime(b).toMillis());}
                        catch(java.io.IOException e){return 0;}
                    }).orElse(null);
                }
                if(mw==null)mw=dim.resolve("mw$"+work.worldId);
            }
            return processor.getMapSaveLoad().getCaveLayerFolder(work.chunk.caveLayer,mw)
                    .resolve(work.chunk.regionX+"_"+work.chunk.regionZ+".zip").toAbsolutePath().normalize();
        }
        public boolean invalidateColdCache(XaeroMapDataHandler.RegionWriteResult receipt){
            try{
                String world=processor.getCurrentWorldId();
                Path dimensionPath=work.serverDir.resolve(work.chunk.dimension).toAbsolutePath().normalize();
                Path actual=receipt.mwDir().toAbsolutePath().normalize();
                String multiworld=actual.equals(dimensionPath)?"":actual.getFileName().toString();
                Path expected=processor.getMapSaveLoad().getMWSubFolder(world,work.chunk.dimension,multiworld);
                if(expected==null||!expected.toAbsolutePath().normalize().equals(actual))throw new java.io.IOException("Native cold map identity mismatch");
                coldDetection = new RegionDetection(world, work.chunk.dimension, multiworld, work.chunk.regionX,
                        work.chunk.regionZ, receipt.outputFile().toFile(), processor.getGlobalVersion(), hasTerrain);
                java.io.File cache=processor.getMapSaveLoad().getCacheFile(coldDetection,work.chunk.caveLayer,false,false);
                if(cache==null)throw new java.io.IOException("Native cold cache path unavailable");
                ColdMapCache.invalidate(receipt.outputFile(),cache.toPath(),work.chunk.regionX,work.chunk.regionZ);
                handling="disk-only-cold-cache-invalidated";
                return true;
            }catch(java.io.IOException|RuntimeException failure){
                if(!work.coldWarning){work.coldWarning=true;LOG.warn("Cold map texture invalidation deferred; source receipt not acknowledged",failure);}
                return false;
            }
        }
        public void publishColdRegion(XaeroMapDataHandler.RegionWriteResult receipt) {
            MapDimension dimension = owningDimension();
            if (dimension == null || dimension.isUsingWorldSave()
                    || !Objects.equals(dimension.getCurrentMultiworld(), coldDetection.getMwId())) return;
            var regions = dimension.getLayeredMapRegions();
            var region = regions.getLeaf(work.chunk.caveLayer, work.chunk.regionX, work.chunk.regionZ);
            if (region != null) {
                // Rendering can create an unloaded leaf while the native IO task is busy.
                // Its queued native load will run after this task and can use the committed source.
                if (region.isNormalMapData() && ownsRegion(region)) synchronized (region) {
                    region.setRegionFile(receipt.outputFile().toFile());
                    region.setSaveExists(null);
                    region.setCacheFile(null);
                    region.setLookedForCache(false);
                    region.setShouldCache(true, "Motiquies server map source changed");
                    if (hasTerrain) region.setHasHadTerrain();
                }
            } else {
                var layer = regions.getLayer(work.chunk.caveLayer);
                if (hasTerrain) {
                    coldDetection.setShouldCache(true, "Motiquies server map source changed");
                    layer.addRegionDetection(coldDetection);
                    handling = "cold-region-detected";
                } else {
                    layer.removeRegionDetection(work.chunk.regionX, work.chunk.regionZ);
                }
            }
        }
        public void retainDirtyRegion(XaeroMapDataHandler.RegionWriteResult receipt) {
            existing.setRegionFile(receipt.outputFile().toFile());
            existing.setSaveExists(null);
            boolean current = target.sameMap(NativeMapReloadBridge.activeContext())
                    && processor.getMapWorld().getCurrentDimension() == target.dimension()
                    && Objects.equals(processor.getCurrentMWId(), target.multiworld());
            handling = current ? "active-cleared-main-handoff" : "inactive-clear-retain";
            work.retained = target;
            work.preparedIdentity = existing;
        }
    }
}
