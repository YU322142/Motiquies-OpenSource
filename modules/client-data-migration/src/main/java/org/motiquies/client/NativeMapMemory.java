package org.motiquies.client;
import java.io.*;
import org.motiquies.client.mixin.NativeMapSaveAccess;
import xaero.map.file.MapSaveLoad;
import xaero.map.region.MapRegion;

/** Native pixel serializer without saveRegion cleanup/backup/queue side effects. Caller owns native lock. */
final class NativeMapMemory {
 static NativeXaeroRegionCodec.Region snapshot(MapSaveLoad save,MapRegion region)throws IOException{
  if(region==null||region.getLoadState()!=2)return null;
  var access=(NativeMapSaveAccess)save;var b=new ByteArrayOutputStream();
  access.motiquies$blockPalette().clear();access.motiquies$biomePalette().clear();
  try(var out=new DataOutputStream(b)){
   out.writeByte(255);out.writeInt(0x60008);
   for(int tx=0;tx<8;tx++)for(int tz=0;tz<8;tz++){
    var chunk=region.getChunk(tx,tz);if(chunk==null)continue;
    out.writeByte((tx<<4)|tz);
    for(int x=0;x<4;x++)for(int z=0;z<4;z++){
     var tile=chunk.getTile(x,z);
     if(tile==null||!tile.isLoaded()){out.writeInt(-1);continue;}
     for(int bx=0;bx<16;bx++)for(var block:tile.getBlockColumn(bx))access.motiquies$savePixel(block,out,region.getBiomeRegistry());
     out.writeByte(tile.getWorldInterpretationVersion());out.writeInt(tile.getWrittenCaveStart());out.writeByte(tile.getWrittenCaveDepth());
    }
   }
  }finally{access.motiquies$blockPalette().clear();access.motiquies$biomePalette().clear();}
  return NativeXaeroRegionCodec.decode(b.toByteArray());
 }
 private NativeMapMemory(){}
}
