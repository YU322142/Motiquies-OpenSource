package com.mapsyncer.server;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
/** Called only after native RegionFile.write/clear succeeds. No filesystem operations here. */
public final class PostSaveRegions {
 private final DirtyRegionQueue queue;
 private final ConcurrentMap<Path,String> regions=new ConcurrentHashMap<>();
 public PostSaveRegions(DirtyRegionQueue queue,Map<Path,String> regions){
  this.queue=queue;regions.forEach(this::register);
 }
 public void register(Path directory,String dimension){
  new DirtyRegionQueue.Key(dimension,0,0);Path normalized=directory.toAbsolutePath().normalize();
  regions.compute(normalized,(path,old)->{if(old!=null&&!old.equals(dimension))throw new IllegalArgumentException("Terrain path belongs to another dimension");return dimension;});
 }
 public boolean saved(Path file,int chunkX,int chunkZ,long now){
  Path path=file.toAbsolutePath().normalize();String dimension=regions.get(path.getParent());if(dimension==null)return false;
  var key=DirtyRegionQueue.Key.fromChunk(dimension,chunkX,chunkZ);
  if(!path.getFileName().toString().equals("r."+key.regionX()+"."+key.regionZ()+".mca"))return false;
  return queue.mark(key,now);
 }
 public record Registration<T>(String dimension,Object owner,Path regionDir,T value) {}
 /** Pure metadata registry. No directory probes, level reads, future waits or file IO. */
 public static final class Lifecycle<T> {
  private final PostSaveRegions router;
  private final Map<String,Registration<T>> current=new HashMap<>();
  private Consumer<Registration<T>> reconcile;
  private boolean reads=true;
  public Lifecycle(DirtyRegionQueue queue){router=new PostSaveRegions(queue,Map.of());}
  public PostSaveRegions router(){return router;}
  public synchronized Registration<T> register(String dimension,Object owner,Path regionDir,T value){
   Objects.requireNonNull(owner);Path path=regionDir.toAbsolutePath().normalize();var old=current.get(dimension);
   if(old!=null&&old.owner()==owner){if(!old.regionDir().equals(path))throw new IllegalArgumentException("Owner terrain path changed");return old;}
   router.register(path,dimension); // Publish routing first: an early callback always has a memory sink.
   var added=new Registration<T>(dimension,owner,path,value);current.put(dimension,added);
   if(reconcile!=null)reconcile.accept(added);return added;
  }
  public synchronized boolean unload(String dimension,Object owner){
   var found=current.get(dimension);if(found==null||found.owner()!=owner)return false;
   current.remove(dimension);
   // Retain the string/path route for native final writes; no closed Level is retained by it.
   return true;
  }
  public synchronized Registration<T> get(String dimension){return reads?current.get(dimension):null;}
  public synchronized boolean current(Registration<T> registration){return reads&&registration!=null&&current.get(registration.dimension())==registration;}
  public synchronized int size(){return current.size();}
  public synchronized List<Registration<T>> startReconciliation(Consumer<Registration<T>> scheduler){
   if(reconcile!=null)throw new IllegalStateException("Reconciliation already started");
   reconcile=Objects.requireNonNull(scheduler);return List.copyOf(current.values());
  }
  /** The supplier may only submit native async IO; it must never wait for its future. */
  public synchronized <R> CompletableFuture<R> submit(Registration<T> registration,Supplier<CompletableFuture<R>> request){
   if(!current(registration))return CompletableFuture.failedFuture(new IllegalStateException("Saved-region level owner no longer active"));
   return request.get();
  }
  public synchronized void stopReads(){reads=false;}
 }
}
