package org.motiquies.client;
import java.nio.file.*;
public final class ColdMapCacheTest {
 public static void main(String[] args)throws Exception{
  Path root=Files.createTempDirectory("motiquies-cold-map-test-");
  for(String layer:new String[]{"surface","caves/-4"}){
   Path dir=root.resolve(layer),source=dir.resolve("-1_2.zip"),cache=dir.resolve("cache_1/-1_2.xwmc");
   Files.createDirectories(cache.getParent());Files.writeString(source,"native-map-source");Files.writeString(cache,"derived-old-texture");
   Path personal=dir.resolve("waypoints.txt");Files.writeString(personal,"keep");
   ColdMapCache.invalidate(source,cache,-1,2);
   check(!Files.exists(cache)&&Files.readString(cache.resolveSibling("-1_2.xwmc.outdated")).equals("derived-old-texture"),"native cold lookup must see outdated marker, not old active texture");
   ColdMapCache.invalidate(source,cache,-1,2);
   check(Files.readString(source).equals("native-map-source")&&Files.readString(personal).equals("keep"),"repeat invalidation preserves source and personal content");
  }
  Path dir=root.resolve("surface"),source=dir.resolve("-1_2.zip"),wrong=root.resolve("other/cache_1/-1_2.xwmc");Files.createDirectories(wrong.getParent());Files.writeString(wrong,"other map");
  boolean rejected=false;try{ColdMapCache.invalidate(source,wrong,-1,2);}catch(java.io.IOException expected){rejected=true;}
  check(rejected&&Files.readString(wrong).equals("other map"),"cross-map cache path refused without mutation");
  Path blocked=dir.resolve("cache_1/-1_2.xwmc");Files.createDirectory(blocked);rejected=false;
  try{ColdMapCache.invalidate(source,blocked,-1,2);}catch(java.io.IOException expected){rejected=true;}
  check(rejected&&Files.isDirectory(blocked),"unexpected directory is retained and invalidation fails");
  System.out.println("PASS cold map texture: native outdated marker, surface/cave/negative coords, idempotence, source/personal preservation, boundary and failure");
 }
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
}
