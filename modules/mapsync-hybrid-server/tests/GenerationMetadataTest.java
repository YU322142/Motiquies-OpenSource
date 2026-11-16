package com.mapsyncer.server;
import java.nio.file.*;
import java.util.*;
public final class GenerationMetadataTest {
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]); Files.createDirectories(root);
        Properties baseline=new Properties();
        for (int i=0;i<50010;i++) baseline.setProperty("null/"+i+"_0","100:12345678");
        try (var out=Files.newOutputStream(root.resolve("generation_cache.properties"))) { baseline.store(out,"large baseline"); }
        Class<?> type=Class.forName("com.mapsyncer.server.GenerationCache");
        Object cache=type.getMethod("getInstance",Path.class).invoke(null,root);
        type.getMethod("update",String.class,long.class,String.class).invoke(cache,"null/new_0",200L,"abcabcab");
        Map<?,?> metadata=(Map<?,?>)type.getMethod("getAll").invoke(cache);
        DownloadPolicyTest.check(metadata.size()==50011,"native incremental update must retain full baseline metadata");
        System.out.println("PASS native metadata: >50,000-entry baseline survives native update");
    }
}
