package com.mapsyncer.server;
/** Small authenticated-player bucket: four initial controls, then at most two per second. */
public final class DownloadControlRate {
    private double tokens=4;
    private long last;
    private boolean initialized;
    public synchronized boolean take(long now){
        if(!initialized){last=now;initialized=true;}
        long elapsed=now-last;
        if(elapsed>0){tokens=Math.min(4,tokens+elapsed/500_000_000.0);last=now;}
        if(tokens<1)return false;
        tokens--;return true;
    }
}
