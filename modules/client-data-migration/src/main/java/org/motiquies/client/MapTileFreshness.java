package org.motiquies.client;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.motiquies.map.MapSourceTimes;

/** Content-bound per-Minecraft-chunk provenance. Unknown local terrain is never discarded. */
final class MapTileFreshness {
 record Stamp(String epoch,long tick,boolean local,String hash){}
 static boolean keepLocal(boolean localPresent,Stamp local,String actualHash,boolean serverPresent,MapSourceTimes source,int index){
  if(!localPresent)return false;
  if(local==null||!Objects.equals(local.hash(),actualHash))return true;
  if(source==null||source.epoch().isEmpty()||source.tick(index)<0)return true;
  // Proven, unchanged imported terrain remains server-owned across cache rebuilds.
  // Local exploration from an incomparable world epoch remains protected.
  if(!source.epoch().equals(local.epoch()))return local.local();
  // Server-origin tiles may be explicitly cleared by a current source tombstone.
  return local.local()?source.tick(index)<=local.tick():source.tick(index)<local.tick();
 }
 static Stamp[] read(Path file)throws IOException{
  Stamp[] stamps=new Stamp[1024];if(!Files.exists(file))return stamps;
  if(Files.isSymbolicLink(file)||Files.size(file)>512*1024)throw new IOException("Unsafe freshness sidecar");
  try(var d=new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))){
   if(d.readInt()!=0x4d4c4631)throw new IOException("Unknown local provenance");
   for(int i=0;i<1024;i++)if(d.readBoolean()){
    String epoch=d.readUTF();long tick=d.readLong();boolean local=d.readBoolean();String hash=d.readUTF();
    if(epoch.length()>128||tick<0||!hash.matches("[0-9a-f]{64}"))throw new IOException("Invalid local provenance");
    stamps[i]=new Stamp(epoch,tick,local,hash);
   }
   if(d.read()!=-1)throw new IOException("Trailing local provenance");
  }return stamps;
 }
 static void write(Path file,Stamp[] stamps)throws IOException{
  if(stamps.length!=1024)throw new IllegalArgumentException();Files.createDirectories(file.getParent());
  if(Files.isSymbolicLink(file)||Files.isSymbolicLink(file.getParent()))throw new IOException("Unsafe freshness path");
  Path temp=Files.createTempFile(file.getParent(),".motiquies-freshness-",".tmp");
  try{
   try(var d=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))){
    d.writeInt(0x4d4c4631);for(Stamp s:stamps){d.writeBoolean(s!=null);if(s!=null){d.writeUTF(s.epoch());d.writeLong(s.tick());d.writeBoolean(s.local());d.writeUTF(s.hash());}}
   }
   try(var f=java.nio.channels.FileChannel.open(temp,StandardOpenOption.WRITE)){f.force(true);}
   Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
  }finally{Files.deleteIfExists(temp);}
 }
 /** Stage a complete replacement without discarding the provenance for the current ZIP. */
 static void stage(Path source,Stamp[] stamps)throws IOException{
  if(Files.exists(pending(source),LinkOption.NOFOLLOW_LINKS)){
   if(!Files.isRegularFile(pending(source),LinkOption.NOFOLLOW_LINKS))throw new IOException("Unsafe pending provenance path");
   var actual=Files.exists(source)?NativeMapMerge.unzip(Files.readAllBytes(source)).region():new NativeXaeroRegionCodec.Region(new NativeXaeroRegionCodec.Tile[1024]);
   recover(source,actual);
   // The previous journal can be the only durable provenance for the actual ZIP.
   // Do not replace it until matching old candidates were durably moved to main.
   if(Files.exists(pending(source),LinkOption.NOFOLLOW_LINKS))throw new IOException("Previous provenance recovery is not durable; source write deferred");
  }
  write(pending(source),stamps);
 }
 @FunctionalInterface interface SourceWrite<R>{R write()throws IOException;}
 /** A committed ZIP must complete its native handoff even if metadata repair is deferred. */
 static <R> R commit(Path source,Stamp[] stamps,SourceWrite<R> writer,java.util.function.Consumer<IOException> deferred)throws IOException{
  stage(source,stamps);R receipt=writer.write();if(receipt==null)return null;
  try{finish(source,stamps);}catch(IOException failure){deferred.accept(failure);}
  return receipt;
 }
 /** Only call after a successful ZIP replacement. A failed finish leaves recovery possible. */
 static void finish(Path source,Stamp[] stamps)throws IOException{
  write(sidecar(source),stamps);Files.deleteIfExists(pending(source));
 }
 /** Resolve either crash window by content, including native ZIP/palette re-encoding. */
 static Stamp[] recover(Path source,NativeXaeroRegionCodec.Region actual)throws IOException{
  var main=readCandidate(sidecar(source));var journal=readCandidate(pending(source));var result=new Stamp[1024];
  for(int i=0;i<1024;i++){
   var tile=actual.tile(i);if(tile==null)continue;
   String hash=NativeXaeroRegionCodec.fingerprint(tile);
   Stamp a=main[i],b=journal[i];String legacy=null;
   if((a!=null&&!a.hash().equals(hash))||(b!=null&&!b.hash().equals(hash)))legacy=NativeXaeroRegionCodec.legacyFingerprint(tile);
   a=matching(a,hash,legacy);b=matching(b,hash,legacy);
   result[i]=prefer(a,b);
  }
  if(Files.isRegularFile(pending(source),LinkOption.NOFOLLOW_LINKS)){
   // Failure to repair metadata must not hide the matching in-memory candidates or
   // cause a successful source commit to be retried with stale native memory.
   try{finish(source,result);}catch(IOException repairDeferred){/* Keep journal for the next native boundary. */}
  }
  return result;
 }
 /** Upgrade only a content-proven legacy hash; never infer provenance from a mismatch. */
 private static Stamp matching(Stamp stamp,String canonical,String legacy){
  if(stamp==null)return null;
  if(stamp.hash().equals(canonical))return stamp;
  return stamp.hash().equals(legacy)?new Stamp(stamp.epoch(),stamp.tick(),stamp.local(),canonical):null;
 }
 private static Stamp[] readCandidate(Path file){
  try{return read(file);}catch(IOException malformedOrUnavailable){return new Stamp[1024];}
 }
 private static Stamp prefer(Stamp main,Stamp journal){
  if(main==null)return journal;if(journal==null)return main;
  if(main.epoch().equals(journal.epoch())){
   if(main.tick()!=journal.tick())return main.tick()>journal.tick()?main:journal;
   return main.local()?main:journal;
  }
  // Across epochs a proven local observation cannot be demoted by a server import.
  if(main.local()!=journal.local())return main.local()?main:journal;
  return main.local()?main:journal;
 }
 static Path sidecar(Path source){return source.resolveSibling(source.getFileName()+".motiquies-freshness");}
 static Path pending(Path source){return source.resolveSibling(source.getFileName()+".motiquies-freshness.pending");}
 private MapTileFreshness(){}
}
