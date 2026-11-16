package com.mapsyncer.server;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Private MCA input built from native storage reads, never an unlocked copy of a live MCA. */
public final class SavedRegionSnapshot implements AutoCloseable {
    @FunctionalInterface public interface Reader { CompletableFuture<Optional<CompoundTag>> read(int chunkX,int chunkZ); }
    public static final class Deferred extends IOException { Deferred(){super("Server pressure; snapshot deferred");} }
    private final Path owner,directory,mca;
    private int chunks;
    private boolean closed;
    private SavedRegionSnapshot(Path owner,DirtyRegionQueue.Key key)throws IOException{
        Files.createDirectories(owner);
        if(Files.isSymbolicLink(owner))throw new IOException("Snapshot owner cannot be a symlink");
        this.owner=owner.toRealPath();directory=Files.createTempDirectory(this.owner,"dirty-").toAbsolutePath().normalize();
        if(!directory.startsWith(this.owner)||directory.equals(this.owner))throw new IOException("Snapshot boundary");
        mca=directory.resolve("r."+key.regionX()+"."+key.regionZ()+".mca");
    }
    public Path directory(){return directory;}
    public Path mca(){return mca;}
    public int chunks(){return chunks;}
    private static void ready(BooleanSupplier active,BooleanSupplier pressure)throws IOException{
        if(Thread.currentThread().isInterrupted()||!active.getAsBoolean())throw new InterruptedIOException("Native map snapshot cancelled");
        if(pressure.getAsBoolean())throw new Deferred();
    }
    public static SavedRegionSnapshot capture(Path owner,DirtyRegionQueue.Key key,RegionStorageInfo info,Reader reader,
            BooleanSupplier active,BooleanSupplier pressure,long sourceTimestamp)throws IOException{
        ready(active,pressure);
        var snapshot=new SavedRegionSnapshot(owner,key);boolean success=false;
        try{
            int startX=Math.multiplyExact(key.regionX(),32),startZ=Math.multiplyExact(key.regionZ(),32);
            try(var region=new RegionFile(info,snapshot.mca,snapshot.directory,false)){
                // One outstanding native read bounds memory and never requests generation/loading.
                for(int z=0;z<32;z++)for(int x=0;x<32;x++){
                    ready(active,pressure);Optional<CompoundTag> data;
                    try{data=reader.read(startX+x,startZ+z).get(30,TimeUnit.SECONDS);}
                    catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException("Native map snapshot interrupted");}
                    catch(ExecutionException|TimeoutException e){throw new IOException("Native chunk storage read failed",e);}
                    if(data.isEmpty())continue;
                    try(var output=region.getChunkDataOutputStream(new ChunkPos(startX+x,startZ+z))){NbtIo.write(data.get(),output);}
                    snapshot.chunks++;
                }
            }
            ready(active,pressure);Files.setLastModifiedTime(snapshot.mca,FileTime.fromMillis(sourceTimestamp));success=true;return snapshot;
        }catch(ArithmeticException e){throw new IOException("Snapshot coordinates out of range",e);}
        finally{if(!success)snapshot.close();}
    }
    @Override public void close()throws IOException{
        if(closed)return;
        Path target=directory.toAbsolutePath().normalize();
        if(!target.startsWith(owner)||target.equals(owner)||!target.getParent().equals(owner)||Files.isSymbolicLink(target))throw new IOException("Refusing snapshot cleanup outside owned boundary");
        if(!Files.exists(target)){closed=true;return;}
        try(var paths=Files.list(target)){
            List<Path> entries=paths.toList();
            for(Path path:entries){
                String name=path.getFileName().toString();
                if(Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)||!name.matches("(?:r\\.-?\\d+\\.-?\\d+\\.mca|c\\.-?\\d+\\.-?\\d+\\.mcc)"))throw new IOException("Unexpected snapshot content retained: "+name);
            }
            for(Path path:entries)Files.deleteIfExists(path);
        }
        Files.deleteIfExists(target);closed=true;
    }
}
