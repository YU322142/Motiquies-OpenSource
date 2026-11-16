package org.motiquies.client;
import org.motiquies.map.MapSourceTimes;
import java.nio.file.*;
import java.util.*;
public class MapTileFreshnessTest {
 static final String HASH="a".repeat(64);
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 public static void main(String[] args)throws Exception{
  long[] ticks=new long[1024];Arrays.fill(ticks,200);var src=new MapSourceTimes("worldA",ticks);
  var local=new MapTileFreshness.Stamp("worldA",201,true,HASH);
  check(MapTileFreshness.keepLocal(true,local,HASH,true,src,0),"newer local");
  check(MapTileFreshness.keepLocal(true,new MapTileFreshness.Stamp("worldA",200,true,HASH),HASH,true,src,0),"tie local");
  check(!MapTileFreshness.keepLocal(true,new MapTileFreshness.Stamp("worldA",199,true,HASH),HASH,true,src,0),"newer server");
  check(MapTileFreshness.keepLocal(true,null,HASH,true,src,0),"legacy unknown");
  check(MapTileFreshness.keepLocal(true,local,"b".repeat(64),true,src,0),"stale sidecar");
  check(MapTileFreshness.keepLocal(true,local,HASH,true,new MapSourceTimes("worldB",ticks),0),"epoch mismatch");
  check(!MapTileFreshness.keepLocal(true,new MapTileFreshness.Stamp("worldA",201,false,HASH),HASH,true,new MapSourceTimes("worldB",ticks),0),"cache rebuild can replace proven unchanged import");
  check(!MapTileFreshness.keepLocal(false,null,"",true,src,0),"fill unexplored");
  check(!MapTileFreshness.keepLocal(true,new MapTileFreshness.Stamp("worldA",200,false,HASH),HASH,false,src,0),"clear imported cave");
  check(MapTileFreshness.keepLocal(true,local,HASH,false,src,0),"keep newer local cave");
  check(MapTileFreshness.keepLocal(true,local,HASH,true,null,0),"missing server provenance");
  check(Arrays.equals(src.encode(),MapSourceTimes.decode(src.encode()).encode()),"wire roundtrip");
  Path p=Files.createTempDirectory("map-freshness-test").resolve("region.meta");var stamps=new MapTileFreshness.Stamp[1024];stamps[7]=local;MapTileFreshness.write(p,stamps);
  check(local.equals(MapTileFreshness.read(p)[7]),"restart preserves provenance");
  System.out.println("MapTileFreshnessTest passed: 12 assertions");
 }
}
