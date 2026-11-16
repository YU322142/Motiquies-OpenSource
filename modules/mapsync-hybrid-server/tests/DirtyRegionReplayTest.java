package com.mapsyncer.server;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
public final class DirtyRegionReplayTest {
 static final String DIM="minecraft:overworld";
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 static DirtyRegionQueue.Key key(int x){return new DirtyRegionQueue.Key(DIM,x,0);}
 static DirtyRegionQueue queue(int cap){return new DirtyRegionQueue(cap,0,0,5);}
 static void largeRestart(Path root,int count,int cap)throws Exception{
  var listed=new ArrayList<DirtyRegionQueue.Key>();var q=queue(cap);for(int i=0;i<count;i++){listed.add(key(i));q.mark(key(i),0);}Collections.shuffle(listed,new Random(53));
  int[] completions=new int[count],legalRepeats=new int[count];int completed=0,restarts=0,batches=0;long clock=1;
  while(q.size()>0){
   var work=q.take(clock++,false,false);check(work!=null,"large replay queue progresses");
   if(work.fullDimension()){check(q.expandDimension(work,listed,clock),"bounded replay expansion");batches++;continue;}
   int i=work.key().regionX();completions[i]++;check(completions[i]<=1+legalRepeats[i],"replay must not repeat acknowledged cursor prefix: "+i);q.finish(work,true,clock);completed++;
   if(restarts<2&&completed==(restarts==0?17:34)){
    var inFlight=q.take(clock++,false,false);check(inFlight!=null&&!inFlight.fullDimension(),"journal captures an actual in-flight region");
    Path file=root.resolve(count+"-restart-"+restarts+".bin");q.save(file);long previousRevision=q.revision();q.close();
    var recovered=DirtyRegionQueue.load(file,cap,0,0,5,clock);var target=queue(cap);
    int changed=-1;for(int k=0;k<count;k++)if(completions[k]>0){changed=k;break;}
    check(changed>=0,"known acknowledged region for new save");target.mark(key(changed),clock);legalRepeats[changed]++;
    int before=recovered.size();recovered.replayInto(target,clock);check(recovered.size()==before,"replay does not consume frozen recovery queue");
    check(target.revision()>previousRevision,"restored jobs get fresh revision beyond old epoch");
    int pending=target.size();target.finish(inFlight,true,clock);check(target.size()==pending,"stale old-owner completion cannot ACK replayed pending");
    q=target;restarts++;
   }
   check(clock<count*3L+1000,"no endless full marker/prefix replay");
  }
  check(restarts==2&&completed==count+2,"all real queue identities plus exactly two fresh saves drain");
  for(int i=0;i<count;i++)check(completions[i]==1+legalRepeats[i],"no replayed or early save omitted");
  System.out.println("PASS replay synthetic regions="+count+" cap="+cap+" two-restarts completed="+completed+" batches="+batches);
 }
 public static void run(Path root)throws Exception{
  Files.createDirectories(root);Path v1=root.resolve("v1.bin");
  try(var out=new DataOutputStream(Files.newOutputStream(v1))){out.writeInt(0x4d514431);out.writeInt(1);out.writeLong(42);out.writeInt(1);out.writeBoolean(false);out.writeUTF(DIM);out.writeInt(1);out.writeInt(0);out.writeLong(41);}
  var restored=DirtyRegionQueue.load(v1,32,0,0,5,100);var target=queue(32);target.mark(key(9),100);restored.replayInto(target,100);check(target.size()==2&&target.revision()>42,"v1 recovery preserves early committed save and fresh revision");
  var corrupt=root.resolve("corrupt.bin");Files.write(corrupt,new byte[]{1,2});boolean bad=false;try{DirtyRegionQueue.load(corrupt,32,0,0,5,100).replayInto(target,100);}catch(IOException expected){bad=true;}check(bad&&target.size()==2,"corrupt recovery cannot replace early-event queue");
  var source=queue(2048);for(int i=0;i<512;i++)source.mark(key(i),0);Path journal=root.resolve("race.bin");source.save(journal);var recovered=DirtyRegionQueue.load(journal,2048,0,0,5,1);var sameTarget=queue(2048);sameTarget.mark(key(-1),1);var begin=new CountDownLatch(1);
  Thread marks=new Thread(()->{try{begin.await();for(int i=512;i<1024;i++)sameTarget.mark(key(i),1);}catch(InterruptedException e){throw new RuntimeException(e);}});marks.start();begin.countDown();recovered.replayInto(sameTarget,1);marks.join();check(sameTarget.size()==1025,"concurrent replay preserves before/during/new storage marks");
  // v2 rescan survives recovery when a behind-cursor save had no free region slot.
  var rescan=queue(2);var small=new ArrayList<DirtyRegionQueue.Key>();for(int i=0;i<5;i++)small.add(key(i));rescan.markDimension(DIM,0);var full=rescan.take(0,false,false);rescan.expandDimension(full,small,0);rescan.mark(key(-1),0);small.add(key(-1));
  Path rescanFile=root.resolve("rescan-v2.bin");rescan.save(rescanFile);var rescanTarget=queue(2);DirtyRegionQueue.load(rescanFile,2,0,0,5,1).replayInto(rescanTarget,1);var seen=new HashSet<Integer>();int guard=0;
  while(rescanTarget.size()>0&&guard++<40){var w=rescanTarget.take(guard,false,false);check(w!=null,"rescan replay progress");if(w.fullDimension())rescanTarget.expandDimension(w,small,guard);else{seen.add(w.key().regionX());rescanTarget.finish(w,true,guard);}}
  check(rescanTarget.size()==0&&seen.containsAll(List.of(-1,0,1,2,3,4)),"restored rescan cannot lose capacity-contended behind-cursor save");
  var freshFull=queue(2);freshFull.markDimension(DIM,1);DirtyRegionQueue.load(rescanFile,2,0,0,5,1).replayInto(freshFull,1);seen.clear();guard=0;
  while(freshFull.size()>0&&guard++<40){var w=freshFull.take(guard,false,false);check(w!=null,"new full marker replay progress");if(w.fullDimension())freshFull.expandDimension(w,small,guard);else{seen.add(w.key().regionX());freshFull.finish(w,true,guard);}}
  check(freshFull.size()==0&&seen.containsAll(List.of(-1,0,1,2,3,4)),"new explicit full marker conservatively wins recovered cursor");
  largeRestart(root,65537,65536);largeRestart(root,100000,1024);
 }
 public static void main(String[] args)throws Exception{run(Path.of(args[0]));}
}
