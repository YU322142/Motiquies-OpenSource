package com.mapsyncer.server;
import java.nio.file.*;
import java.util.*;
public final class DownloadCustomTest {
    static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
    public static void main(String[] args)throws Exception{
        Path file=Path.of(args[0]).resolve("custom.properties");
        var p=new DownloadPolicy(file);UUID a=UUID.randomUUID();
        p.selectCustom(a,0);var job=p.begin(a);
        check(job.radius==0&&!job.large(),"one region stays small");
        check(DownloadPolicy.allows(job.mode,job.radius,"null",-2,-1,"null",-2,-1),"negative center included");
        check(!DownloadPolicy.allows(job.mode,job.radius,"null",-2,-1,"null",-1,-1),"radius zero cannot expand");
        p.selectCustom(a,32);job=p.begin(a);check(job.large(),"65x65 is queued large");
        check(DownloadPolicy.allows(job.mode,job.radius,"null",-2,-1,"null",30,31),"inclusive far edge");
        check(!DownloadPolicy.allows(job.mode,job.radius,"null",-2,-1,"null",31,31),"outside excluded");
        check(!DownloadPolicy.allows(job.mode,job.radius,"null",-2,-1,"DIM1",-2,-1),"no other dimension");
        p.select(a,DownloadPolicy.Mode.PAUSED);p=new DownloadPolicy(file);p.resume(a);
        check(p.mode(a)==DownloadPolicy.Mode.CUSTOM&&p.radius(a)==32,"radius survives pause/restart/resume");
        for(int invalid:new int[]{-1,33,Integer.MAX_VALUE}){
            boolean rejected=false;try{p.selectCustom(a,invalid);}catch(IllegalArgumentException expected){rejected=true;}
            check(rejected&&p.radius(a)==32,"invalid radius never mutates preference");
        }
        p.selectCustom(a,1);check(!p.begin(a).large(),"small custom does not queue");
        System.out.println("PASS custom: bounds, negative coords, dimension, persistence, pause/resume, large queue");
    }
}
