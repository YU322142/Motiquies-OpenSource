package com.mapsyncer.server;
import net.minecraft.nbt.*;
import net.minecraft.resources.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class SavedRegionSnapshotTest {
    static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]).toAbsolutePath();Files.createDirectories(root);Path sentinel=root.resolve("other-data.txt");Files.writeString(sentinel,"do not touch");
        var key=DirtyRegionQueue.Key.fromChunk("minecraft:overworld",-1,-1);
        var info=new RegionStorageInfo("qa-only",ResourceKey.create(Registries.DIMENSION,ResourceLocation.withDefaultNamespace("overworld")),"chunk");
        AtomicInteger reads=new AtomicInteger();Path owned;
        try(var snapshot=SavedRegionSnapshot.capture(root,key,info,(x,z)->{
            reads.incrementAndGet();
            if((x==-1&&z==-1)||(x==-32&&z==-32)){
                CompoundTag tag=new CompoundTag();tag.putInt("xPos",x);tag.putInt("zPos",z);tag.putString("qa-source","native-reader");return CompletableFuture.completedFuture(Optional.of(tag));
            }
            return CompletableFuture.completedFuture(Optional.empty());
        },()->true,()->false,123000L)){
            owned=snapshot.directory();check(snapshot.chunks()==2&&reads.get()==1024,"exact bounded region coordinates, no extra generation");
            check(Files.getLastModifiedTime(snapshot.mca()).toMillis()==123000L,"conservative source stamp");
            try(var file=new RegionFile(info,snapshot.mca(),snapshot.directory(),false)){
                try(var input=file.getChunkDataInputStream(new ChunkPos(-1,-1))){check(input!=null,"saved chunk");var tag=NbtIo.read(input);check(tag.getInt("xPos")==-1&&tag.getString("qa-source").equals("native-reader"),"native MCA/NBT round trip");}
                check(file.getChunkDataInputStream(new ChunkPos(-2,-2))==null,"unknown chunks stay absent");
            }
        }
        check(!Files.exists(owned)&&Files.readString(sentinel).equals("do not touch"),"close cleans only owned snapshot");
        int before=reads.get();boolean cancelled=false;
        try{SavedRegionSnapshot.capture(root,key,info,(x,z)->{reads.incrementAndGet();return CompletableFuture.completedFuture(Optional.empty());},()->false,()->false,123000L);}catch(java.io.InterruptedIOException expected){cancelled=true;}
        check(cancelled&&before==reads.get(),"cancel before any IO request");
        boolean pressure=false;try{SavedRegionSnapshot.capture(root,key,info,(x,z)->CompletableFuture.completedFuture(Optional.empty()),()->true,()->true,123000L);}catch(SavedRegionSnapshot.Deferred expected){pressure=true;}
        check(pressure,"high load defers without reading source");
        boolean failed=false;try{SavedRegionSnapshot.capture(root,key,info,(x,z)->CompletableFuture.failedFuture(new java.io.IOException("QA read failure")),()->true,()->false,123000L);}catch(java.io.IOException expected){failed=true;}
        check(failed,"native IO failure is not a successful snapshot");
        try(var paths=Files.list(root)){check(paths.count()==1,"failure snapshots cleaned, external sentinel retained");}
        System.out.println("PASS native snapshot: real MCA/NBT roundtrip, negative coords, bounded native reads, absence, cancellation, pressure, failure and owned cleanup");
    }
}
