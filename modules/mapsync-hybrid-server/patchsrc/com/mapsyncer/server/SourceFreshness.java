package com.mapsyncer.server;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import org.motiquies.map.MapSourceTimes;
import org.motiquies.map.NativeXaeroRegionCodec;

/** Provenance is allowed only for owned dirty snapshots or explicitly frozen offline inputs. */
public final class SourceFreshness implements AutoCloseable {
 public static final String IMMUTABLE_INPUT_PROPERTY="motiquies.mapsync.immutableInputRoot";
 private static final int MAX_LAYERS=128;
 private static final ThreadLocal<SourceFreshness> CURRENT=new ThreadLocal<>();
 private record Publication(MapSourceTimes source,String[] fingerprints){}
 private record FileState(long size,java.nio.file.attribute.FileTime modified,Object key,String sha256){
  boolean sameAttributes(FileState other){return other!=null&&size==other.size&&modified.equals(other.modified)&&Objects.equals(key,other.key);}
 }
 private record Snapshot(Map<Path,FileState> files,java.nio.file.attribute.FileTime directoryModified,Object directoryKey){}
 private static final class Input {
  final Path directory;final int rx,rz;final MapSourceTimes times;final Snapshot original;
  final Map<Path,Publication> publications=new HashMap<>();
  Input(Path directory,int rx,int rz,MapSourceTimes times,Snapshot original){this.directory=directory;this.rx=rx;this.rz=rz;this.times=times;this.original=original;}
  void verifyComplete()throws IOException {
   if(!original.equals(snapshot(directory,rx,rz)))throw new IOException("Frozen map input changed; publication deferred");
  }
  void verifyPublication()throws IOException{
   // Existing files only: directory mtime detects an added/removed MCC, avoiding 1024 absent stats per layer.
   for(var entry:original.files().entrySet())if(!entry.getValue().sameAttributes(attributes(entry.getKey())))throw new IOException("Frozen map input attributes changed; publication deferred");
   var dir=Files.readAttributes(directory,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
   if(!original.directoryModified().equals(dir.lastModifiedTime())||!Objects.equals(original.directoryKey(),dir.fileKey()))throw new IOException("Frozen map input directory changed; publication deferred");
  }
 }
 private final SourceFreshness previous;
 private final Input input;
 private SourceFreshness(Input input){previous=CURRENT.get();this.input=input;CURRENT.set(this);}
 public static MapSourceTimes current(){var scope=CURRENT.get();return scope==null?null:scope.input.times;}
 /** Captures a bounded semantic baseline before EmptyRegionSupport removes a published layer. */
 public static void remember(Path previousFile)throws IOException{
  var scope=CURRENT.get();if(scope!=null)scope.baseline(previousFile);
 }
 private Publication baseline(Path file)throws IOException{
  Path key=file.toAbsolutePath().normalize();
  if(!key.getFileName().toString().equals(input.rx+"_"+input.rz+".zip"))throw new IOException("Publication belongs to another source region");
  if(!input.publications.containsKey(key)){
   if(input.publications.size()>=MAX_LAYERS)throw new IOException("Too many publication layers for one source region");
   input.publications.put(key,readPublication(key));
  }
  return input.publications.get(key);
 }
 /** Source tick advances only when that layer's semantic tile changes, including empty tombstones. */
 public static MapSourceTimes forPublication(Path previousFile,byte[] raw)throws IOException {
  var scope=CURRENT.get();if(scope==null)return null;
  scope.input.verifyPublication();var fresh=scope.input.times;var old=scope.baseline(previousFile);
  if(old==null||!fresh.epoch().equals(old.source().epoch()))return fresh;
  var after=NativeXaeroRegionCodec.decode(raw);long[] ticks=fresh.ticks();
  for(int i=0;i<1024;i++){
   var tile=after.tile(i);String hash=tile==null?null:NativeXaeroRegionCodec.fingerprint(tile);
   if(Objects.equals(old.fingerprints()[i],hash))ticks[i]=old.source().tick(i);
  }
  return new MapSourceTimes(fresh.epoch(),ticks);
 }
 private static Publication readPublication(Path file)throws IOException{
  if(!Files.exists(file))return null;
  if(Files.isSymbolicLink(file)||Files.size(file)>32L*1024*1024)throw new IOException("Unsafe previous map publication");
  try(var zip=new java.util.zip.ZipFile(file.toFile())){
   var nativeEntry=zip.getEntry("region.xaero");var timesEntry=zip.getEntry(MapSourceTimes.ENTRY);
   if(nativeEntry==null||timesEntry==null)return null;
   byte[] raw,times;
   try(var in=zip.getInputStream(nativeEntry)){raw=in.readNBytes(NativeXaeroRegionCodec.MAX_RAW_BYTES+1);}
   try(var in=zip.getInputStream(timesEntry)){times=in.readNBytes(9001);}
   var source=MapSourceTimes.decode(times);var region=NativeXaeroRegionCodec.decode(raw);String[] hashes=new String[1024];
   for(int i=0;i<1024;i++)if(region.tile(i)!=null)hashes[i]=NativeXaeroRegionCodec.fingerprint(region.tile(i));
   return new Publication(source,hashes);
  }catch(java.util.zip.ZipException malformed){throw new IOException("Previous publication ZIP corrupt; retain pending region",malformed);}
 }
 public static SourceFreshness open(Path regionDir,int rx,int rz,Path cache)throws IOException {
  Path directory=regionDir.toAbsolutePath().normalize().toRealPath();
  var existing=CURRENT.get();
  if(existing!=null){
   if(!existing.input.directory.equals(directory)||existing.input.rx!=rx||existing.input.rz!=rz)throw new IOException("Nested provenance scope belongs to another region");
   return new SourceFreshness(existing.input);
  }
  requireFrozen(directory,cache);
  Snapshot original=snapshot(directory,rx,rz);
  String epoch=epoch(cache);
  long[] ticks=new long[1024];Arrays.fill(ticks,-1);
  Path mca=directory.resolve("r."+rx+"."+rz+".mca");
  if(Files.exists(mca)){
   // RegionFile.close pads the source and forces its channel even for reads. Never open it here.
   try(var region=new RandomAccessFile(mca.toFile(),"r")){
    if(region.length()<8192)throw new IOException("Truncated frozen MCA header");
    for(int x=0;x<32;x++)for(int z=0;z<32;z++){
      var tag=chunk(region,directory,rx,rz,x,z);if(tag==null)continue;
      if(tag.contains("LastUpdate",net.minecraft.nbt.Tag.TAG_LONG))ticks[x*32+z]=Math.max(-1,tag.getLong("LastUpdate"));
    }
   }
  }
  var input=new Input(directory,rx,rz,new MapSourceTimes(epoch,ticks),original);input.verifyPublication();
  return new SourceFreshness(input);
 }
 /** Background initialization only; callers cache this value before publishing clocks from a tick thread. */
 public static String epoch(Path cache)throws IOException{
  Files.createDirectories(cache);Path identity=cache.resolve("motiquies-source-epoch.txt");
  synchronized(SourceFreshness.class){
   if(!Files.exists(identity)){try{Files.writeString(identity,UUID.randomUUID().toString(),StandardOpenOption.CREATE_NEW);}catch(FileAlreadyExistsException ignored){}}
   if(Files.isSymbolicLink(identity)||Files.size(identity)>128)throw new IOException("Unsafe map source identity");
   String value=Files.readString(identity).strip();if(value.isEmpty()||value.length()>128)throw new IOException("Empty/invalid map source identity");
   return value;
  }
 }
 private static CompoundTag chunk(RandomAccessFile file,Path directory,int rx,int rz,int x,int z)throws IOException{
  file.seek(4L*(x+z*32));int location=file.readInt();if(location==0)return null;
  long start=(long)(location>>>8)*4096;int sectors=location&255;
  if(sectors==0||start<8192||start+5>file.length())throw new IOException("Invalid frozen MCA chunk offset");
  file.seek(start);int length=file.readInt(),compression=file.readUnsignedByte();
  var codec=RegionFileVersion.fromId(compression&127);if(codec==null)throw new IOException("Unsupported native MCA compression");
  InputStream payload;
  if((compression&128)!=0){
   if(length!=1)throw new IOException("Invalid external MCA chunk length");
   Path external;
   try{external=directory.resolve("c."+Math.addExact(Math.multiplyExact(rx,32),x)+"."+Math.addExact(Math.multiplyExact(rz,32),z)+".mcc");}
   catch(ArithmeticException overflow){throw new IOException("External MCA coordinates overflow",overflow);}
   if(!Files.isRegularFile(external,LinkOption.NOFOLLOW_LINKS)||Files.size(external)>64L*1024*1024)throw new IOException("Unsafe external MCA chunk");
   payload=Files.newInputStream(external);
  }else{
   if(length<1||length>sectors*4096-4||start+4+length>file.length())throw new IOException("Invalid frozen MCA chunk length");
   byte[] raw=new byte[length-1];file.readFully(raw);payload=new ByteArrayInputStream(raw);
  }
  try(var compressed=payload;var data=new DataInputStream(codec.wrap(compressed))){return NbtIo.read(data,NbtAccounter.create(64L*1024*1024));}
 }
 private static void requireFrozen(Path directory,Path cache)throws IOException{
  Path dirtyRoot=cache.toAbsolutePath().normalize().resolve("motiquies-dirty-inputs");
  if(Files.isDirectory(dirtyRoot)&&!Files.isSymbolicLink(dirtyRoot)){
   Path owned=dirtyRoot.toRealPath();
   if(owned.equals(directory.getParent())&&directory.getFileName().toString().startsWith("dirty-")&&!Files.isSymbolicLink(directory))return;
  }
  String declared=System.getProperty(IMMUTABLE_INPUT_PROPERTY,"");
  if(!declared.isBlank()){
   Path requested=Path.of(declared).toAbsolutePath().normalize();
   if(Files.isDirectory(requested)&&!Files.isSymbolicLink(requested)&&directory.startsWith(requested.toRealPath()))return;
  }
  throw new IOException("Live MCA input is not eligible for provenance; use a native dirty snapshot or an offline frozen root declared by -D"+IMMUTABLE_INPUT_PROPERTY);
 }
 /** Hashes only this region and its bounded 1024 possible external chunk files; never walks the world. */
 private static Snapshot snapshot(Path directory,int rx,int rz)throws IOException{
  var before=Files.readAttributes(directory,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
  Map<Path,FileState> hashes=new HashMap<>();
  hash(hashes,directory.resolve("r."+rx+"."+rz+".mca"));
  try{
   int startX=Math.multiplyExact(rx,32),startZ=Math.multiplyExact(rz,32);
   for(int x=0;x<32;x++)for(int z=0;z<32;z++)hash(hashes,directory.resolve("c."+Math.addExact(startX,x)+"."+Math.addExact(startZ,z)+".mcc"));
  }catch(ArithmeticException overflow){throw new IOException("Map input coordinates overflow",overflow);}
  var after=Files.readAttributes(directory,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
  if(!before.lastModifiedTime().equals(after.lastModifiedTime())||!Objects.equals(before.fileKey(),after.fileKey()))throw new IOException("Frozen input directory changed while hashing");
  return new Snapshot(Map.copyOf(hashes),after.lastModifiedTime(),after.fileKey());
 }
 private static FileState attributes(Path file)throws IOException{
  var state=Files.readAttributes(file,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
  if(!state.isRegularFile())throw new IOException("Frozen map input contains a nonregular source file");
  return new FileState(state.size(),state.lastModifiedTime(),state.fileKey(),null);
 }
 private static void hash(Map<Path,FileState> hashes,Path file)throws IOException{
  FileState before;
  try{before=attributes(file);}catch(NoSuchFileException absent){return;}
  try{
   var digest=MessageDigest.getInstance("SHA-256");
   try(var in=Files.newInputStream(file)){byte[] buffer=new byte[65536];for(int count;(count=in.read(buffer))!=-1;)digest.update(buffer,0,count);}
   var after=attributes(file);
   if(!before.sameAttributes(after))throw new IOException("Frozen map file changed while hashing");
   hashes.put(file,new FileState(after.size(),after.modified(),after.key(),HexFormat.of().formatHex(digest.digest())));
  }catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
 }
 @Override public void close()throws IOException{
  try{if(previous==null)input.verifyComplete();}
  finally{if(previous==null)CURRENT.remove();else CURRENT.set(previous);}
 }
}
