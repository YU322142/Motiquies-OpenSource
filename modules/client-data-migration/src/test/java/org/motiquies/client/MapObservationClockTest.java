package org.motiquies.client;
import org.motiquies.map.MapSourceTimes;
import java.util.*;
public class MapObservationClockTest {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 public static void main(String[] args){
  Object conn=new Object();MapObservationClock.clear();MapObservationClock.receive(conn,"epoch","minecraft:overworld",100,5,10);
  var c=MapObservationClock.get(conn,"minecraft:overworld",20);check(c!=null&&c.upper()==105,"watermark includes remaining server tick batch");
  // Client's interpolated time might still be90 while actual explored content is from100.
  long[] source=new long[1024];Arrays.fill(source,95);String hash="a".repeat(64);
  var actual=new MapTileFreshness.Stamp("epoch",c.upper(),true,hash);
  check(MapTileFreshness.keepLocal(true,actual,hash,true,new MapSourceTimes("epoch",source),0),"older snapshot cannot beat a lagging client clock");
  check(MapObservationClock.get(new Object(),"minecraft:overworld",20)==null,"old connection rejected");
  check(MapObservationClock.get(conn,"minecraft:the_nether",20)==null,"dimension isolated");
  check(MapObservationClock.get(conn,"minecraft:overworld",1_000_000_011L)==null,"expired watermark becomes unknown");
  MapObservationClock.receive(conn,"epoch","minecraft:overworld",99,5,30);check(MapObservationClock.get(conn,"minecraft:overworld",31)==null,"clock rollback becomes unknown");
  MapObservationClock.receive(conn,"new-world","minecraft:overworld",1,5,40);check(MapObservationClock.get(conn,"minecraft:overworld",41).epoch().equals("new-world"),"new epoch has independent time");
  MapObservationClock.clear();check(MapObservationClock.get(conn,"minecraft:overworld",41)==null,"logout clears clock");
  System.out.println("MapObservationClockTest passed: skew, conservative bound, expiry, session, dimension, rollback, epoch, logout");
 }
}
