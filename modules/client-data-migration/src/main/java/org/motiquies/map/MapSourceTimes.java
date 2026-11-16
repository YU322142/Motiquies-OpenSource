package org.motiquies.map;
import java.io.*;
import java.util.*;

/** Optional ZIP provenance; region.xaero remains the first, unmodified native entry. */
public record MapSourceTimes(String epoch,long[] ticks) {
 public static final String ENTRY="motiquies-source-times.bin";
 public MapSourceTimes {if(epoch==null||epoch.length()>128||ticks.length!=1024)throw new IllegalArgumentException();ticks=ticks.clone();for(long t:ticks)if(t< -1)throw new IllegalArgumentException();}
 @Override public long[] ticks(){return ticks.clone();}
 public long tick(int i){return ticks[i];}
 public byte[] encode()throws IOException {
  var b=new ByteArrayOutputStream();try(var d=new DataOutputStream(b)){d.writeInt(0x4d535431);d.writeUTF(epoch);for(long t:ticks)d.writeLong(t);}return b.toByteArray();
 }
 public static MapSourceTimes decode(byte[] data)throws IOException {
  if(data.length>9000)throw new IOException("Oversized source provenance");
  try(var d=new DataInputStream(new ByteArrayInputStream(data))){
   if(d.readInt()!=0x4d535431)throw new IOException("Unknown source provenance");
   String epoch=d.readUTF();long[] ticks=new long[1024];for(int i=0;i<1024;i++)ticks[i]=d.readLong();
   if(d.read()!=-1)throw new IOException("Trailing source provenance");
   try{return new MapSourceTimes(epoch,ticks);}catch(IllegalArgumentException e){throw new IOException("Invalid source provenance",e);}
  }
 }
}
