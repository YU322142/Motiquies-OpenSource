package com.mapsyncer.server;
public final class DownloadEstimateTest {
    static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
    public static void main(String[] args){
        var e=new DownloadEstimate();
        check(e.view(256*1024,0).totalBytes()==-1,"unknown before scan");
        e.total(1024*1024,0);
        check(e.view(256*1024,0).seconds()==4,"initial rate bound");
        e.scheduled(128*1024,1_000_000_000L);
        check(e.view(256*1024,1_000_000_000L).seconds()==7,"observed slower rate");
        check(e.view(256*1024,3_000_000_000L).seconds()==21,"stall increases estimate, not false countdown");
        e.scheduled(100*1024*1024,4_000_000_000L);
        check(e.view(256*1024,4_000_000_000L).remainingBytes()==0,"progress bounded by total");
        check(e.view(256*1024,4_000_000_000L).seconds()==0,"scheduled completion");
        e.total(0,5_000_000_000L);check(e.view(1,5_000_000_000L).seconds()==0,"up to date");
        e.total(Long.MAX_VALUE/4,0);check(e.view(1,0).seconds()>0,"large total cannot wrap");
        System.out.println("PASS estimate: unknown, initial cap, measured throughput, stall, bounded progress, empty, overflow");
    }
}
