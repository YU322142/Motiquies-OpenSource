package com.mapsyncer.server;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
public final class SavedRegionLifecycleTest {
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 public static void run(Path root)throws Exception{
  var q=new DirtyRegionQueue(32,0,0,5);var life=new PostSaveRegions.Lifecycle<String>(q);
  Path missing=root.resolve("world/dimensions/example/new/region");Object old=new String("same"),fresh=new String("same");
  var first=life.register("example:new",old,missing,"old");
  check(!Files.exists(root)&&life.router().saved(missing.resolve("r.0.0.mca"),0,0,0),"absent region directory first native save is routed without IO");
  var scheduled=new ArrayList<PostSaveRegions.Registration<String>>();var initial=life.startReconciliation(scheduled::add);
  check(initial.size()==1&&initial.get(0)==first,"initialization handoff includes preexisting empty dimension");
  var other=life.register("example:later",new Object(),root.resolve("later/region"),"later");
  check(scheduled.size()==1&&scheduled.get(0)==other,"load after initialization schedules background reconciliation");
  check(life.register("example:later",other.owner(),other.regionDir(),"ignored")==other&&scheduled.size()==1,"duplicate load same owner is idempotent");
  AtomicInteger reads=new AtomicInteger();var inFlight=new CompletableFuture<String>();
  check(life.submit(first,()->{reads.incrementAndGet();return inFlight;})==inFlight,"current owner submits native future without waiting");
  var work=q.take(1,false,false);check(work!=null,"in-flight dirty work exists");
  check(life.unload("example:new",old)&&!life.current(first),"unload disables old context");
  check(!inFlight.isDone(),"unload does not wait for an outstanding native IO future");
  check(life.router().saved(missing.resolve("r.0.0.mca"),0,0,2),"unload retains path for final persisted writes");
  inFlight.complete("old IO completes");if(!life.current(first))q.defer(work,2);
  check(q.size()==1&&q.take(2,false,false)==null,"unloaded in-flight result is deferred, not ACKed or spun");
  var replacement=life.register("example:new",fresh,missing,"new");
  check(!life.unload("example:new",old)&&life.current(replacement),"equal-but-different old owner unload cannot remove replacement");
  boolean stale=false;try{life.submit(first,()->{reads.incrementAndGet();return CompletableFuture.completedFuture("BAD");}).join();}catch(CompletionException expected){stale=true;}
  check(stale&&reads.get()==1,"closed level is never read after unload/replacement");
  check(life.submit(replacement,()->CompletableFuture.completedFuture("new")).join().equals("new"),"new owner has its own native reader");
  work=q.take(3,false,false);check(work!=null,"pending work survives unload/reload");q.finish(work,true,3);check(q.size()==0,"replacement can finish retained job");
  life.stopReads();check(!life.current(replacement)&&life.router().saved(missing.resolve("r.1.0.mca"),32,0,4),"server stopping fences reads but accepts final native saves");
  q.close();check(!life.router().saved(missing.resolve("r.2.0.mca"),64,0,5),"only final queue close rejects storage callbacks");
  // Handoff versus concurrent dynamic registration: every owner is returned in
  // initial snapshot OR scheduled, never lost in the initialization boundary.
  var race=new PostSaveRegions.Lifecycle<Integer>(new DirtyRegionQueue(128,0,0,5));var later=new CopyOnWriteArrayList<PostSaveRegions.Registration<Integer>>();var start=new CountDownLatch(1);
  Thread registrar=new Thread(()->{try{start.await();for(int i=0;i<100;i++)race.register("example:d"+i,new Object(),root.resolve("race/d"+i+"/region"),i);}catch(InterruptedException e){throw new RuntimeException(e);}});
  registrar.start();start.countDown();var snapshot=race.startReconciliation(later::add);registrar.join();var identities=Collections.newSetFromMap(new IdentityHashMap<PostSaveRegions.Registration<Integer>,Boolean>());identities.addAll(snapshot);identities.addAll(later);
  check(identities.size()==100&&snapshot.size()+later.size()==100,"register/init handoff does not lose or double-schedule owners");
  check(!Files.exists(root),"all lifecycle/save/load/unload operations above are memory only");
  System.out.println("PASS saved lifecycle: absent first save, dynamic load, init race, owner identity, stale-read fence, deferred pending, shutdown final saves");
 }
 public static void main(String[] args)throws Exception{run(Path.of(args[0]));}
}
