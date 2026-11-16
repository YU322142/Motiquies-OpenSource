package com.mapsyncer.server;
import java.nio.file.*;
import java.util.*;
public final class DirtyMapMetadataTest {
    static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]);Files.createDirectories(root);Path file=root.resolve("generation_cache.properties");
        Map<String,String> values=new HashMap<>();for(int i=0;i<60000;i++)values.put("null/caves/4/"+i+"_0","1234567890:12345678");
        DirtyMapMetadata.write(file,values);Properties read=new Properties();try(var in=Files.newInputStream(file)){read.load(in);}check(read.size()==60000&&read.getProperty("null/caves/4/59999_0").equals("1234567890:12345678"),"native format, no 50k truncation");
        byte[] before=Files.readAllBytes(file);boolean failed=false;
        try{DirtyMapMetadata.write(file,Map.of("new/0_0","99:aaaaaaaa"),(temp,target)->{throw new java.io.IOException("QA commit failure");});}catch(java.io.IOException expected){failed=true;}
        check(failed&&Arrays.equals(before,Files.readAllBytes(file)),"failed commit keeps complete prior index");
        try(var paths=Files.list(root)){check(paths.count()==1,"only owned temporary removed");}
        System.out.println("PASS dirty metadata: native properties format, 60000 entries retained, atomic failure keeps old index, owned cleanup");
    }
}
