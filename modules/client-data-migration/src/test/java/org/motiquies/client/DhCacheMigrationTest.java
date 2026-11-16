package org.motiquies.client;

import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.util.Set;

public final class DhCacheMigrationTest {
    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("dh-migration-");
        Path target=Files.createDirectory(root.resolve("instance"));
        Path owned=put(target,"Distant_Horizons_server_data/-1921347326_Motiquies/seed@minecraft@@overworld/DistantHorizons.sqlite","SQLite format 3\0old future-marked cache");
        Path legacy=put(target,"Distant_Horizons_server_data/%E7%9B%B4%E8%BF%9E%E4%B8%8A%E6%B5%B7/seed/DistantHorizons.sqlite","SQLite format 3\0legacy cache");
        put(target,"Distant_Horizons_server_data/-1921347326_Motiquies/seed@minecraft@@overworld/DistantHorizons.sqlite-wal","old WAL");
        Path unknown=put(target,"Distant_Horizons_server_data/OtherServer/seed/DistantHorizons.sqlite","Other server");
        Path similar=put(target,"Distant_Horizons_server_data/-1921347326_MotiquiesOther/seed/DistantHorizons.sqlite","Similar name other server");
        Path personal=put(target,"xaero/minimap/Multiplayer_Any Address/dim%0/mw$1_1.txt","Keep waypoints");
        Path jei=put(target,"config/jei/world/server/Motiquies/bookmarks.json","Keep JEI");
        Path extra=put(target,"Distant_Horizons_server_data/-1921347326_Motiquies/notes.txt","User note inside cache folder");
        var names=Set.of("-1921347326_Motiquies","%E7%9B%B4%E8%BF%9E%E4%B8%8A%E6%B5%B7");
        DhCacheMigration.migrate(target,names);
        check(!Files.exists(owned) && !Files.exists(legacy),"Confirmed shared and actual legacy-name DH files must be reset");
        check(Files.readString(unknown).equals("Other server") && Files.readString(similar).equals("Similar name other server"),"Other/near-match servers must survive");
        check(Files.readString(personal).equals("Keep waypoints") && Files.readString(jei).equals("Keep JEI"),"Personal data must survive");
        check(Files.readString(extra).equals("User note inside cache folder"),"Only exact DH cache filenames may be deleted");
        Path fresh=put(target,"Distant_Horizons_server_data/-1921347326_Motiquies/seed@minecraft@@overworld/DistantHorizons.sqlite","Fresh native cache");
        DhCacheMigration.migrate(target,names);
        check(Files.readString(fresh).equals("Fresh native cache"),"Second startup must retain newly downloaded DH data");

        Path busy=Files.createDirectory(root.resolve("busy"));
        Path busyDb=put(busy,"Distant_Horizons_server_data/-1921347326_Motiquies/world/DistantHorizons.sqlite","SQLite format 3\0busy");
        try(FileChannel channel=FileChannel.open(busyDb,StandardOpenOption.WRITE);var lock=channel.lock()) {
            expectFailure(()->DhCacheMigration.migrate(busy,Set.of("-1921347326_Motiquies")),"Busy DB must defer migration");
        }
        check(Files.exists(busyDb) && !Files.exists(busy.resolve("config/motiquies-client/dh-native-v1.done")),"Failure must not mark complete or delete busy DB");
        DhCacheMigration.migrate(busy,Set.of("-1921347326_Motiquies"));
        check(!Files.exists(busyDb),"A failed migration can retry successfully after DB closes");

        Path unsafe=Files.createDirectory(root.resolve("unsafe"));
        expectFailure(()->DhCacheMigration.migrate(unsafe,Set.of("../OtherServer")),"Traversal must be rejected");
        check(!Files.exists(unsafe.resolve("config/motiquies-client/dh-native-v1.done")),"Invalid input must not mark complete");
        Path clean=Files.createDirectory(root.resolve("clean"));
        DhCacheMigration.migrate(clean,names);
        check(Files.isRegularFile(clean.resolve("config/motiquies-client/dh-native-v1.done")),"Empty install completes without personal data");
        System.out.println("DH scoped reset tests passed");
    }
    private interface Checked {void run() throws Exception;}
    private static void expectFailure(Checked action,String message) throws Exception {
        try {action.run();} catch(java.io.IOException expected){return;}
        throw new AssertionError(message);
    }
    private static Path put(Path root,String name,String body)throws Exception {
        Path f=root.resolve(name);Files.createDirectories(f.getParent());return Files.writeString(f,body);
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
