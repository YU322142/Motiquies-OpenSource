package com.mapsyncer.server;
public final class DownloadControlRateTest {
    public static void main(String[] args){
        var r=new DownloadControlRate();
        for(int i=0;i<4;i++)if(!r.take(0))throw new AssertionError("ordinary status/start/pause/resume burst");
        for(int i=0;i<100;i++)if(r.take(0))throw new AssertionError("unbounded request flood");
        if(r.take(499_999_999L)||!r.take(500_000_000L)||r.take(500_000_000L))throw new AssertionError("two/sec refill");
        for(int i=0;i<4;i++)if(!r.take(10_000_000_000L))throw new AssertionError("idle refill");
        if(r.take(10_000_000_000L))throw new AssertionError("idle cannot bank beyond four");
        System.out.println("PASS optional controls: bounded burst, bounded sustained rate, capped idle refill");
    }
}
