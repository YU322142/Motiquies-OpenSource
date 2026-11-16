package com.mapsyncer.server;
import java.nio.file.*;
import java.util.*;
public final class WriterDeterminismTest {
 public static void main(String[] args)throws Exception{
  Path root=Path.of(args[0]);Files.createDirectories(root);
  Class<?> region=Class.forName("com.mapsyncer.mca.RegionConverterStandalone$ConvertedRegion");
  Object input=region.getConstructor(int.class,int.class,byte[].class).newInstance(0,0,"native map payload fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8));
  var write=Class.forName("com.mapsyncer.server.XaeroWriter").getMethod("writeRegionFile",Path.class,region);
  write.invoke(null,root,input);byte[] first=Files.readAllBytes(root.resolve("0_0.zip"));
  Thread.sleep(2200);write.invoke(null,root,input);byte[] next=Files.readAllBytes(root.resolve("0_0.zip"));
  if(!Arrays.equals(first,next))throw new AssertionError("unchanged native layer must keep identical ZIP/CRC across generation time");
  try(var zip=new java.util.zip.ZipFile(root.resolve("0_0.zip").toFile())){
   if(!new String(zip.getInputStream(zip.getEntry("region.xaero")).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).equals("native map payload fixture"))throw new AssertionError("native entry data changed");
  }
  System.out.println("PASS native writer: unchanged layer keeps identical ZIP/CRC; native entry payload preserved");
 }
}
