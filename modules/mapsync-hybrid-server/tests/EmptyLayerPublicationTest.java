package com.mapsyncer.server;
import java.nio.file.*;
import java.util.*;
public final class EmptyLayerPublicationTest {
 @SuppressWarnings({"unchecked","rawtypes"}) public static void main(String[] args)throws Exception{
  Path root=Path.of(args[0]);Files.createDirectories(root);
  Class<?> cacheType=Class.forName("com.mapsyncer.server.GenerationCache"),publisher=Class.forName("com.mapsyncer.server.EmptyLayerPublication"),layerType=Class.forName("com.mapsyncer.server.EmptyLayerPublication$Layer");
  Object cache=cacheType.getMethod("getInstance",Path.class).invoke(null,root);
  Class<?> mode=Class.forName("com.mapsyncer.mca.LightMode"),params=Class.forName("com.mapsyncer.mca.RegionConverterStandalone$CaveModeParams");
  Object cave=Enum.valueOf((Class)mode,"CAVE"),caveParams=params.getConstructor(int.class,int.class).newInstance(304,16);
  var constructor=layerType.getConstructor(String.class,Path.class,int.class,int.class,int.class,mode,params);
  var complete=publisher.getMethod("completeRemoved",List.class,Map.class,cacheType,long.class,boolean.class);
  String key="null/caves/18/2_0";Path output=root.resolve("caves/18");Object layer=constructor.newInstance(key,output,2,0,-64,cave,caveParams);
  cacheType.getMethod("update",String.class,long.class,String.class).invoke(cache,key,100L,"11111111");Object before=cacheType.getMethod("getMeta",String.class).invoke(cache,key);
  cacheType.getMethod("remove",String.class).invoke(cache,key);
  complete.invoke(null,List.of(layer),Map.of(key,before),cache,200L,false);
  Path zip=output.resolve("2_0.zip");byte[] bytes=Files.readAllBytes(zip);
  if(!(Boolean)Class.forName("com.mapsyncer.util.HashUtils").getMethod("isValidRegionZip",byte[].class).invoke(null,(Object)bytes))throw new AssertionError("empty publication must be a valid native map ZIP");
  try(var z=new java.util.zip.ZipFile(zip.toFile());var in=new java.io.DataInputStream(z.getInputStream(z.getEntry("region.xaero")))){
   if(in.readUnsignedByte()!=255||in.readInt()!=((6<<16)|8))throw new AssertionError("native format version changed");
   for(int x=0;x<8;x++)for(int y=0;y<8;y++){
    if(in.readUnsignedByte()!=((x<<4)|y))throw new AssertionError("native tile-chunk position");
    for(int tile=0;tile<16;tile++)if(in.readInt()!=-1)throw new AssertionError("empty layer retained old pixels");
   }
   if(in.read()!=-1)throw new AssertionError("unexpected native empty payload tail");
  }
  Object emptyMeta=cacheType.getMethod("getMeta",String.class).invoke(cache,key);if(emptyMeta==null)throw new AssertionError("empty source must have real CRC/timestamp metadata");
  cacheType.getMethod("remove",String.class).invoke(cache,key);complete.invoke(null,List.of(layer),Map.of(key,emptyMeta),cache,201L,false);
  if(!Arrays.equals(bytes,Files.readAllBytes(zip)))throw new AssertionError("repeat empty publication changes content identity");
  Object absent=constructor.newInstance("null/caves/19/2_0",root.resolve("caves/19"),2,0,-64,cave,caveParams);
  complete.invoke(null,List.of(absent),Map.of(),cache,202L,false);if(Files.exists(root.resolve("caves/19/2_0.zip")))throw new AssertionError("never-published empty layers should stay omitted");
  cacheType.getMethod("remove",String.class).invoke(cache,key);complete.invoke(null,List.of(layer),Map.of(key,before),cache,203L,true);
  if(!before.equals(cacheType.getMethod("getMeta",String.class).invoke(cache,key)))throw new AssertionError("failed conversion must retain previous publication identity for retry");
  Path blocked=root.resolve("blocked");Files.writeString(blocked,"sentinel");Object bad=constructor.newInstance(key,blocked,2,0,-64,cave,caveParams);cacheType.getMethod("remove",String.class).invoke(cache,key);
  boolean failed=false;try{complete.invoke(null,List.of(bad),Map.of(key,before),cache,204L,false);}catch(java.lang.reflect.InvocationTargetException e){failed=e.getCause() instanceof java.io.IOException;}
  if(!failed||!before.equals(cacheType.getMethod("getMeta",String.class).invoke(cache,key))||!Files.readString(blocked).equals("sentinel"))throw new AssertionError("failed empty write must restore prior publication identity and preserve unrelated file");
  System.out.println("PASS empty layer publication: valid native empty ZIP, CRC, no stale tiles, idempotence, omit never-published layers, failed conversion/write preserve retry identity");
 }
}
