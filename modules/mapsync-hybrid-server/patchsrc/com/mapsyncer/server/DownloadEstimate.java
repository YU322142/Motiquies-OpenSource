package com.mapsyncer.server;

/** Worker scheduling estimate, never a claim that a client has written these bytes. */
public final class DownloadEstimate {
    public record View(long totalBytes,long remainingBytes,long seconds) {}
    private long total=-1,scheduled,start;
    public synchronized void total(long bytes,long now){
        if(bytes<0)throw new IllegalArgumentException("negative total");
        total=bytes;scheduled=0;start=now;
    }
    public synchronized void scheduled(int bytes,long now){
        if(bytes<0)throw new IllegalArgumentException("negative progress");
        if(total>=0)scheduled=Math.min(total,scheduled+bytes);
    }
    public synchronized View view(int bytesPerSecond,long now){
        if(bytesPerSecond<=0)throw new IllegalArgumentException("invalid rate");
        if(total<0)return new View(-1,-1,-1);
        long remaining=total-scheduled;
        double rate=bytesPerSecond;
        long elapsed=now-start;
        if(scheduled>0&&elapsed>0)rate=Math.min(rate,scheduled/(elapsed/1e9));
        long seconds=remaining==0?0:(long)Math.ceil(remaining/Math.max(1,rate));
        return new View(total,remaining,seconds);
    }
}
