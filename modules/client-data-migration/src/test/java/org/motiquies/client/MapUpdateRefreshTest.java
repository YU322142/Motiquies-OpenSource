package org.motiquies.client;
public final class MapUpdateRefreshTest {
 public static void main(String[] args){
  Object a=new Object(),b=new Object();var s=new MapUpdateRefresh();
  s.receive(a,1,true,0);s.receive(a,2,true,300);
  check(!s.take(a,true,false,false,799),"burst debounce");check(!s.take(a,true,true,false,800),"native hash/write/transfer busy");
  check(!s.take(a,true,false,true,800),"manual request has priority");check(s.take(a,true,false,false,800),"one coalesced idle refresh");
  s.receive(a,2,true,900);check(!s.take(a,true,false,false,5000),"duplicate revision ignored");
  s.receive(a,3,true,1000);check(!s.take(a,true,false,false,2000),"rate cap");check(s.take(a,true,false,false,3800),"bounded later refresh");
  s.receive(a,4,true,4000);check(!s.take(a,false,false,false,5000),"pause clears refresh");check(!s.take(a,true,false,false,6000),"resume cannot resurrect paused notification");
  s.receive(a,5,true,7000);check(!s.take(b,true,false,false,9000),"new route/connection cannot run old notification");
  s.receive(b,1,true,10000);check(s.take(b,true,false,false,10500),"server restart/new connection revision resets");
  for(int n=2;n<30;n++)s.receive(b,n,true,11000+n*200);
  check(s.take(b,true,false,false,17000),"continuous saves cannot starve refresh");
  s.receive(b,30,false,18000);check(!s.take(b,true,false,false,19000),"unapproved notifications do not enable downloads");
  System.out.println("PASS map updates: coalescing, native idle, manual priority, rate cap, pause, connection, revision, max wait");
 }
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
}
