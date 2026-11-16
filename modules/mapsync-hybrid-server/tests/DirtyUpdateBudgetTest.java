package com.mapsyncer.server;
public final class DirtyUpdateBudgetTest {
 public static void main(String[] args){
  var b=new DirtyUpdateBudget();check(!b.pressure(),"starts idle");
  b.sample(45);check(b.pressure(),"pause at 45ms");b.sample(40);check(b.pressure(),"hysteresis avoids flutter");
  b.sample(35);check(!b.pressure(),"resume at 35ms");b.sample(Double.NaN);check(b.pressure(),"invalid sample conservative");
  b.sample(10);check(!b.pressure(),"recover");
  check(!DirtyUpdateBudget.diskAllows(100)&&DirtyUpdateBudget.diskAllows(1024L*1024*1024),"disk headroom gate");
  System.out.println("PASS dirty budget: hysteresis, invalid sample, disk headroom");
 }
 static void check(boolean ok,String msg){if(!ok)throw new AssertionError(msg);}
}
