package org.motiquies.client;
import java.nio.file.*;
import java.util.Set;
public final class DhCacheJunctionTest {
    public static void main(String[] args)throws Exception {
        Path instance=Path.of(args[0]).toAbsolutePath();Path victim=Path.of(args[1]).toAbsolutePath();
        String before=Files.readString(victim);
        try {DhCacheMigration.migrate(instance,Set.of("-1921347326_Motiquies"));throw new AssertionError("Junction must be rejected");}
        catch(java.io.IOException expected){}
        if(!Files.readString(victim).equals(before))throw new AssertionError("Junction target changed");
        if(Files.exists(instance.resolve("config/motiquies-client/dh-native-v1.done")))throw new AssertionError("Failed migration was marked complete");
        System.out.println("Windows junction escape test passed");
    }
}
