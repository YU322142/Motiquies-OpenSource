package com.mapsyncer.server;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.Pattern;

/** Opt-in dedicated-server hybrid map worker. All conversion/scan/journal IO runs off the tick thread. */
public final class NativeDirtyMapUpdates {
 private static final Logger LOG=LoggerFactory.getLogger(NativeDirtyMapUpdates.class);
 private static final AtomicBoolean REGISTERED=new AtomicBoolean();
 private static final Pattern MCA=Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
 private static volatile State current;
 private record Context(String dimension,ServerLevel level,ConversionOrchestrator.IncrementalScanSnapshot scan,RegionStorageInfo storage){}
 public static void register(){
  if(!REGISTERED.compareAndSet(false,true))return;
  NeoForge.EVENT_BUS.addListener(NativeDirtyMapUpdates::started);
  NeoForge.EVENT_BUS.addListener(NativeDirtyMapUpdates::tick);
  NeoForge.EVENT_BUS.addListener(NativeDirtyMapUpdates::stopping);
  NeoForge.EVENT_BUS.addListener(NativeDirtyMapUpdates::stopped);
  NeoForge.EVENT_BUS.addListener(NativeDirtyMapUpdates::loaded);
  NeoForge.EVENT_BUS.addListener(NativeDirtyMapUpdates::unloaded);
 }
 private static boolean enabled(MinecraftServer server){return server.isDedicatedServer()&&Boolean.getBoolean("motiquies.mapsync.dirty")&&!PublishedMapCache.isEnabled()&&!PublishedMapCache.isConverter();}
 public static boolean owns(MinecraftServer server){State s=current;return s!=null&&s.server==server&&!s.failed;}
 /** Runs inside the native storage lock: path lookup, integer arithmetic, memory queue only. */
 public static void saved(Path path,ChunkPos pos){
  State s=current;if(s==null||!s.accepting)return;PostSaveRegions router=s.router;
  if(router!=null&&router.saved(path,pos.x,pos.z,now()))s.commits.incrementAndGet();
 }
 private static long now(){return System.nanoTime()/1_000_000L;}
 private static void started(ServerStartedEvent event){
  MinecraftServer server=event.getServer();if(!enabled(server))return;
  State state=new State(server);
  for(ServerLevel level:server.getAllLevels())state.register(level);
  current=state;state.executor.execute(state::initialize);
 }
 private static void loaded(LevelEvent.Load event){
  if(!(event.getLevel() instanceof ServerLevel level))return;State s=current;
  if(s!=null&&s.server==level.getServer()&&s.accepting)s.register(level);
 }
 private static void unloaded(LevelEvent.Unload event){
  if(!(event.getLevel() instanceof ServerLevel level))return;State s=current;
  if(s!=null&&s.server==level.getServer())s.lifecycle.unload(level.dimension().location().toString(),level);
 }
 private static void tick(ServerTickEvent.Post event){State s=current;if(s!=null&&s.server==event.getServer()){
  s.budget.sample(s.server.getAverageTickTimeNanos()/1_000_000.0);
  if(s.sourceEpoch!=null&&s.server.getTickCount()%5==0)for(var player:s.server.getPlayerList().getPlayers()){
   if(!player.connection.hasChannel(com.mapsyncer.network.payload.MotiquiesMapClockPayload.TYPE))continue;
   try{net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,new com.mapsyncer.network.payload.MotiquiesMapClockPayload(s.sourceEpoch,player.serverLevel().dimension().location().toString(),player.serverLevel().getGameTime(),5));}
   catch(RuntimeException failure){LOG.debug("Optional map clock unavailable; client observations remain unknown",failure);}
  }
 }}
 private static void stopping(ServerStoppingEvent event){State s=current;if(s!=null&&s.server==event.getServer()){s.running=false;s.lifecycle.stopReads();}}
 private static void stopped(ServerStoppedEvent event){
  State s=current;if(s==null||s.server!=event.getServer())return;
  s.running=false;s.accepting=false;s.lifecycle.stopReads();current=null;
  // Native chunk storage has closed by ServerStopped. Retain all final shutdown writes.
  s.executor.shutdownNow();
  Thread finalizer=new Thread(()->{
   try{if(!s.executor.awaitTermination(30,TimeUnit.SECONDS)){LOG.error("Saved-region worker still active; pending journal retained");return;}s.queue.close();if(s.journalReady)s.persist();else LOG.warn("Pending journal recovery not completed; old journal retained for startup reconciliation");}
   catch(Exception failure){LOG.error("Final saved-region journal failed; startup metadata reconciliation required",failure);}
  },"motiquies-map-journal-close");finalizer.setDaemon(false);finalizer.start();
 }
 private static final class State {
  final MinecraftServer server;final DirtyUpdateBudget budget=new DirtyUpdateBudget();
  final DirtyRegionQueue queue=new DirtyRegionQueue(65536,3000,30000,5000);
  final PostSaveRegions.Lifecycle<Context> lifecycle=new PostSaveRegions.Lifecycle<>(queue);
  final PostSaveRegions router=lifecycle.router();
  final ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"motiquies-saved-map-regions");t.setDaemon(true);t.setPriority(Thread.MIN_PRIORITY);return t;});
  final Path cache=ConversionOrchestrator.getCacheDir().toAbsolutePath().normalize(),journal=cache.resolve("motiquies-dirty-regions.bin"),inputs=cache.resolve("motiquies-dirty-inputs");
  final AtomicLong commits=new AtomicLong();
  volatile boolean running=true,accepting=true,failed,journalReady;volatile String sourceEpoch;
  long persisted=-1,lastJournal,lastWarning;int completed;
  State(MinecraftServer server){this.server=server;}
  void register(ServerLevel level){
   if(!accepting)return;
   var scan=ConversionOrchestrator.buildSavedRegionSnapshot(level);String dimension=level.dimension().location().toString();
   lifecycle.register(dimension,level,scan.regionDir(),new Context(dimension,level,scan,new RegionStorageInfo("motiquies-private-map-input",level.dimension(),"chunk")));
  }
  void scheduleReconcile(PostSaveRegions.Registration<Context> registration){
   if(!running||!accepting)return;
   try{executor.execute(()->{reconcile(registration);try{persist();}catch(IOException failure){LOG.error("Dynamic dimension journal failed; pending work retained",failure);}});}
   catch(RejectedExecutionException stopped){/* Shutdown owns persistence; keep the load callback memory-only. */}
  }
  void initialize(){
   try{
    Files.createDirectories(cache);
    sourceEpoch=SourceFreshness.epoch(cache); // Background IO only; tick thread reads the cached identity.
    int replayed=0;
    try{var recovered=DirtyRegionQueue.load(journal,65536,3000,30000,5000,now());replayed=recovered.replayInto(queue,now());}
    catch(IOException corrupt){
     if(Files.exists(journal))Files.copy(journal,cache.resolve("motiquies-dirty-regions.corrupt-"+UUID.randomUUID()+".bin"));
     LOG.error("Unreadable pending map journal retained; reconciling native MCA timestamps",corrupt);
    }
    journalReady=true;int reconciled=0;
    for(var registration:lifecycle.startReconciliation(this::scheduleReconcile))reconciled+=reconcile(registration);
    persist();LOG.info("Saved-region hybrid ready: dimensions={}, pending={}, reconciled={}, replayed={}, commits={}",lifecycle.size(),queue.size(),reconciled,replayed,commits.get());
    if(running)executor.scheduleWithFixedDelay(this::step,500,500,TimeUnit.MILLISECONDS);
   }catch(Exception failure){failed=true;LOG.error("Saved-region hybrid unavailable; legacy update fallback retained",failure);}
  }
  int reconcile(PostSaveRegions.Registration<Context> registration){
   if(!running||!lifecycle.current(registration))return 0;Context c=registration.value();int count=0;
   try{
    McaTimestampCache times=McaTimestampCache.getInstance(cache);
    for(var key:list(registration)){
     if(!running||!lifecycle.current(registration))return count;
     if(times.needsRegeneration(c.scan.dimPath(),key.regionX(),key.regionZ(),file(c,key))){queue.mark(key,now());count++;}
    }
   }catch(IOException failure){if(running&&lifecycle.current(registration)){queue.markDimension(c.dimension,now());LOG.warn("Dimension map reconciliation deferred; pending marker retained: {}",c.dimension,failure);}}
   return count;
  }
  List<DirtyRegionQueue.Key> list(PostSaveRegions.Registration<Context> registration)throws IOException{
   Context c=registration.value();
   List<DirtyRegionQueue.Key> found=new ArrayList<>();
   try(var paths=Files.newDirectoryStream(c.scan.regionDir(),"r.*.*.mca")){
    for(Path path:paths){if(!running||!lifecycle.current(registration))throw new InterruptedIOException("Server/level stopping");var m=MCA.matcher(path.getFileName().toString());if(!m.matches()||Files.isSymbolicLink(path))continue;
     try{found.add(new DirtyRegionQueue.Key(c.dimension,Integer.parseInt(m.group(1)),Integer.parseInt(m.group(2))));}catch(NumberFormatException ignored){}
    }
   }catch(NoSuchFileException absent){return List.of();}return found;
  }
  Path file(Context c,DirtyRegionQueue.Key k){return c.scan.regionDir().resolve("r."+k.regionX()+"."+k.regionZ()+".mca");}
  void persist()throws IOException{if(!journalReady)return;long before=queue.revision();queue.save(journal);persisted=before;lastJournal=now();}
  void step(){
   if(!running)return;DirtyRegionQueue.Work work=null;PostSaveRegions.Registration<Context> registration=null;
   try{
    if(queue.revision()!=persisted||now()-lastJournal>=10000)persist();
    if(budget.pressure()||!DirtyUpdateBudget.diskAllows(Files.getFileStore(cache).getUsableSpace()))return;
    work=queue.take(now(),false,ConversionOrchestrator.isRunning()||DownloadGenerationGate.INSTANCE.blocking()>0);if(work==null)return;
    registration=lifecycle.get(work.key().dimension());if(registration==null){queue.defer(work,now());return;}Context c=registration.value();
    if(work.fullDimension()){var listed=list(registration);if(!lifecycle.current(registration)||!queue.expandDimension(work,listed,now()))queue.defer(work,now());return;}
    Path mca=file(c,work.key());long timestamp=Files.exists(mca)?Files.getLastModifiedTime(mca).toMillis():0;
    DirtyRegionQueue.Key key=work.key();persist();
    final var owner=registration;
    try(var snapshot=SavedRegionSnapshot.capture(inputs,key,c.storage,
            (x,z)->lifecycle.submit(owner,()->c.level.getChunkSource().chunkMap.read(new ChunkPos(x,z))),()->running&&lifecycle.current(owner), budget::pressure,timestamp)){
     if(!running||!lifecycle.current(owner)){queue.defer(work,now());return;}
     var scan=c.scan;var stable=new ConversionOrchestrator.IncrementalScanSnapshot(scan.dimPath(),scan.xaeroDimName(),snapshot.directory(),scan.baseOutputDir(),scan.dimTypeInfo(),scan.passes());
     var result=ConversionOrchestrator.generateDirtyRegion(stable,key.regionX(),key.regionZ());
     if(!lifecycle.current(owner)||result==ConversionOrchestrator.DirtyResult.DEFERRED)queue.defer(work,now());
     else {boolean success=result==ConversionOrchestrator.DirtyResult.COMPLETE||result==ConversionOrchestrator.DirtyResult.UNCHANGED;queue.finish(work,success,now());
      if(success){completed++;boolean changed=result==ConversionOrchestrator.DirtyResult.COMPLETE;if(changed)notifyPlayers(key,completed);LOG.info("Saved-region map committed: dimension={}, region={},{} chunks={}, pending={}, commits={}, completed={}, contentChanged={}",key.dimension(),key.regionX(),key.regionZ(),snapshot.chunks(),queue.size(),commits.get(),completed,changed);}
     }
    }
    persist();
   }catch(SavedRegionSnapshot.Deferred|InterruptedIOException deferred){if(work!=null)queue.defer(work,now());}
   catch(Exception failure){boolean stale=registration!=null&&!lifecycle.current(registration);if(work!=null){if(stale)queue.defer(work,now());else queue.finish(work,false,now());}if(!stale&&(now()-lastWarning>30000||lastWarning==0)){lastWarning=now();LOG.error("Saved-region map job failed; retained for retry",failure);}}
  }
  void notifyPlayers(DirtyRegionQueue.Key key,long revision){
   server.execute(()->{
    if(!running||current!=this)return;
    for(var player:server.getPlayerList().getPlayers()){
     if(!DownloadController.wantsUpdate(player,key)||!player.connection.hasChannel(com.mapsyncer.network.payload.MotiquiesMapUpdatedPayload.TYPE))continue;
     try{net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,new com.mapsyncer.network.payload.MotiquiesMapUpdatedPayload(revision));}
     catch(RuntimeException failure){LOG.debug("Optional saved-region hint unavailable; periodic sync retained",failure);}
    }
   });
  }
 }
}
