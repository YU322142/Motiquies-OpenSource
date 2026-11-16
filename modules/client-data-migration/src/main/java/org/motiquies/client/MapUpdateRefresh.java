package org.motiquies.client;
/** Coalesced hints only. They never authorize a download or interrupt native/manual work. */
final class MapUpdateRefresh {
 private Object connection;
 private long seen,first,last,dispatched;
 private boolean pending,hasDispatched;
 void cancel(){pending=false;}
 void reset(){connection=null;seen=0;pending=false;hasDispatched=false;}
 void receive(Object source,long revision,boolean allowed,long now){
  if(source==null||!allowed||revision<=0)return;
  if(connection!=source){reset();connection=source;}
  if(revision<=seen)return;seen=revision;
  if(!pending)first=now;last=now;pending=true;
 }
 boolean take(Object source,boolean allowed,boolean busy,boolean manual,long now){
  if(source==null||source!=connection){reset();return false;}
  if(!allowed){cancel();return false;}
  if(!pending||busy||manual||(now-last<500&&now-first<5000)||(hasDispatched&&now-dispatched<3000))return false;
  pending=false;dispatched=now;hasDispatched=true;return true;
 }
}
