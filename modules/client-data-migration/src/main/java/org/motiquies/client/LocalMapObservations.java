package org.motiquies.client;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import xaero.map.MapProcessor;
import xaero.map.file.MapSaveLoad;
import xaero.map.region.MapRegion;

/** Writer hook records clocks in memory only; stamps are content-bound at native save/merge. */
public final class LocalMapObservations {
 private record Seen(Object connection,Object processor,String dimension,String epoch,long tick,long captured){}
 private static final Map<Path,Map<Integer,Seen>> PENDING=new ConcurrentHashMap<>();
 private static final Map<Path,String> EPOCHS=new ConcurrentHashMap<>();
 public static void observe(MapProcessor processor,net.minecraft.world.level.Level world,int layer,int cx,int cz,long tick){
  try{
   if(net.minecraft.client.Minecraft.getInstance().level!=world)return;
   String dimension=world.dimension().location().toString();
   var save=processor.getMapSaveLoad();Path mw=save.getMWSubFolder(processor.getCurrentWorldId(),processor.getCurrentDimId(),processor.getCurrentMWId());
   if(mw==null)return;Path path=save.getCaveLayerFolder(layer,mw).resolve((cx>>5)+"_"+(cz>>5)+".zip").toAbsolutePath().normalize();
   Object connection=net.minecraft.client.Minecraft.getInstance().getConnection();if(connection==null)return;
   MapObservationClock.activate(connection,dimension);
   long captured=System.nanoTime();var clock=MapObservationClock.get(connection,dimension,captured);
   synchronized(PENDING){
    if(net.minecraft.client.Minecraft.getInstance().level!=world||PENDING.size()>=4096&&!PENDING.containsKey(path))return; // Unknown remains protected.
    PENDING.computeIfAbsent(path,k->new ConcurrentHashMap<>()).put((cx&31)*32+(cz&31),new Seen(connection,processor,dimension,clock==null?"":clock.epoch(),clock==null?Math.max(0,tick):clock.upper(),captured));
   }
  }catch(RuntimeException failure){org.apache.logging.log4j.LogManager.getLogger().debug("Local map observation deferred",failure);}
 }
 static void epoch(Path source,String epoch,Object connection,Object processor){
  source=source.toAbsolutePath().normalize();synchronized(PENDING){EPOCHS.put(source,epoch);var pending=PENDING.get(source);
   if(pending!=null)pending.replaceAll((index,seen)->{
    var clock=MapObservationClock.get(connection,seen.dimension(),System.nanoTime());
    return seen.epoch().isEmpty()&&seen.connection()==connection&&seen.processor()==processor&&clock!=null&&clock.received()>=seen.captured()&&clock.epoch().equals(epoch)
        ?new Seen(connection,processor,seen.dimension(),epoch,clock.upper(),seen.captured()):seen;
   });
  }
 }
 static void committed(Path source,MapTileFreshness.Stamp[] observations){
  source=source.toAbsolutePath().normalize();synchronized(PENDING){
   var pending=PENDING.get(source);if(pending==null)return;
   for(int i=0;i<1024;i++)if(observations[i]!=null){var s=observations[i];var seen=pending.get(i);if(seen!=null&&seen.epoch().equals(s.epoch())&&seen.tick()==s.tick())pending.remove(i,seen);}
   if(pending.isEmpty())PENDING.remove(source,pending);
  }
 }
 static MapTileFreshness.Stamp[] stamps(Path source,NativeXaeroRegionCodec.Region actual)throws java.io.IOException{
  var result=new MapTileFreshness.Stamp[1024];var pending=PENDING.get(source.toAbsolutePath().normalize());
  if(pending!=null&&actual!=null)for(var entry:pending.entrySet()){
   var tile=actual.tile(entry.getKey());if(tile!=null){var seen=entry.getValue();result[entry.getKey()]=new MapTileFreshness.Stamp(seen.epoch(),seen.tick(),true,NativeXaeroRegionCodec.fingerprint(tile));}
  }return result;
 }
 public static void saved(MapSaveLoad save,MapRegion region){
  try{
   Path path=save.getNormalFile(region).toPath().toAbsolutePath().normalize();var pending=PENDING.get(path);if(pending==null||pending.isEmpty()||!Files.exists(path))return;
   synchronized(region){
    var memory=NativeMapMemory.snapshot(save,region);if(memory==null)return;
    var disk=NativeMapMerge.unzip(Files.readAllBytes(path)).region();var local=stamps(path,memory);var saved=readSafe(path,disk);boolean changed=false;
    var persisted=new MapTileFreshness.Stamp[1024];
    for(int i=0;i<1024;i++)if(local[i]!=null&&disk.tile(i)!=null&&local[i].hash().equals(NativeXaeroRegionCodec.fingerprint(disk.tile(i)))){saved[i]=local[i];persisted[i]=local[i];changed=true;}
    if(changed){MapTileFreshness.write(MapTileFreshness.sidecar(path),saved);committed(path,persisted);}
   }
  }catch(Exception failure){org.apache.logging.log4j.LogManager.getLogger().warn("Map freshness not persisted; existing terrain remains conservatively protected",failure);}
 }
 static void logout(){synchronized(PENDING){PENDING.clear();EPOCHS.clear();}MapObservationClock.clear();}
 static MapTileFreshness.Stamp[] readSafe(Path source){
  try{return readSafe(source,Files.exists(source)?NativeMapMerge.unzip(Files.readAllBytes(source)).region():new NativeXaeroRegionCodec.Region(new NativeXaeroRegionCodec.Tile[1024]));}
  catch(java.io.IOException failure){org.apache.logging.log4j.LogManager.getLogger().warn("Unreadable map provenance retained; preserving existing local terrain",failure);return new MapTileFreshness.Stamp[1024];}
 }
 static MapTileFreshness.Stamp[] readSafe(Path source,NativeXaeroRegionCodec.Region disk){
  try{return MapTileFreshness.recover(source,disk);}
  catch(java.io.IOException failure){org.apache.logging.log4j.LogManager.getLogger().warn("Unreadable map provenance retained; preserving existing local terrain",failure);return new MapTileFreshness.Stamp[1024];}
 }
 private LocalMapObservations(){}
}
