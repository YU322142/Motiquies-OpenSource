package com.mapsyncer.server;
public final class DirtyUpdateBudget {
 private volatile boolean pressure;
 public void sample(double mspt){if(!Double.isFinite(mspt)||mspt>=45)pressure=true;else if(mspt<=35)pressure=false;}
 public boolean pressure(){return pressure;}
 public static boolean diskAllows(long usable){return usable>=512L*1024*1024;}
}
