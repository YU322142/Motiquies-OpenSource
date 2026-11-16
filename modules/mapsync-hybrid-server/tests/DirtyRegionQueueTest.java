package com.mapsyncer.server;
import java.nio.file.*;
import java.util.concurrent.*;
public final class DirtyRegionQueueTest {
    static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
    static final String O="minecraft:overworld",N="minecraft:the_nether";
    static DirtyRegionQueue queue(int cap){return new DirtyRegionQueue(cap,3,30,5);}
    public static void main(String[] args)throws Exception{
        var q=queue(16);var a=DirtyRegionQueue.Key.fromChunk(O,-1,-33);check(a.regionX()==-1&&a.regionZ()==-2,"negative chunk to region");
        q.mark(a,0);q.mark(a,2);check(q.size()==1,"coalesce same region");check(q.take(4,false,false)==null,"quiet debounce");
        var first=q.take(5,false,false);check(first!=null&&!first.fullDimension(),"ready after quiet");check(q.take(100,false,false)==null,"one in flight");
        q.mark(a,6);q.finish(first,true,7);check(q.size()==1,"new save during work retained");
        var newer=q.take(9,false,false);check(newer.revision()>first.revision(),"new revision");q.finish(first,true,10);check(q.size()==1,"stale completion cannot drop newer work");q.finish(newer,true,10);check(q.size()==0,"newest completion removes work");
        q.mark(a,20);check(q.take(25,true,false)==null&&q.take(25,false,true)==null,"pressure and native generation defer");
        var fail=q.take(25,false,false);q.finish(fail,false,25);check(q.take(29,false,false)==null,"failed work backs off");var retry=q.take(30,false,false);check(retry!=null,"retry retained");q.defer(retry,30);check(q.take(30,false,false)==null,"busy does not spin");q.finish(q.take(31,false,false),true,31);
        q.mark(a,40);for(long t=42;t<=68;t+=2)q.mark(a,t);check(q.take(69,false,false)==null,"continuous changes still debounce");var max=q.take(70,false,false);check(max!=null,"max wait prevents starvation");q.finish(max,true,70);
        var negative=queue(2);negative.mark(a,-100);check(negative.take(-97,false,false)!=null,"nanoTime may have negative origin");
        q.mark(DirtyRegionQueue.Key.fromChunk(O,0,0),80);q.mark(DirtyRegionQueue.Key.fromChunk(N,0,0),80);check(q.size()==2,"dimension isolation");
        Path root=Path.of(args[0]);Files.createDirectories(root);Path file=root.resolve("pending.bin");q.save(file);var loaded=DirtyRegionQueue.load(file,16,3,30,5,100);check(loaded.size()==2,"restart replay");
        var inFlight=q.take(100,false,false);q.save(file);loaded=DirtyRegionQueue.load(file,16,3,30,5,100);check(loaded.size()==2&&loaded.take(100,false,false)!=null,"in-flight work also durable");
        q=queue(2);q.mark(DirtyRegionQueue.Key.fromChunk(O,0,0),0);q.mark(DirtyRegionQueue.Key.fromChunk(N,0,0),0);q.mark(DirtyRegionQueue.Key.fromChunk(O,64,0),1);
        var work=q.take(4,false,false);boolean full=work.fullDimension();q.finish(work,true,4);work=q.take(4,false,false);full|=work.fullDimension();check(full,"overflow becomes conservative full dimension, never lost save");
        var saved=queue(16);var pending=new CompletableFuture<Void>();SavedWriteTracker.track(pending,saved,a,()->100);check(saved.size()==0,"serialization/queued IO is not persisted");pending.complete(null);check(saved.size()==1,"successful native future marks dirty");
        var expansion=queue(4);expansion.markDimension(O,0);var fullWork=expansion.take(3,false,false);
        var newlyCreated=DirtyRegionQueue.Key.fromChunk(O,320,0);expansion.mark(newlyCreated,4);
        check(expansion.expandDimension(fullWork,java.util.List.of(a),5),"full marker expands without converting whole dimension at once");
        check(expansion.size()==2,"save creating a new MCA during listing is retained");
        var one=expansion.take(5,false,false);check(one!=null&&!one.fullDimension(),"expanded work is one region");expansion.finish(one,true,5);
        one=expansion.take(5,false,false);check(one!=null&&!one.fullDimension(),"second region survives");expansion.finish(one,true,5);
        check(expansion.size()==0,"expanded regions drain individually");
        var bad=new CompletableFuture<Void>();SavedWriteTracker.track(bad,saved,DirtyRegionQueue.Key.fromChunk(N,0,0),()->101);bad.completeExceptionally(new java.io.IOException("QA"));check(saved.size()==1,"failed save never marks committed data");
        saved.close();SavedWriteTracker.track(CompletableFuture.completedFuture(null),saved,a,()->102);check(saved.size()==1,"old lifecycle cannot accept callbacks");
        byte[] good=Files.readAllBytes(file);Files.write(file,new byte[]{1,2,3});boolean corrupt=false;try{DirtyRegionQueue.load(file,16,3,30,5,100);}catch(java.io.IOException expected){corrupt=true;}check(corrupt,"corrupt journal rejected without deletion");Files.write(file,good);
        System.out.println("PASS dirty queue: post-save futures, coalescing, coordinates, dimensions, debounce, max-wait, in-flight revisions, stale ACK, retry, pressure, restart, overflow, corrupt journal");
    }
}
