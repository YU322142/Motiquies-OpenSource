package org.motiquies.client;
import java.util.*;
import org.motiquies.map.MapSourceTimes;
public class NativeMapMergeTest {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static NativeXaeroRegionCodec.Tile tile(int height){
  var pixels=new NativeXaeroRegionCodec.Pixel[256];Arrays.fill(pixels,new NativeXaeroRegionCodec.Pixel(height<<12,null,-1,new NativeXaeroRegionCodec.Overlay[0],null));
  return new NativeXaeroRegionCodec.Tile(pixels,new NativeXaeroRegionCodec.Footer(1,256,16));
 }
 static NativeXaeroRegionCodec.Tile stairs(boolean nativeOrder,String facing)throws Exception{
  var bytes=new java.io.ByteArrayOutputStream();var out=new java.io.DataOutputStream(bytes);
  out.writeByte(10);out.writeUTF("");
  if(!nativeOrder){out.writeByte(8);out.writeUTF("Name");out.writeUTF("minecraft:oak_stairs");}
  out.writeByte(10);out.writeUTF("Properties");
  String[] keys=nativeOrder?new String[]{"half","waterlogged","shape","facing"}:new String[]{"facing","half","shape","waterlogged"};
  for(String key:keys){out.writeByte(8);out.writeUTF(key);out.writeUTF(switch(key){case "facing"->facing;case "half"->"bottom";case "shape"->"straight";default->"false";});}
  out.writeByte(0);
  if(nativeOrder){out.writeByte(8);out.writeUTF("Name");out.writeUTF("minecraft:oak_stairs");}
  out.writeByte(0);
  var pixels=new NativeXaeroRegionCodec.Pixel[256];Arrays.fill(pixels,new NativeXaeroRegionCodec.Pixel((4<<12)|1,new NativeXaeroRegionCodec.Blob(bytes.toByteArray()),-1,new NativeXaeroRegionCodec.Overlay[0],null));
  return new NativeXaeroRegionCodec.Tile(pixels,new NativeXaeroRegionCodec.Footer(1,256,16));
 }
 static void nativeStateOrderDoesNotFreezeServerUpdates()throws Exception{
  var disk=new NativeXaeroRegionCodec.Tile[1024];disk[0]=stairs(false,"north");
  var memory=new NativeXaeroRegionCodec.Tile[1024];memory[0]=stairs(true,"north");
  var server=new NativeXaeroRegionCodec.Tile[1024];server[0]=stairs(false,"south");
  check(!NativeXaeroRegionCodec.legacyFingerprint(disk[0]).equals(NativeXaeroRegionCodec.legacyFingerprint(memory[0])),"fixture reproduces different native/converter NBT byte order");
  var saved=new MapTileFreshness.Stamp[1024];saved[0]=new MapTileFreshness.Stamp("epoch",100,false,NativeXaeroRegionCodec.fingerprint(disk[0]));
  long[] ticks=new long[1024];Arrays.fill(ticks,200);
  var result=NativeMapMerge.merge(NativeMapMerge.zip(new NativeXaeroRegionCodec.Region(server),new MapSourceTimes("epoch",ticks)),NativeMapMerge.zip(new NativeXaeroRegionCodec.Region(disk),null),new NativeXaeroRegionCodec.Region(memory),saved,null);
  check(result.imported()==1&&result.retained()==0,"native NBT reordering must not make unchanged imported memory unknown");
  check(NativeXaeroRegionCodec.fingerprint(NativeMapMerge.unzip(result.zip()).region().tile(0)).equals(NativeXaeroRegionCodec.fingerprint(server[0])),"newer server state replaces reencoded imported state");
 }
 public static void main(String[] args)throws Exception{
  nativeStateOrderDoesNotFreezeServerUpdates();
  var localTiles=new NativeXaeroRegionCodec.Tile[1024];localTiles[0]=tile(4);localTiles[2]=tile(6);localTiles[3]=tile(7);
  var serverTiles=new NativeXaeroRegionCodec.Tile[1024];serverTiles[0]=tile(1);serverTiles[1]=tile(2);serverTiles[2]=tile(3);
  var local=new NativeXaeroRegionCodec.Region(localTiles);var server=new NativeXaeroRegionCodec.Region(serverTiles);
  long[] times=new long[1024];Arrays.fill(times,200);var src=new MapSourceTimes("epoch",times);
  var stamps=new MapTileFreshness.Stamp[1024];for(int i:new int[]{0,2,3})stamps[i]=new MapTileFreshness.Stamp("epoch",i==0?201:199,true,NativeXaeroRegionCodec.fingerprint(localTiles[i]));
  var result=NativeMapMerge.merge(NativeMapMerge.zip(server,src),NativeMapMerge.zip(local,null),null,stamps,null);
  var merged=NativeMapMerge.unzip(result.zip()).region();
  check(NativeXaeroRegionCodec.fingerprint(merged.tile(0)).equals(NativeXaeroRegionCodec.fingerprint(localTiles[0])),"new local retained");
  check(merged.tile(1)!=null,"same region unexplored filled");
  check(NativeXaeroRegionCodec.fingerprint(merged.tile(2)).equals(NativeXaeroRegionCodec.fingerprint(serverTiles[2])),"new server replaces old local");
  check(merged.tile(3)==null,"new source clears older cave");
  var memTiles=new NativeXaeroRegionCodec.Tile[1024];memTiles[0]=tile(12);var observations=new MapTileFreshness.Stamp[1024];observations[0]=new MapTileFreshness.Stamp("epoch",202,true,NativeXaeroRegionCodec.fingerprint(memTiles[0]));
  var pending=NativeMapMerge.merge(NativeMapMerge.zip(server,src),NativeMapMerge.zip(local,null),new NativeXaeroRegionCodec.Region(memTiles),stamps,observations);
  check(NativeXaeroRegionCodec.fingerprint(NativeMapMerge.unzip(pending.zip()).region().tile(0)).equals(observations[0].hash()),"unsaved memory survives");
  var repeated=NativeMapMerge.merge(NativeMapMerge.zip(server,src),pending.zip(),null,pending.stamps(),null);
  check(Arrays.equals(repeated.zip(),pending.zip()),"idempotent replay");
  var legacy=NativeMapMerge.merge(NativeMapMerge.zip(server,src),NativeMapMerge.zip(local,null),null,new MapTileFreshness.Stamp[1024],null);
  check(legacy.retained()==3,"legacy unknown retained");
  byte[] old=NativeMapMerge.zip(local,null);var crc=new java.util.zip.CRC32();crc.update(old);var proved=new MapTileFreshness.Stamp[1024];
  NativeMapMerge.proveUnchangedImport(old,String.format("%08x",crc.getValue()),src,proved);check(proved[0]!=null&&!proved[0].local(),"exact receipt proves old import");
  var stale=new MapTileFreshness.Stamp[1024];NativeMapMerge.proveUnchangedImport(old,"deadbeef",src,stale);check(stale[0]==null,"changed local source never inferred from stale receipt");
  System.out.println("NativeMapMergeTest passed: 9 scenarios");
 }
}
