package com.mapsyncer.server;
import java.nio.file.*;
import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.*;
import org.motiquies.map.*;
import com.mapsyncer.mca.RegionConverterStandalone.ConvertedRegion;
public class SourceFreshnessTest {
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 static byte[] raw(int height)throws Exception{
  var pixels=new NativeXaeroRegionCodec.Pixel[256];Arrays.fill(pixels,new NativeXaeroRegionCodec.Pixel(height<<12,null,-1,new NativeXaeroRegionCodec.Overlay[0],null));
  var tiles=new NativeXaeroRegionCodec.Tile[1024];tiles[0]=new NativeXaeroRegionCodec.Tile(pixels,new NativeXaeroRegionCodec.Footer(1,256,16));
  return NativeXaeroRegionCodec.encode(new NativeXaeroRegionCodec.Region(tiles));
 }
 static void mca(Path source,long tick)throws Exception{
  Files.createDirectories(source);var info=new RegionStorageInfo("qa-only",ResourceKey.create(Registries.DIMENSION,ResourceLocation.withDefaultNamespace("overworld")),"chunk");
  try(var region=new RegionFile(info,source.resolve("r.0.0.mca"),source,false);var out=region.getChunkDataOutputStream(new ChunkPos(0,0))){var tag=new CompoundTag();tag.putLong("LastUpdate",tick);NbtIo.write(tag,out);}
 }
 static MapSourceTimes meta(Path zip)throws Exception{
  try(var z=new java.util.zip.ZipFile(zip.toFile());var in=z.getInputStream(z.getEntry(MapSourceTimes.ENTRY))){check(z.entries().nextElement().getName().equals("region.xaero"),"native entry first");return MapSourceTimes.decode(in.readAllBytes());}
 }
 static void compressedMca(Path source,int compression,long tick)throws Exception{
  Files.createDirectories(source);var bytes=new java.io.ByteArrayOutputStream();var tag=new CompoundTag();tag.putLong("LastUpdate",tick);
  try(var data=new java.io.DataOutputStream(RegionFileVersion.fromId(compression).wrap(bytes))){NbtIo.write(tag,data);}
  byte[] payload=bytes.toByteArray();int sectors=(payload.length+5+4095)/4096;
  try(var file=new java.io.RandomAccessFile(source.resolve("r.0.0.mca").toFile(),"rw")){
   file.setLength(8192);file.seek(0);file.writeInt((2<<8)|sectors);file.seek(8192);file.writeInt(payload.length+1);file.writeByte(compression);file.write(payload);
  }
 }
 public static void main(String[] args)throws Exception{
  if(Arrays.asList(args).contains("--benchmark-only")){benchmark(Path.of(args[0]));return;}
  Path root=Path.of(args[0]),source=root.resolve("input"),cache=root.resolve("cache"),out=root.resolve("map");mca(source,100);
  String property="motiquies.mapsync.immutableInputRoot";
  System.clearProperty(property);boolean rejected=false;
  try(var scope=SourceFreshness.open(source,0,0,cache)){}catch(java.io.IOException expected){rejected=true;}
  check(rejected,"unqualified live full input must defer before provenance publication");
  Path nonAligned=root.resolve("nonaligned");mca(nonAligned,77);Path nonAlignedFile=nonAligned.resolve("r.0.0.mca");
  byte[] aligned=Files.readAllBytes(nonAlignedFile);Files.write(nonAlignedFile,Arrays.copyOf(aligned,aligned.length-1));
  byte[] frozenBytes=Files.readAllBytes(nonAlignedFile);System.setProperty(property,nonAligned.toString());boolean opened=false;
  try(var scope=SourceFreshness.open(nonAligned,0,0,cache)){opened=SourceFreshness.current().tick(0)==77;}catch(java.io.IOException rejectedRead){}
  check(Arrays.equals(frozenBytes,Files.readAllBytes(nonAlignedFile)),"read-only LastUpdate reader must never pad or modify nonaligned MCA input");
  check(opened,"valid nonaligned MCA can be read without forcing alignment");
  for(int compression:new int[]{1,2,3,4}){
   Path variant=root.resolve("compression-"+compression);compressedMca(variant,compression,1000+compression);Path file=variant.resolve("r.0.0.mca");byte[] before=Files.readAllBytes(file);
   System.setProperty(property,variant.toString());
   try(var scope=SourceFreshness.open(variant,0,0,cache)){check(SourceFreshness.current().tick(0)==1000+compression,"native compression codec " + compression);}
   check(Arrays.equals(before,Files.readAllBytes(file)),"compression source remains byte-identical " + compression);
  }
  check(SourceFreshness.epoch(cache).equals(SourceFreshness.epoch(cache)),"background epoch lookup is persistent");
  System.setProperty(property,source.toString());
  XaeroWriter.RegionWriteResult a;
  try(var scope=SourceFreshness.open(source,0,0,cache)){check(SourceFreshness.current().tick(0)==100&&SourceFreshness.current().tick(1)==-1,"snapshot LastUpdate with unknown absent");a=XaeroWriter.writeRegionFile(out,new ConvertedRegion(0,0,raw(10)));}
  check(SourceFreshness.current()==null,"threadlocal released");check(meta(a.path()).tick(0)==100,"source time paired with CRC");
  mca(source,200);XaeroWriter.RegionWriteResult b;
  try(var scope=SourceFreshness.open(source,0,0,cache)){b=XaeroWriter.writeRegionFile(out,new ConvertedRegion(0,0,raw(10)));}
  check(a.crc32Hash().equals(b.crc32Hash()),"autosave-only LastUpdate does not trigger download");check(meta(b.path()).tick(0)==100,"unchanged tile keeps source tick");
  try(var scope=SourceFreshness.open(source,0,0,cache)){b=XaeroWriter.writeRegionFile(out,new ConvertedRegion(0,0,raw(11)));}
  check(!a.crc32Hash().equals(b.crc32Hash()),"changed tile gets new CRC");check(meta(b.path()).tick(0)==200,"changed tile source tick advances");
  byte[] empty=NativeXaeroRegionCodec.encode(new NativeXaeroRegionCodec.Region(new NativeXaeroRegionCodec.Tile[1024]));
  XaeroWriter.RegionWriteResult firstEmpty;
  try(var scope=SourceFreshness.open(source,0,0,cache)){firstEmpty=XaeroWriter.writeRegionFile(out,new ConvertedRegion(0,0,empty));}
  check(meta(firstEmpty.path()).tick(0)==200,"empty tombstone carries changed chunk tick");
  mca(source,300);
  try(var scope=SourceFreshness.open(source,0,0,cache)){
   if(!Arrays.asList(args).contains("--omit-stash"))SourceFreshness.remember(firstEmpty.path()); // exact purge/republication sequence used by empty-layer completion
   Files.delete(firstEmpty.path());
   b=XaeroWriter.writeRegionFile(out,new ConvertedRegion(0,0,empty));
  }
  check(firstEmpty.crc32Hash().equals(b.crc32Hash()),"already empty autosave retains CRC after purge");
  check(meta(b.path()).tick(0)==200,"already empty tombstone keeps original semantic tick");
  byte[] publicationBefore=Files.readAllBytes(b.path());rejected=false;
  try(var scope=SourceFreshness.open(source,0,0,cache)){
   mca(source,400);
   try{XaeroWriter.writeRegionFile(out,new ConvertedRegion(0,0,raw(12)));}catch(java.io.IOException expected){rejected=true;}
  }catch(java.io.IOException expected){rejected=true;}
  check(rejected,"source mutation between provenance read and publication rejects output");
  check(Arrays.equals(publicationBefore,Files.readAllBytes(b.path())),"unstable source leaves old ZIP unchanged");
  boolean closeRejected=false;
  try(var scope=SourceFreshness.open(source,0,0,cache)){
   XaeroWriter.writeRegionFile(out.resolve("after-publication"),new ConvertedRegion(0,0,raw(15)));
   Path mca=source.resolve("r.0.0.mca");var originalTime=Files.getLastModifiedTime(mca);
   byte[] changed=Files.readAllBytes(mca);changed[changed.length-1]^=1;Files.write(mca,changed);Files.setLastModifiedTime(mca,originalTime);
  }catch(java.io.IOException expected){closeRejected=true;}
  check(closeRejected,"scope close rejects a content change after last publication even with restored size and mtime");
  check(SourceFreshness.current()==null,"failed close always releases threadlocal");
  // Capture an actual external .mcc chunk, then change only its external bytes after metadata read.
  var externalTag=new CompoundTag();externalTag.putLong("LastUpdate",450);
  byte[] random=new byte[1_200_000];new Random(17).nextBytes(random);externalTag.putByteArray("largeFixture",random);
  try(var region=new RegionFile(new RegionStorageInfo("qa-only",net.minecraft.world.level.Level.OVERWORLD,"chunk"),source.resolve("r.0.0.mca"),source,false);
      var data=region.getChunkDataOutputStream(new ChunkPos(0,0))){NbtIo.write(externalTag,data);}
  Path external=source.resolve("c.0.0.mcc");check(Files.exists(external),"fixture uses actual external MCC storage");rejected=false;
  byte[] mcaBefore=Files.readAllBytes(source.resolve("r.0.0.mca")),externalBefore=Files.readAllBytes(external);
  try(var scope=SourceFreshness.open(source,0,0,cache)){check(SourceFreshness.current().tick(0)==450,"external native NBT tick captured read-only");}
  check(Arrays.equals(mcaBefore,Files.readAllBytes(source.resolve("r.0.0.mca")))&&Arrays.equals(externalBefore,Files.readAllBytes(external)),"MCA and external MCC remain byte-identical after complete read scope");
  try(var scope=SourceFreshness.open(source,0,0,cache)){
   check(SourceFreshness.current().tick(0)==450,"external native NBT tick captured");
   byte[] changed=Files.readAllBytes(external);changed[changed.length-1]^=1;Files.write(external,changed);
   try{XaeroWriter.writeRegionFile(out,new ConvertedRegion(0,0,raw(13)));}catch(java.io.IOException expected){rejected=true;}
  }catch(java.io.IOException expected){rejected=true;}
  check(rejected,"external MCC mutation rejects publication");
  check(Arrays.equals(publicationBefore,Files.readAllBytes(b.path())),"external mutation leaves old ZIP unchanged");
  System.clearProperty(property);
  Path privateInput=cache.resolve("motiquies-dirty-inputs/dirty-test");mca(privateInput,500);
  try(var scope=SourceFreshness.open(privateInput,0,0,cache)){check(SourceFreshness.current().tick(0)==500,"owned native dirty snapshot admitted without offline override");}
  Path sibling=root.resolve("outside");mca(sibling,600);System.setProperty(property,source.toString());rejected=false;
  try(var scope=SourceFreshness.open(sibling,0,0,cache)){}catch(java.io.IOException expected){rejected=true;}
  check(rejected,"offline property does not admit sibling live directories");
  System.setProperty(property,privateInput.toString());
  try(var scope=SourceFreshness.open(privateInput,0,0,cache)){
   rejected=false;try{SourceFreshness.remember(out.resolve("1_0.zip"));}catch(java.io.IOException expected){rejected=true;}
   check(rejected,"stash cannot capture another region");
   for(int i=0;i<128;i++)SourceFreshness.remember(out.resolve("layer-"+i).resolve("0_0.zip"));
   rejected=false;try{SourceFreshness.remember(out.resolve("layer-overflow/0_0.zip"));}catch(java.io.IOException expected){rejected=true;}
   check(rejected,"stash layer count bounded");
  }
  System.clearProperty(property);
  System.out.println("SourceFreshnessTest passed: live-input gate, immutable snapshot validation, stable empty tombstones, paired CRC, no autosave-only download");
 }
 static void benchmark(Path root)throws Exception{
  Path source=root.resolve("input"),cache=root.resolve("cache"),out=root.resolve("map");mca(source,450);
  var tag=new CompoundTag();tag.putLong("LastUpdate",450);byte[] random=new byte[1_200_000];new Random(17).nextBytes(random);tag.putByteArray("largeFixture",random);
  try(var region=new RegionFile(new RegionStorageInfo("qa-only",net.minecraft.world.level.Level.OVERWORLD,"chunk"),source.resolve("r.0.0.mca"),source,false);
      var data=region.getChunkDataOutputStream(new ChunkPos(0,0))){NbtIo.write(tag,data);}
  Files.createDirectories(cache);Files.writeString(cache.resolve("motiquies-source-epoch.txt"),"00000000-0000-0000-0000-000000000001");
  System.setProperty("motiquies.mapsync.immutableInputRoot",source.toString());
  Path recordingFile=root.resolve("source-reads.jfr");StringBuilder crcs=new StringBuilder();long elapsed;
  try(var recording=new jdk.jfr.Recording()){
   recording.enable("jdk.FileRead").withThreshold(java.time.Duration.ZERO);recording.start();long begin=System.nanoTime();
   try(var scope=SourceFreshness.open(source,0,0,cache)){
    for(int i=0;i<20;i++)crcs.append(XaeroWriter.writeRegionFile(out.resolve("layer-"+i),new ConvertedRegion(0,0,raw(10))).crc32Hash());
   }
   elapsed=(System.nanoTime()-begin)/1_000_000;recording.stop();recording.dump(recordingFile);
  }
  long bytes=0,events=0;String prefix=source.toAbsolutePath().toString();
  for(var event:jdk.jfr.consumer.RecordingFile.readAllEvents(recordingFile))if(event.getEventType().getName().equals("jdk.FileRead")){
   String path=event.getString("path");
   if(path!=null&&path.startsWith(prefix)&&(path.endsWith(".mca")||path.endsWith(".mcc"))){bytes+=Math.max(0,event.getLong("bytesRead"));events++;}
  }
  String digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(crcs.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
  System.out.println("BENCHMARK layers=20 sourceReadBytes="+bytes+" sourceReadEvents="+events+" elapsedMs="+elapsed+" outputCRCVectorSHA256="+digest+" recording="+recordingFile);
 }
}
