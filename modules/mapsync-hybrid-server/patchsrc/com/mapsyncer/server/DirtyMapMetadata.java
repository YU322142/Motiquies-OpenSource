package com.mapsyncer.server;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Same native properties format, with write failures propagated before a dirty job is ACKed. */
public final class DirtyMapMetadata {
    @FunctionalInterface interface Commit { void replace(Path temp,Path target)throws IOException; }
    public static void write(Path target,Map<String,String> entries)throws IOException{
        write(target,entries,(temp,file)->{
            try{Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException e){Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);}
        });
    }
    static void write(Path target,Map<String,String> entries,Commit commit)throws IOException{
        target=target.toAbsolutePath().normalize();Path parent=target.getParent();Files.createDirectories(parent);
        if(Files.isSymbolicLink(target)||Files.isSymbolicLink(parent))throw new IOException("Metadata symlink rejected");
        Properties values=new Properties();values.putAll(entries);
        Path temp=Files.createTempFile(parent,".dirty-metadata-",".tmp");
        try{
            try(var out=new BufferedOutputStream(Files.newOutputStream(temp))){values.store(out,"Native MapSyncer generation cache; saved-region update");}
            try(var file=java.nio.channels.FileChannel.open(temp,StandardOpenOption.WRITE)){file.force(true);}
            commit.replace(temp,target);
        }finally{Files.deleteIfExists(temp);}
    }
    private DirtyMapMetadata(){}
}
