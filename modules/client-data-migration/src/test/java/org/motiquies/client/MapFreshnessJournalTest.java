package org.motiquies.client;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.motiquies.map.MapSourceTimes;

/** Actual ZIP/sidecar files exercise crash boundaries without a running Minecraft client. */
public final class MapFreshnessJournalTest {
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 static NativeXaeroRegionCodec.Region region(int height){
  var pixels=new NativeXaeroRegionCodec.Pixel[256];Arrays.fill(pixels,new NativeXaeroRegionCodec.Pixel(height<<12,null,-1,new NativeXaeroRegionCodec.Overlay[0],null));
  var tiles=new NativeXaeroRegionCodec.Tile[1024];tiles[0]=new NativeXaeroRegionCodec.Tile(pixels,new NativeXaeroRegionCodec.Footer(1,256,16));
  return new NativeXaeroRegionCodec.Region(tiles);
 }
 static MapTileFreshness.Stamp[] stamps(NativeXaeroRegionCodec.Region region,String epoch,long tick,boolean local)throws IOException{
  var result=new MapTileFreshness.Stamp[1024];result[0]=new MapTileFreshness.Stamp(epoch,tick,local,NativeXaeroRegionCodec.fingerprint(region.tile(0)));return result;
 }
 static Path source()throws IOException{return Files.createTempDirectory("map-journal-").resolve("0_0.zip");}
 static Path pending(Path source){return source.resolveSibling(source.getFileName()+".motiquies-freshness.pending");}
 static void zip(Path source,NativeXaeroRegionCodec.Region region)throws IOException{Files.write(source,NativeMapMerge.zip(region,null));}
 static void restartRecovery()throws Exception{
  Path source=source();var a=region(1);var b=region(2);zip(source,b);
  var old=stamps(a,"epoch",100,false);var fresh=stamps(b,"epoch",200,false);
  MapTileFreshness.write(MapTileFreshness.sidecar(source),old);MapTileFreshness.write(pending(source),fresh);
  check(fresh[0].equals(LocalMapObservations.readSafe(source)[0]),"ZIP B committed before crash must recover B stamp from pending, not stale main A");
  check(!Files.exists(pending(source)),"successful durable recovery retires pending journal");
  var c=region(3);long[] ticks=new long[1024];Arrays.fill(ticks,300);
  var merge=NativeMapMerge.merge(NativeMapMerge.zip(c,new MapSourceTimes("epoch",ticks)),Files.readAllBytes(source),null,LocalMapObservations.readSafe(source),null);
  check(NativeXaeroRegionCodec.fingerprint(c.tile(0)).equals(NativeXaeroRegionCodec.fingerprint(NativeMapMerge.unzip(merge.zip()).region().tile(0))),"recovered B remains comparable so newer C replaces it");
 }
 static void failedReplacementRecovery()throws Exception{
  Path source=source();var a=region(1);zip(source,a);var old=stamps(a,"epoch",100,false);var fresh=stamps(region(2),"epoch",200,false);
  MapTileFreshness.write(MapTileFreshness.sidecar(source),old);MapTileFreshness.write(pending(source),fresh);
  check(old[0].equals(LocalMapObservations.readSafe(source)[0]),"failed ZIP replacement retains the old hash-matching provenance");
 }
 static void finishFailureRecovery()throws Exception{
  Path source=source();var b=region(2);zip(source,b);var fresh=stamps(b,"epoch",200,false);
  Files.createDirectory(MapTileFreshness.sidecar(source));MapTileFreshness.write(pending(source),fresh);
  check(fresh[0].equals(LocalMapObservations.readSafe(source)[0]),"failed main-sidecar recovery still returns matching B candidate");
  check(Files.exists(pending(source)),"failed durable recovery keeps pending journal");
 }
 static void stalePendingCannotDemoteLocal()throws Exception{
  Path source=source();var b=region(2);zip(source,b);var local=stamps(b,"epoch",300,true);
  MapTileFreshness.write(MapTileFreshness.sidecar(source),local);MapTileFreshness.write(pending(source),stamps(b,"epoch",200,false));
  check(local[0].equals(LocalMapObservations.readSafe(source)[0]),"old pending provenance cannot replace newer local stamp with same tile hash");
  MapTileFreshness.write(pending(source),stamps(b,"other-world",500,false));
  check(local[0].equals(LocalMapObservations.readSafe(source)[0]),"incomparable server pending cannot demote local provenance");
  var c=region(3);zip(source,c);var changedLocal=stamps(c,"epoch",301,true);
  MapTileFreshness.write(MapTileFreshness.sidecar(source),changedLocal);MapTileFreshness.write(pending(source),stamps(b,"epoch",500,false));
  check(changedLocal[0].equals(LocalMapObservations.readSafe(source)[0]),"pending with an old tile hash cannot overwrite a newer changed local tile stamp");
 }
 static void sameHashOrdering()throws Exception{
  Path source=source();var a=region(1);zip(source,a);var newer=stamps(a,"epoch",200,false);
  MapTileFreshness.write(MapTileFreshness.sidecar(source),stamps(a,"epoch",100,true));MapTileFreshness.write(pending(source),newer);
  check(newer[0].equals(LocalMapObservations.readSafe(source)[0]),"same epoch same hash chooses newer tick");
  var tieLocal=stamps(a,"epoch",200,true);MapTileFreshness.write(pending(source),tieLocal);
  check(tieLocal[0].equals(LocalMapObservations.readSafe(source)[0]),"same tick prefers local provenance");
 }
 static void corruptMetadataAndSource()throws Exception{
  Path source=source();var b=region(2);zip(source,b);var fresh=stamps(b,"epoch",200,false);
  Files.write(MapTileFreshness.sidecar(source),new byte[]{1,2,3});MapTileFreshness.write(pending(source),fresh);
  check(fresh[0].equals(LocalMapObservations.readSafe(source)[0]),"corrupt main does not hide matching pending provenance");
  byte[] broken={3,2,1};Files.write(source,broken);MapTileFreshness.write(pending(source),fresh);
  check(LocalMapObservations.readSafe(source)[0]==null,"unparseable source is unknown, never trusts unbound metadata");
  check(Arrays.equals(broken,Files.readAllBytes(source))&&Files.exists(pending(source)),"recovery never rewrites an unparseable native source or removes its journal");
 }
 static void unknownAndNativeReencode()throws Exception{
  Path source=source();var a=region(1);zip(source,a);
  check(LocalMapObservations.readSafe(source)[0]==null,"legacy terrain without provenance stays unknown");
  // Equivalent native streams with a state defined afresh at every pixel versus
  // references to a shared state palette really differ in bytes, as native saves can.
  var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
  out.writeByte(255);out.writeInt(0x60008);out.writeByte(0);
  for(int p=0;p<256;p++){
   out.writeInt(0x201001);out.writeByte(10);out.writeUTF("");out.writeByte(8);out.writeUTF("Name");out.writeUTF("mod:opaque_state");out.writeByte(0);
  }
  out.writeByte(1);out.writeInt(256);out.writeByte(16);for(int slot=1;slot<16;slot++)out.writeInt(-1);
  byte[] raw=bytes.toByteArray();a=NativeXaeroRegionCodec.decode(raw);
  var stamp=stamps(a,"epoch",100,true);MapTileFreshness.write(MapTileFreshness.sidecar(source),stamp);
  byte[] reencoded=NativeXaeroRegionCodec.encode(a);check(!Arrays.equals(raw,reencoded),"native fixture must really change palette representation");
  var nativeRegion=NativeXaeroRegionCodec.decode(reencoded);zip(source,nativeRegion);
  check(stamp[0].equals(LocalMapObservations.readSafe(source)[0]),"native re-encode preserves semantic tile hash and provenance");
 }
 static void failedReplacementThenRetry()throws Exception{
  Path source=source();var a=region(1);var b=region(2);zip(source,a);
  var old=stamps(a,"epoch",100,false);MapTileFreshness.write(MapTileFreshness.sidecar(source),old);
  long[] times=new long[1024];Arrays.fill(times,200);byte[] incoming=NativeMapMerge.zip(b,new MapSourceTimes("epoch",times));
  var first=NativeMapMerge.merge(incoming,Files.readAllBytes(source),null,LocalMapObservations.readSafe(source),null);
  String failed=MapTileFreshness.commit(source,first.stamps(),()->null,failure->{throw new AssertionError(failure);});
  check(failed==null&&old[0].equals(MapTileFreshness.read(MapTileFreshness.sidecar(source))[0]),"failed underlying ZIP replacement cannot overwrite main old provenance");
  var retry=NativeMapMerge.merge(incoming,Files.readAllBytes(source),null,LocalMapObservations.readSafe(source),null);
  var receipt=MapTileFreshness.commit(source,retry.stamps(),()->{Files.write(source,retry.zip());return "B-crc";},failure->{throw new AssertionError(failure);});
  check("B-crc".equals(receipt)&&retry.imported()==1,"retry still imports newer B after failed source replacement");
  check(stamps(b,"epoch",200,false)[0].equals(LocalMapObservations.readSafe(source)[0]),"successful retry has B tile and its matching stamp");
 }
 static void successfulZipFailedFinishStillReceipts()throws Exception{
  Path source=source();var b=region(2);var fresh=stamps(b,"epoch",200,false);var warnings=new ArrayList<IOException>();
  var receipt=MapTileFreshness.commit(source,fresh,()->{zip(source,b);Files.createDirectory(MapTileFreshness.sidecar(source));return "B-crc";},warnings::add);
  check("B-crc".equals(receipt)&&warnings.size()==1&&Files.exists(pending(source)),"committed B returns receipt for native handoff despite failed finish, retaining journal");
  check(fresh[0].equals(LocalMapObservations.readSafe(source)[0]),"restart reads B even while finish path remains unavailable");
  Files.delete(MapTileFreshness.sidecar(source));
  check(fresh[0].equals(LocalMapObservations.readSafe(source)[0])&&!Files.exists(pending(source)),"later recovery repairs main and retires journal");
 }
 static void stageFailureDoesNotWriteSource()throws Exception{
  Path source=source();var a=region(1);zip(source,a);byte[] before=Files.readAllBytes(source);
  var old=stamps(a,"epoch",100,false);MapTileFreshness.write(MapTileFreshness.sidecar(source),old);Files.createDirectory(pending(source));
  int[] writes={0};boolean failed=false;
  try{MapTileFreshness.commit(source,stamps(region(2),"epoch",200,false),()->{writes[0]++;return "crc";},failure->{throw new AssertionError(failure);});}catch(IOException expected){failed=true;}
  check(failed&&writes[0]==0&&Arrays.equals(before,Files.readAllBytes(source))&&old[0].equals(MapTileFreshness.read(MapTileFreshness.sidecar(source))[0]),"journal stage failure prevents source write and preserves old provenance");
 }
 static void unrepairedJournalCannotBeOverwritten()throws Exception{
  Path source=source();var b=region(2);zip(source,b);var fresh=stamps(b,"epoch",200,false);
  MapTileFreshness.write(pending(source),fresh);Files.createDirectory(MapTileFreshness.sidecar(source));
  int[] writes={0};boolean blocked=false;
  try{MapTileFreshness.commit(source,stamps(region(3),"epoch",300,false),()->{writes[0]++;return null;},failure->{throw new AssertionError(failure);});}catch(IOException expected){blocked=true;}
  check(blocked&&writes[0]==0,"unrepaired pending B must prevent a new C stage and source transaction");
  check(fresh[0].equals(MapTileFreshness.read(pending(source))[0])&&fresh[0].equals(LocalMapObservations.readSafe(source)[0]),"sole durable matching B provenance survives repeated main-sidecar failure");
 }
 static void legacyHashUpgradeIsContentBound()throws Exception{
  Path source=source();var tiles=new NativeXaeroRegionCodec.Tile[1024];tiles[0]=NativeMapMergeTest.stairs(true,"north");var region=new NativeXaeroRegionCodec.Region(tiles);zip(source,region);
  String oldHash=NativeXaeroRegionCodec.legacyFingerprint(tiles[0]),canonical=NativeXaeroRegionCodec.fingerprint(tiles[0]);
  check(!oldHash.equals(canonical),"fixture needs a noncanonical old hash");
  var old=new MapTileFreshness.Stamp[1024];old[0]=new MapTileFreshness.Stamp("epoch",100,true,oldHash);MapTileFreshness.write(MapTileFreshness.sidecar(source),old);
  var recovered=LocalMapObservations.readSafe(source)[0];check(recovered!=null&&recovered.hash().equals(canonical)&&recovered.tick()==100&&recovered.local(),"exact raw legacy match upgrades hash preserving local provenance");
  tiles[0]=NativeMapMergeTest.stairs(true,"south");zip(source,new NativeXaeroRegionCodec.Region(tiles));
  check(LocalMapObservations.readSafe(source)[0]==null,"changed state cannot inherit old legacy provenance");
 }
 public static void main(String[] args)throws Exception{
  legacyHashUpgradeIsContentBound();var failures=new ArrayList<String>();
  for(var test:List.<Checked>of(MapFreshnessJournalTest::restartRecovery,MapFreshnessJournalTest::failedReplacementRecovery,MapFreshnessJournalTest::finishFailureRecovery,MapFreshnessJournalTest::stalePendingCannotDemoteLocal,MapFreshnessJournalTest::sameHashOrdering,MapFreshnessJournalTest::corruptMetadataAndSource,MapFreshnessJournalTest::unknownAndNativeReencode,MapFreshnessJournalTest::failedReplacementThenRetry,MapFreshnessJournalTest::successfulZipFailedFinishStillReceipts,MapFreshnessJournalTest::stageFailureDoesNotWriteSource,MapFreshnessJournalTest::unrepairedJournalCannotBeOverwritten))try{test.run();}catch(AssertionError error){failures.add(error.getMessage());}
  if(!failures.isEmpty())throw new AssertionError(String.join("\n",failures));
  System.out.println("MapFreshnessJournalTest passed: restart, failed replacement/finish, local priority, tick/tie ordering, corrupt metadata/source, legacy, native re-encode");
 }
 interface Checked{void run()throws Exception;}
}
