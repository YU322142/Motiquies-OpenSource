package org.motiquies.client;
import java.util.*;
/** Server tick watermark, independent of the client's interpolated game clock. */
final class MapObservationClock {
 record Clock(Object connection,String epoch,long tick,int interval,long received){long upper(){return Math.addExact(tick,interval);}}
 private static final Map<String,Clock> CLOCKS=new HashMap<>();
 private static Object activeConnection;private static String activeDimension;
 static synchronized void activate(Object connection,String dimension){
  if(connection!=activeConnection||!Objects.equals(dimension,activeDimension)){CLOCKS.clear();activeConnection=connection;activeDimension=dimension;}
 }
 static synchronized void receive(Object connection,String epoch,String dimension,long tick,int interval,long now){
  if(connection==null||epoch==null||epoch.isBlank()||epoch.length()>128||dimension==null||dimension.length()>256||tick<0||tick>Long.MAX_VALUE-20||interval<1||interval>20)return;
  var prior=CLOCKS.get(dimension);
  if(prior!=null&&prior.connection()==connection&&prior.epoch().equals(epoch)&&tick<prior.tick()){CLOCKS.remove(dimension);return;}
  CLOCKS.put(dimension,new Clock(connection,epoch,tick,interval,now));
 }
 static synchronized Clock get(Object connection,String dimension,long now){
  var value=CLOCKS.get(dimension);
  return value!=null&&value.connection()==connection&&now>=value.received()&&now-value.received()<=1_000_000_000L?value:null;
 }
 static synchronized void clear(){CLOCKS.clear();activeConnection=null;activeDimension=null;}
 private MapObservationClock(){}
}
