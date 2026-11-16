package org.motiquies.client;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class NativeMapWriteRequestTest {
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 public static void main(String[] args)throws Exception{
  var canceled=new NativeMapWriteRequest<String>();var mutations=new AtomicInteger();
  canceled.attempt(()->true,()->{
   // Cancellation arrives while the expensive merge is running, before journal staging.
   canceled.cancel();
   try{return new NativeMapCommit.Outcome<>(false,canceled.finalCommit(()->true,()->{mutations.incrementAndGet();return "crc";}));}
   catch(java.io.IOException impossible){throw new AssertionError(impossible);}
  });
  check(mutations.get()==0&&canceled.future().join()==null,"cancel during preparation prevents sidecar/source transaction and receipt");
  var stale=new NativeMapWriteRequest<String>();
  check(stale.finalCommit(()->false,()->{mutations.incrementAndGet();return "crc";})==null&&mutations.get()==0,"stale generation before final transaction prevents mutation");
  var staged=new NativeMapWriteRequest<String>();var stageEntered=new AtomicBoolean();
  var stagedResult=staged.finalCommit(()->true,()->{
   stageEntered.set(true);staged.cancel();
   return staged.mayCommit(()->true)?"must-not-write":null;
  });
  check(stageEntered.get()&&stagedResult==null&&staged.future().join()==null,"cancel during journal stage is rechecked before the final ZIP writer");
  var inProgress=new NativeMapWriteRequest<String>();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var finished=new CountDownLatch(1);var failure=new AtomicReference<Throwable>();
  Thread worker=new Thread(()->{
   try{inProgress.attempt(()->true,()->{
    try{return new NativeMapCommit.Outcome<>(false,inProgress.finalCommit(()->true,()->{
     entered.countDown();try{check(release.await(5,TimeUnit.SECONDS),"test must release filesystem transaction");}catch(InterruptedException e){throw new AssertionError(e);}
     mutations.incrementAndGet();return "actual-crc";
    }));}catch(java.io.IOException impossible){throw new AssertionError(impossible);}
   });}catch(Throwable error){failure.set(error);}finally{finished.countDown();}
  },"native-commit-test");worker.start();
  check(entered.await(5,TimeUnit.SECONDS),"worker claimed final transaction");
  // If finalCommit held its ownership lock over IO, this separate cancellation would block.
  var cancelDone=new CountDownLatch(1);new Thread(()->{inProgress.cancel();cancelDone.countDown();},"network-cancel-test").start();
  boolean nonblocking=cancelDone.await(1,TimeUnit.SECONDS);release.countDown();
  check(finished.await(5,TimeUnit.SECONDS)&&nonblocking,"cancel never waits for in-progress filesystem replacement");
  if(failure.get()!=null)throw new AssertionError(failure.get());
  check(mutations.get()==1&&inProgress.future().join()==null,"already claimed atomic commit can finish but cancellation suppresses stale receipt");
  check(!inProgress.mayCommit(()->true),"canceled request cannot start another source operation");
  System.out.println("NativeMapWriteRequestTest passed: merge cancellation, generation guard, nonblocking in-progress cancellation, no stale receipt");
 }
}
