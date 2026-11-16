"""Pinned source transformations applied after the existing compatibility overlay."""
from pathlib import Path

SAVED_REGION_SNAPSHOT_FACTORY='''
    /** Successful-save routing uses metadata only, including dimensions without a region directory. */
    public static IncrementalScanSnapshot buildSavedRegionSnapshot(ServerLevel level) {
        MinecraftServer server=level.getServer();
        ResourceKey<Level> key=level.dimension();
        String fullDimId=key.location().toString();
        String dimPath=com.mapsyncer.util.DimensionApiHelper.getDimId(key);
        String xaeroDimName=DimensionPathMapping.getInstance().toXaeroDimension(fullDimId);
        DimensionScanConfig scanConfig=PlatformManager.getPlatform().getConfigForDimension(dimPath);
        DimensionTypeInfo dimTypeInfo=DimensionTypeHelper.fromDimensionType(level.dimensionType());
        List<RegionScanPass> passes=RegionGenerationPlanner.plan(scanConfig,dimTypeInfo);
        Path regionDir=net.minecraft.world.level.dimension.DimensionType.getStorageFolder(key,server.getWorldPath(LevelResource.ROOT))
                .resolve("region").toAbsolutePath().normalize();
        return new IncrementalScanSnapshot(dimPath,xaeroDimName,regionDir,getDimensionCacheDir(xaeroDimName),dimTypeInfo,passes);
    }
'''

def replace(source, old, new):
    if source.count(old) != 1:
        raise ValueError('Policy patch expected one anchor: '+old[:120])
    return source.replace(old,new,1)

def function(source, signature, replacement):
    start=source.index(signature)
    brace=source.index('{',start); depth=1; end=brace+1
    while depth:
        if source[end]=='{': depth+=1
        elif source[end]=='}': depth-=1
        end+=1
    return source[:start]+replacement+source[end:]

def apply_policy(handlers, upstream: Path):
    # This is a server overlay; preserve upstream client bytecode in its original form.
    for name in ('XaeroMapIntegrator','ClientHashManager','XaeroMapDataHandler'):
        handlers.pop(name,None)
    handlers['NeoForgeNetworkHandler']=replace(handlers['NeoForgeNetworkHandler'],
        '        PayloadRegistrar registrar = event.registrar("1").optional();',
        '        com.mapsyncer.server.DownloadController.register();\n        com.mapsyncer.server.NativeDirtyMapUpdates.register();\n        PayloadRegistrar registrar = event.registrar("1").optional();')
    handlers['NeoForgeNetworkHandler']=replace(handlers['NeoForgeNetworkHandler'],
        '        PayloadRegistrar registrar = event.registrar("1").optional();',
        '''        PayloadRegistrar registrar = event.registrar("1").optional();
        registrar.playToClient(com.mapsyncer.network.payload.MotiquiesMapClockPayload.TYPE,
                com.mapsyncer.network.payload.MotiquiesMapClockPayload.STREAM_CODEC,(payload,context)->{});
        registrar.playToClient(com.mapsyncer.network.payload.MotiquiesMapUpdatedPayload.TYPE,
                com.mapsyncer.network.payload.MotiquiesMapUpdatedPayload.STREAM_CODEC,(payload,context)->{});
        registrar.playToServer(com.mapsyncer.network.payload.MotiquiesDownloadControlPayload.TYPE,
                com.mapsyncer.network.payload.MotiquiesDownloadControlPayload.STREAM_CODEC,(payload,context)->{
                    context.enqueueWork(()->{
                        if(context.player() instanceof net.minecraft.server.level.ServerPlayer player)
                            com.mapsyncer.server.DownloadController.control(player,payload.action(),payload.radius());
                    });
                });''')
    handlers['NeoForgeNetworkHandler']=replace(handlers['NeoForgeNetworkHandler'],
        '    public void sendToPlayer(ServerPlayer player, SyncProgressPayload payload) {',
        '''    public void sendToPlayer(ServerPlayer player, SyncProgressPayload payload) {
        if (!player.connection.hasChannel(NeoForgePayloadAdapters.NeoForgeSyncProgressPayload.TYPE)) return;''')
    src=handlers['ServerSyncHandlerLogic']
    src=replace(src,'        return activeSyncCount.get() > 0;',
        '        return DownloadGenerationGate.INSTANCE.blocking() > 0;')
    src=replace(src,'    public static void onPlayerDisconnect(UUID playerId) {',
        '    public static void onPlayerDisconnect(UUID playerId) {\n        DownloadController.cancel(playerId);')
    src=function(src,'    private static boolean applySpeedLimit(','''    private static boolean applySpeedLimit(int bytesSent, MinecraftServer server, UUID playerId,
            ResourceKey<Level> startDimension, int syncVersion) {
        DownloadController.Context policy = DownloadController.context();
        return policy != null && policy.pace(bytesSent)
                && isPlayerStillValid(server,playerId,startDimension,syncVersion);
    }''')
    # Small, paced batches bound reservations, cancellation latency, and queued main-thread work.
    src=function(src,'    private static int getBatchThreshold()',
        '    private static int getBatchThreshold() { return 28_000; }')
    src=replace(src,'        UUID playerId = serverPlayer.getUUID();', '''        UUID playerId = serverPlayer.getUUID();
        if (DownloadController.paused(serverPlayer)) {
            NetworkManager.sendToPlayer(serverPlayer,new SyncProgressPayload(0,0,"aborted_paused"));
            return;
        }''')
    src=replace(src,'        ServerSyncSession.assignVersion(playerId, syncVersion);', '''        ServerSyncSession.assignVersion(playerId, syncVersion);
        final DownloadController.Context download;
        try {
            download = DownloadController.prepare(serverPlayer);
            if (download == null) {
                NetworkManager.sendToPlayer(serverPlayer,new SyncProgressPayload(0,0,"aborted_paused"));
                onPlayerDisconnect(playerId); return;
            }
        } catch (IOException failure) {
            LOGGER.warn("Cannot initialize download policy",failure);
            NetworkManager.sendToPlayer(serverPlayer,new SyncProgressPayload(0,0,"aborted:server_error"));
            onPlayerDisconnect(playerId); return;
        }''')
    src=replace(src,'                PlatformManager.getPlatform().getSyncRadiusRegions(), startBlockX, startBlockZ,',
        '                0, startBlockX, startBlockZ,')
    src=replace(src,'''            try {
                if (!awaitGenerationIdle(server, playerId, syncVersion)) {''','''            try {
                if (!download.enter()) {
                    finalizePlayerSync(server,playerId,syncVersion); return;
                }
                if (!awaitGenerationIdle(server, playerId, syncVersion)) {''')
    src=replace(src,'''                if (!awaitGenerationIdle(server, playerId, syncVersion)) {
                    finalizePlayerSync(server, playerId, syncVersion);''','''                if (!awaitGenerationIdle(server, playerId, syncVersion)) {
                    notifySyncAborted(server,playerId,syncVersion,"generation_unavailable");
                    finalizePlayerSync(server, playerId, syncVersion);''')
    src=replace(src,'                processSyncAsync(server, playerId, clientMeta, syncAll, targetDimension,',
        '                download.capture(syncVersion);\n                processSyncAsync(server, playerId, clientMeta, syncAll, targetDimension,')
    src=replace(src,'            } catch (RuntimeException e) {\n                LOGGER.error("Unhandled error while processing sync for player {}", playerId, e);',
        '            } catch (IOException | InterruptedException | RuntimeException e) {\n                if (e instanceof InterruptedException) Thread.currentThread().interrupt();\n                LOGGER.error("Unhandled error while processing sync for player {}", playerId, e);')
    src=replace(src,'                activeSyncCount.updateAndGet(value -> Math.max(0, value - 1));',
        '                download.close();\n                activeSyncCount.updateAndGet(value -> Math.max(0, value - 1));')
    # A timed-out converter must never be treated as an idle and coherent snapshot.
    src=replace(src,'''                    return true;
                }
                Thread.sleep(500L);''','''                    return false;
                }
                Thread.sleep(500L);''')
    src=replace(src,'                            + "continuing with current cache snapshot", playerId);',
        '                            + "aborting to keep the cache snapshot coherent", playerId);')
    src=replace(src,'        final boolean published = server.isDedicatedServer() && PublishedMapCache.isEnabled();',
        '        final Path pinnedRoot = DownloadController.snapshotRoot();\n        final boolean published = pinnedRoot != null || (server.isDedicatedServer() && PublishedMapCache.isEnabled());')
    src=replace(src,'                ? new ServerMapCache.Snapshot(PublishedMapCache.currentRoot(), false, Map.of())',
        '                ? new ServerMapCache.Snapshot(pinnedRoot != null ? pinnedRoot : PublishedMapCache.currentRoot(), false, Map.of())')
    src=replace(src,'        Map<String, TimestampHashEntry> serverCache = genCache == null ? Map.of() : genCache.getAll();',
        '        Map<String, TimestampHashEntry> serverCache = genCache == null ? Map.of() : Map.copyOf(genCache.getAll());')
    # Dedicated downloads never write/prune shared native or published generation metadata.
    src=replace(src,'        Path cacheDir = cacheLayout.root();',
        '        Path cacheDir = cacheLayout.root();\n        final boolean readOnly = published || server.isDedicatedServer();')
    src=src.replace('&& !published','&& !readOnly')
    # Nearby native reads can recover missing metadata without modifying the shared cache.
    src=replace(src,'                                    timestamp = System.currentTimeMillis() / 1000;',
        '                                    timestamp = Files.getLastModifiedTime(zipPath).toMillis() / 1000;')
    src=replace(src,'                        if (candidate == null || !radiusPolicy.allows(candidate.dimension(), candidate.regionX(), candidate.regionZ())) {',
        '''                        if (candidate == null || !DownloadController.context().allows(candidate.dimension(), candidate.regionX(), candidate.regionZ())
                                || !radiusPolicy.allows(candidate.dimension(), candidate.regionX(), candidate.regionZ())) {''')
    src=replace(src,'        allZipPaths.forEach(zipPath -> {',
        '        allZipPaths.forEach(zipPath -> {\n                        if (!DownloadController.context().scanReady()) return;')
    src=replace(src,'        sortByViewDistancePriority(regionsToSync, startBlockX, startBlockZ, viewDistanceRegions);',
        '''        regionsToSync.sort(DownloadPolicy.priority(DownloadController.context().dimension,
                startBlockX >> 9,startBlockZ >> 9));''')
    src=replace(src,'        int total = regionsToSync.size();', '''        long scheduledBytes=0;boolean estimateKnown=true;
        for (RegionSyncInfo info:regionsToSync) {
            try { scheduledBytes=Math.addExact(scheduledBytes,Files.size(info.zipPath())); }
            catch(IOException failure){estimateKnown=false;LOGGER.warn("Cannot estimate map region size {}",info.zipPath(),failure);}
        }
        if(estimateKnown)DownloadController.context().total(scheduledBytes);
        int total = regionsToSync.size();''')
    src=replace(src,'int regionX, int regionZ, String dimension, int caveLayer) {',
        'int regionX, int regionZ, String dimension, int caveLayer, String expectedHash) implements DownloadPolicy.Region {')
    src=replace(src,'new RegionSyncInfo(zipPath, normalizedPath, timestampSeconds, regionX, regionZ, dimension, caveLayer)',
        'new RegionSyncInfo(zipPath, normalizedPath, timestampSeconds, regionX, regionZ, dimension, caveLayer, null)')
    src=replace(src,'                                regionsToSync.add(info);',
        '''                                regionsToSync.add(new RegionSyncInfo(info.zipPath(),info.normalizedPath(),info.timestampSeconds(),
                                        info.regionX(),info.regionZ(),info.dimension(),info.caveLayer(),serverHash));''')
    src=replace(src,'            byte[] data = Files.readAllBytes(info.zipPath());',
        '            byte[] data = DownloadSnapshot.readVerified(info.zipPath(),info.expectedHash());')
    handlers['ServerSyncHandlerLogic']=src
    # Atomically interlock all native generation entrypoints with snapshot/nearby leases.
    path=upstream/'libs/mc-1.21/src/main/java/com/mapsyncer/server/ConversionOrchestrator.java'
    src=path.read_text(encoding='utf-8')
    anchor='isRunning.compareAndSet(false, true)'
    if src.count(anchor)!=5: raise ValueError('Generation entrypoint layout changed')
    src=src.replace(anchor,'DownloadGenerationGate.INSTANCE.tryBegin(isRunning)')
    src=function(src,'    private static void waitForCompletion(','''    private static void waitForCompletion(List<java.util.concurrent.Future<?>> futures, String taskName) {
        boolean interrupted = false;
        for (java.util.concurrent.Future<?> future : futures) {
            boolean done = false;
            while (!done) {
                try {
                    future.get(TimeoutConfig.TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS); done = true;
                } catch (java.util.concurrent.TimeoutException failure) {
                    // The worker can still write. Keep generation active until it really terminates.
                    LOGGER.warn("{} task timeout; keeping generation fence active",taskName);
                } catch (ExecutionException | java.util.concurrent.CancellationException failure) {
                    LOGGER.error("{} task failed",taskName,failure); done = true;
                } catch (InterruptedException failure) {
                    interrupted = true; // Drain outstanding workers before exposing an idle cache.
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }''')
    dirty_method='''
    public enum DirtyResult { COMPLETE, UNCHANGED, DEFERRED, FAILED }

    /** A stable native-IO MCA input for exactly one saved region. No world save/full scan. */
    public static DirtyResult generateDirtyRegion(IncrementalScanSnapshot snapshot,int regionX,int regionZ) {
        if (!DownloadGenerationGate.INSTANCE.tryBegin(isRunning)) return DirtyResult.DEFERRED;
        boolean sourceReady=false;
        try {
            McaTimestampCache mcaCache=getTimestampCache();
            DirtyResult result;
            try (var source=SourceFreshness.open(snapshot.regionDir(),regionX,regionZ,getCacheDir())) {
            sourceReady=true;
            GenerationCache genCache=GenerationCache.getInstance(getCacheDir());
            java.util.Map<String,String> previousHashes=new java.util.HashMap<>();
            java.util.Map<String,com.mapsyncer.util.PropertiesCacheIO.TimestampHashEntry> previousMeta=new java.util.HashMap<>();
            java.util.List<EmptyLayerPublication.Layer> publicationLayers=new java.util.ArrayList<>();
            for (RegionScanPass pass:snapshot.passes()) {
                String key=ConversionOutputPaths.relativePath(snapshot.xaeroDimName(),pass.caveLayer(),regionX,regionZ);
                var meta=genCache.getMeta(key);previousHashes.put(key,meta==null?null:meta.hash());
                if(meta!=null)previousMeta.put(key,meta);
                publicationLayers.add(new EmptyLayerPublication.Layer(key,ConversionOutputPaths.outputDir(snapshot.baseOutputDir(),pass.caveLayer()),regionX,regionZ,snapshot.dimTypeInfo().minY(),pass.lightMode(),pass.caveParams()));
            }
            for (RegionScanPass pass:snapshot.passes())
                Files.createDirectories(ConversionOutputPaths.outputDir(snapshot.baseOutputDir(),pass.caveLayer()));
            ConcurrentLinkedQueue<RegionCoords> failed=new ConcurrentLinkedQueue<>();
            totalCount=snapshot.passes().size();processedCount=0;currentStatus="saved-region update";
            long generationTime=System.currentTimeMillis()/1000;
            convertRegionMultiPasses(new RegionCoords(regionX,regionZ),snapshot.regionDir(),snapshot.baseOutputDir(),
                    snapshot.xaeroDimName(),snapshot.dimPath(),snapshot.dimTypeInfo(),snapshot.passes(),
                    mcaCache,genCache,generationTime,failed,false,"Saved region");
            EmptyLayerPublication.completeRemoved(publicationLayers,previousMeta,genCache,generationTime,!failed.isEmpty());
            // Even partial native writes require paired CRC metadata. A failed pass remains queued.
            if(!failed.isEmpty())reconcileFreshnessOutputs(snapshot.baseOutputDir(),snapshot.xaeroDimName(),regionX,regionZ,snapshot.passes(),genCache,generationTime);
            java.util.Map<String,String> values=new java.util.HashMap<>();
            genCache.getAll().forEach((key,value)->values.put(key,value.format()));
            DirtyMapMetadata.write(getCacheDir().resolve("generation_cache.properties"),values);
            boolean changed=previousHashes.entrySet().stream().anyMatch(entry->{
                var after=genCache.getMeta(entry.getKey());
                return !java.util.Objects.equals(entry.getValue(),after==null?null:after.hash());
            });
            result=!failed.isEmpty()?DirtyResult.FAILED:changed?DirtyResult.COMPLETE:DirtyResult.UNCHANGED;
            } // Outermost scope completes the full input digest before acknowledging the source.
            if(result!=DirtyResult.FAILED){
                mcaCache.updateTimestamp(snapshot.dimPath(),regionX,regionZ,snapshot.regionDir().resolve("r."+regionX+"."+regionZ+".mca"));
                mcaCache.saveCache();processedCount=totalCount;
            }
            return result;
        } catch (IOException | RuntimeException failure) {
            if(sourceReady)reconcileFreshnessOutputs(snapshot.baseOutputDir(),snapshot.xaeroDimName(),regionX,regionZ,snapshot.passes(),GenerationCache.getInstance(getCacheDir()),System.currentTimeMillis()/1000);
            LOGGER.error("Saved-region map update failed; pending work retained",failure);
            return DirtyResult.FAILED;
        } finally { isRunning.set(false);currentStatus="completed"; }
    }
'''
    pos=src.rfind('}');src=src[:pos]+SAVED_REGION_SNAPSHOT_FACTORY+dirty_method+src[pos:]
    # Every eligible full/incremental conversion has a checked frozen input scope.
    src=src.replace("    private static void convertRegionMultiPasses(","    private static void convertRegionMultiPassesNative(",1)
    # Normal live full/single conversion is deliberately deferred by SourceFreshness.open.
    # Offline operators must declare a frozen input root; dirty jobs already own a native snapshot.
    src=replace(src,'        SingleRegionResult result = SingleRegionResult.SUCCESS;\n        try {',
        '        SingleRegionResult result = SingleRegionResult.SUCCESS;\n        boolean sourceReady=false;\n        try (var source=SourceFreshness.open(regionDir,regionX,regionZ,getCacheDir())) {\n            sourceReady=true;')
    src=replace(src,'        finally {\n            isRunning.set(false);\n            currentStatus = "completed";\n        }\n        return result;',
        '        finally {\n            if(sourceReady)reconcileFreshnessOutputs(baseOutputDir,xaeroDimName,regionX,regionZ,passes,GenerationCache.getInstance(getCacheDir()),System.currentTimeMillis()/1000);\n            isRunning.set(false);\n            currentStatus = "completed";\n        }\n        return result;')
    src=replace(src,'            ConcurrentLinkedQueue<RegionCoords> failedRegions, boolean logProgress, String logPrefix) {',
        '            ConcurrentLinkedQueue<RegionCoords> failedRegions, boolean logProgress, String logPrefix) throws IOException {')
    empty_purge='                EmptyRegionSupport.purgeGeneratedArtifacts('
    if src.count(empty_purge)!=2:
        raise ValueError('Expected both native empty-region/layer purge branches')
    src=src.replace(empty_purge,
        '                SourceFreshness.remember(outputDir.resolve(coords.x()+"_"+coords.z()+".zip"));\n'+empty_purge)
    src=replace(src,'            mcaCache.updateTimestampValue(dimPath, coords.x(), coords.z(), sourceTimestamp);',
        '            // Source timestamp is acknowledged by the wrapper only after scope close validation.')
    wrapper='''
    private static void convertRegionMultiPasses(
            RegionCoords coords, Path regionDir, Path baseOutputDir, String xaeroDimName, String dimPath,
            DimensionTypeInfo dimTypeInfo, List<RegionScanPass> passes,
            McaTimestampCache mcaCache, GenerationCache genCache, long generationTimeSeconds,
            ConcurrentLinkedQueue<RegionCoords> failedRegions, boolean logProgress, String logPrefix) {
        boolean sourceReady=false,nested=SourceFreshness.current()!=null;
        try (var source=SourceFreshness.open(regionDir,coords.x(),coords.z(),getCacheDir())) {
            sourceReady=true;
            convertRegionMultiPassesNative(coords,regionDir,baseOutputDir,xaeroDimName,dimPath,dimTypeInfo,passes,mcaCache,genCache,generationTimeSeconds,failedRegions,logProgress,logPrefix);
        } catch (IOException | RuntimeException failure) {
            failedRegions.add(coords);LOGGER.error("Source freshness unavailable; region remains pending",failure);
            if(sourceReady)reconcileFreshnessOutputs(baseOutputDir,xaeroDimName,coords.x(),coords.z(),passes,genCache,generationTimeSeconds);
        }
        if(!nested&&!failedRegions.contains(coords)){
            mcaCache.updateTimestamp(dimPath,coords.x(),coords.z(),regionDir.resolve("r."+coords.x()+"."+coords.z()+".mca"));
        }
    }
    /** A failed scope is never ACKed; already atomic layer files keep matching CRC metadata. */
    private static void reconcileFreshnessOutputs(Path baseOutputDir,String dimension,int rx,int rz,
            List<RegionScanPass> passes,GenerationCache cache,long timestamp) {
        try{
            for(RegionScanPass pass:passes){
                Path file=ConversionOutputPaths.outputDir(baseOutputDir,pass.caveLayer()).resolve(rx+"_"+rz+".zip");
                String key=ConversionOutputPaths.relativePath(dimension,pass.caveLayer(),rx,rz);
                if(Files.isRegularFile(file,java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    cache.update(key,timestamp,com.mapsyncer.util.HashUtils.computeFileHash(file));
                // Missing layer metadata is retained as a pending tombstone retry identity.
                // It is never sent without a valid ZIP; the failed region stays in the journal.
            }
            java.util.Map<String,String> values=new java.util.HashMap<>();
            cache.getAll().forEach((key,value)->values.put(key,value.format()));
            DirtyMapMetadata.write(getCacheDir().resolve("generation_cache.properties"),values);
        }catch(IOException|RuntimeException failure){LOGGER.error("Partial map publication metadata repair failed; region remains pending",failure);}
    }
'''
    pos=src.rfind('}');src=src[:pos]+wrapper+src[pos:]
    handlers['ConversionOrchestrator']=src
    writer=(upstream/'libs/common/src/main/java/com/mapsyncer/server/XaeroWriter.java').read_text(encoding='utf-8')
    writer=replace(writer,'                ZipEntry entry = new ZipEntry("region.xaero");',
        '                ZipEntry entry = new ZipEntry("region.xaero");\n                entry.setTime(0L); // Content identity must not depend on conversion wall-clock time.')
    writer=replace(writer,'                zos.closeEntry();','''                zos.closeEntry();
                var source=SourceFreshness.forPublication(finalFile,region.xaeroData());
                if(source!=null){
                    ZipEntry provenance=new ZipEntry(org.motiquies.map.MapSourceTimes.ENTRY);provenance.setTime(0L);
                    zos.putNextEntry(provenance);zos.write(source.encode());zos.closeEntry();
                }''')
    handlers['XaeroWriter']=writer
    src=(upstream/'libs/common/src/main/java/com/mapsyncer/server/IncrementalUpdateHandlerLogic.java').read_text(encoding='utf-8')
    src=replace(src,'        if (!running || server == null) return;',
        '        if (!running || server == null) return;\n        if (NativeDirtyMapUpdates.owns(server)) return;')
    handlers['IncrementalUpdateHandlerLogic']=src
    handlers['GenerationCache']=function(handlers['GenerationCache'],'    private void trimIfOverLimit()',
        '''    private void trimIfOverLimit() {
        // Server publication baselines exceed the upstream 50,000-entry client-oriented bound.
        // Dropping metadata breaks CRC/version pairing and makes pinned snapshots incomplete.
    }''')
