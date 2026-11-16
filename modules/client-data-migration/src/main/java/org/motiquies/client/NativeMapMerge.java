package org.motiquies.client;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.motiquies.map.MapSourceTimes;

/** Pure native 6.8 merge. Palette indices are resolved by the codec, never byte-spliced. */
final class NativeMapMerge {
 record Input(NativeXaeroRegionCodec.Region region,MapSourceTimes source){}
 record Result(byte[] zip,MapTileFreshness.Stamp[] stamps,int retained,int imported,int cleared){}
 /** Exact old receipt match proves these disk tiles were imported, not locally edited. */
 static void proveUnchangedImport(byte[] disk,String receipt,MapSourceTimes source,MapTileFreshness.Stamp[] saved)throws IOException{
  if(disk==null||receipt==null||source==null||source.epoch().isEmpty())return;
  var crc=new CRC32();crc.update(disk);if(!String.format("%08x",crc.getValue()).equals(receipt))return;
  var region=unzip(disk).region();for(int i=0;i<1024;i++)if(saved[i]==null&&region.tile(i)!=null)
   saved[i]=new MapTileFreshness.Stamp(source.epoch(),0,false,NativeXaeroRegionCodec.fingerprint(region.tile(i)));
 }
 static Input unzip(byte[] data)throws IOException{
  if(data.length>32*1024*1024)throw new IOException("Region ZIP too large");
  byte[] raw=null;MapSourceTimes times=null;Set<String> names=new HashSet<>();int entries=0;
  try(var z=new ZipInputStream(new ByteArrayInputStream(data))){
   for(ZipEntry entry;(entry=z.getNextEntry())!=null;){
    if(++entries>4||!names.add(entry.getName()))throw new IOException("Duplicate/excessive region ZIP entries");
    int limit=entry.getName().equals("region.xaero")?NativeXaeroRegionCodec.MAX_RAW_BYTES:9000;
    byte[] bytes=z.readNBytes(limit+1);if(bytes.length>limit)throw new IOException("Region ZIP entry too large");
    if(entry.getName().equals("region.xaero")){
     if(entries!=1)throw new IOException("Native region must be first ZIP entry");raw=bytes;
    }else if(entry.getName().equals(MapSourceTimes.ENTRY))times=MapSourceTimes.decode(bytes);
    else throw new IOException("Unknown region ZIP entry");
   }
  }
  if(raw==null)throw new IOException("Missing native region");return new Input(NativeXaeroRegionCodec.decode(raw),times);
 }
 static byte[] zip(NativeXaeroRegionCodec.Region region,MapSourceTimes source)throws IOException{
  var b=new ByteArrayOutputStream();try(var z=new ZipOutputStream(b)){
   ZipEntry entry=new ZipEntry("region.xaero");entry.setTime(0);z.putNextEntry(entry);z.write(NativeXaeroRegionCodec.encode(region));z.closeEntry();
   if(source!=null){entry=new ZipEntry(MapSourceTimes.ENTRY);entry.setTime(0);z.putNextEntry(entry);z.write(source.encode());z.closeEntry();}
  }return b.toByteArray();
 }
 static Result merge(byte[] incoming,byte[] disk,NativeXaeroRegionCodec.Region memory,MapTileFreshness.Stamp[] saved,
                     MapTileFreshness.Stamp[] observations)throws IOException{
  Input server=unzip(incoming);var serverRegion=server.region();
  var local=disk==null?new NativeXaeroRegionCodec.Region(new NativeXaeroRegionCodec.Tile[1024]):unzip(disk).region();
  var tiles=local.tiles();if(memory!=null)for(int i=0;i<1024;i++)if(memory.tile(i)!=null)tiles[i]=memory.tile(i);
  local=new NativeXaeroRegionCodec.Region(tiles);boolean[] keep=new boolean[1024];var result=new MapTileFreshness.Stamp[1024];int retained=0,imported=0,cleared=0;
  for(int i=0;i<1024;i++){
   var tile=local.tile(i);String hash=tile==null?null:NativeXaeroRegionCodec.fingerprint(tile);
   var stamp=observations!=null&&observations[i]!=null?observations[i]:saved[i];
   keep[i]=MapTileFreshness.keepLocal(tile!=null,stamp,hash,serverRegion.tile(i)!=null,server.source(),i);
   if(keep[i]){retained++;if(stamp!=null&&Objects.equals(stamp.hash(),hash))result[i]=stamp;}
   else if(serverRegion.tile(i)!=null){
    imported++;var source=server.source();
    if(source!=null&&source.tick(i)>=0)result[i]=new MapTileFreshness.Stamp(source.epoch(),source.tick(i),false,NativeXaeroRegionCodec.fingerprint(serverRegion.tile(i)));
   }else if(tile!=null)cleared++;
  }
  var combined=NativeXaeroRegionCodec.combine(local,serverRegion,keep);
  return new Result(retained==0?incoming:zip(combined,server.source()),result,retained,imported,cleared);
 }
 private NativeMapMerge(){}
}
