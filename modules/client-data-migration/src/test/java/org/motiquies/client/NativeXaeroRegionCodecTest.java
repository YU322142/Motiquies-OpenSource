package org.motiquies.client;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.motiquies.client.NativeXaeroRegionCodec.*;

/** Standalone JDK 21 tests; fixtures are emitted independently of the codec. */
public final class NativeXaeroRegionCodecTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        emptySparseAndPaletteSemantics();
        mergePreservesSelectedLocalAndFillsMissing();
        fingerprintsIgnoreInputPaletteLayout();
        fingerprintsNormalizeNativeCompoundOrder();
        fingerprintsPreserveUnknownNbtSemantics();
        fingerprintsNormalizeModifiedUtf();
        rejectsCorruption();
        defensiveCopies();
        for (String arg : args)if(arg.equals("--native-nbt"))nativeNbtRoundtrip();else nativeZipRoundtrip(Path.of(arg));
        System.out.println("PASS NativeXaeroRegionCodecTest checks=" + checks);
    }
    private static final byte[] NATIVE_STAIRS=HexFormat.of().parseHex("0a00000a000a50726f7065727469657308000468616c660006626f74746f6d08000b77617465726c6f67676564000566616c7365080005736861706500087374726169676874080006666163696e6700056e6f727468000800044e616d6500146d696e6563726166743a6f616b5f73746169727300");
    private static final byte[] CONVERTER_STAIRS=HexFormat.of().parseHex("0a00000800044e616d6500146d696e6563726166743a6f616b5f7374616972730a000a50726f70657274696573080006666163696e6700056e6f72746808000468616c660006626f74746f6d08000573686170650008737472616967687408000b77617465726c6f67676564000566616c73650000");
    private static Tile stateTile(byte[] state,byte[] overlay,byte[] biome){
        var pixels=new Pixel[256];Arrays.fill(pixels,new Pixel(1|(overlay==null?0:2)|(biome==null?0:0x100000),new Blob(state),-1,overlay==null?new Overlay[0]:new Overlay[]{new Overlay(0x2AD1,new Blob(overlay))},biome==null?null:new Blob(biome)));
        return new Tile(pixels,new Footer(1,Integer.MAX_VALUE,32));
    }
    private static byte[] atOrigin(Tile tile)throws IOException{var tiles=new Tile[1024];tiles[0]=tile;return encode(new Region(tiles));}
    // These are the exact native/converter bytes from e28's real NbtOrderProbe log.
    private static void fingerprintsNormalizeNativeCompoundOrder()throws Exception{
        Tile nativeTile=stateTile(NATIVE_STAIRS,null,null),converted=stateTile(CONVERTER_STAIRS,null,null);
        System.out.println("LEGACY_STAIRS native="+legacyFingerprint(nativeTile)+" converter="+legacyFingerprint(converted));
        check(legacyFingerprint(nativeTile).equals("473c518b8b61f01c749c3dfb1fb5dc5215e0a8c6ca6df19e2a1b185dbf10f957"),"native-order legacy fingerprint remains exactly the old algorithm");
        check(legacyFingerprint(converted).equals("0e7b08703783f74eb8e0bbbe9b0d9cf6a5f2f41168585f225dda8f3da4b59af4"),"converter-order legacy fingerprint remains exactly the old algorithm");
        check(!legacyFingerprint(nativeTile).equals(legacyFingerprint(converted)),"pre-0.3.15 raw hash exposes actual NBT ordering mismatch");
        check(fingerprint(nativeTile).equals(fingerprint(converted)),"actual native and converter oak_stairs have the same semantic fingerprint");
        check(fingerprint(nativeTile).equals("0e7b08703783f74eb8e0bbbe9b0d9cf6a5f2f41168585f225dda8f3da4b59af4"),"normal converter sidecar hash is already the canonical semantic hash");
        check(!fingerprint(nativeTile).equals(fingerprint(stateTile(replaceUtf(NATIVE_STAIRS,"north","south"),null,null))),"property value changes fingerprint");
        check(!fingerprint(nativeTile).equals(fingerprint(stateTile(replaceUtf(NATIVE_STAIRS,"minecraft:oak_stairs","minecraft:birch_stairs"),null,null))),"Name value changes fingerprint");
        check(fingerprint(stateTile(NATIVE_STAIRS,CONVERTER_STAIRS,null)).equals(fingerprint(stateTile(CONVERTER_STAIRS,NATIVE_STAIRS,null))),"overlay state uses the same semantic NBT normalization");
        byte[] before=atOrigin(nativeTile);fingerprint(nativeTile);fingerprintMatches(nativeTile,legacyFingerprint(nativeTile));
        check(Arrays.equals(before,atOrigin(nativeTile)),"fingerprinting never rewrites encoded opaque NBT");
        check(Arrays.equals(decode(before).tile(0).pixels()[0].blockState().bytes(),NATIVE_STAIRS),"native NBT raw order survives encode/decode");
        check(fingerprintMatches(nativeTile,legacyFingerprint(nativeTile)),"existing native-order raw hash can be matched for safe promotion");
        check(fingerprintMatches(nativeTile,legacyFingerprint(converted)),"existing canonical converter hash survives a native order rewrite");
        check(!fingerprintMatches(nativeTile,"0".repeat(64))&&!fingerprintMatches(nativeTile,null),"unrelated/missing hash never promoted");
    }
    @FunctionalInterface private interface NbtField{void write(DataOutputStream out)throws IOException;}
    private static byte[] complexNbt(boolean reverse,boolean reverseList)throws IOException{
        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeByte(10);out.writeUTF("");
        List<NbtField> fields=new ArrayList<>();
        fields.add(d->{d.writeByte(8);d.writeUTF("Name");d.writeUTF("mod:unknown_block");});
        fields.add(d->{d.writeByte(10);d.writeUTF("extension");List<NbtField> nested=new ArrayList<>();
            nested.add(v->{v.writeByte(8);v.writeUTF("text");v.writeUTF("a\u0000\u4e16\u754c");});
            nested.add(v->{v.writeByte(7);v.writeUTF("bytes");v.writeInt(3);v.write(new byte[]{-1,0,7});});
            nested.add(v->{v.writeByte(11);v.writeUTF("ints");v.writeInt(2);v.writeInt(Integer.MIN_VALUE);v.writeInt(7);});
            nested.add(v->{v.writeByte(12);v.writeUTF("longs");v.writeInt(2);v.writeLong(Long.MIN_VALUE);v.writeLong(9);});
            nested.add(v->{v.writeByte(5);v.writeUTF("nan");v.writeInt(0x7fc01234);});
            nested.add(v->{v.writeByte(6);v.writeUTF("negativeZero");v.writeLong(0x8000000000000000L);});
            nested.add(v->{v.writeByte(9);v.writeUTF("empty");v.writeByte(10);v.writeInt(0);});
            nested.add(v->{v.writeByte(9);v.writeUTF("ordered");v.writeByte(10);v.writeInt(2);for(int n=0;n<2;n++){int number=reverseList?1-n:n;
                if(reverse){v.writeByte(3);v.writeUTF("index");v.writeInt(number);v.writeByte(8);v.writeUTF("value");v.writeUTF(number==0?"first":"second");}
                else{v.writeByte(8);v.writeUTF("value");v.writeUTF(number==0?"first":"second");v.writeByte(3);v.writeUTF("index");v.writeInt(number);}v.writeByte(0);}});
            if(reverse)Collections.reverse(nested);for(var field:nested)field.write(d);d.writeByte(0);
        });
        if(reverse)Collections.reverse(fields);for(var field:fields)field.write(out);out.writeByte(0);return bytes.toByteArray();
    }
    private static void fingerprintsPreserveUnknownNbtSemantics()throws Exception{
        Tile a=stateTile(complexNbt(false,false),null,null),b=stateTile(complexNbt(true,false),null,null);
        check(fingerprint(a).equals(fingerprint(b)),"recursive unknown compounds and compounds in lists ignore key order");
        check(!fingerprint(a).equals(fingerprint(stateTile(complexNbt(true,true),null,null))),"list element order remains semantic");
        byte[] changed=replaceUtf(complexNbt(false,false),"first","third");
        check(!fingerprint(a).equals(fingerprint(stateTile(changed,null,null))),"unknown nested/list string value remains semantic");
        byte[] before=atOrigin(a);fingerprint(a);check(Arrays.equals(before,atOrigin(a)),"unknown NBT arrays/types/list bytes stay opaque in actual encoding");
        for(int type=1;type<=12;type++)check(!fingerprint(stateTile(typedNbt(type,1),null,null)).equals(fingerprint(stateTile(typedNbt(type,2),null,null))),"primitive/array/list/compound value changes remain semantic for tag " + type);
        check(!fingerprint(stateTile(typedNbt(1,1),null,null)).equals(fingerprint(stateTile(typedNbt(3,1),null,null))),"NBT tag type remains semantic even for equal numeric value");
    }
    private static byte[] typedNbt(int type,long value)throws IOException{
        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeByte(10);out.writeUTF("");
        out.writeByte(8);out.writeUTF("Name");out.writeUTF("mod:unknown_block");out.writeByte(type);out.writeUTF("typed");
        switch(type){
            case 1->out.writeByte((int)value);case 2->out.writeShort((int)value);case 3->out.writeInt((int)value);case 4->out.writeLong(value);
            case 5->out.writeInt(value==0?0x80000000:Float.floatToRawIntBits((float)value));case 6->out.writeLong(value==0?0x8000000000000000L:Double.doubleToRawLongBits((double)value));
            case 7->{out.writeInt(2);out.writeByte(7);out.writeByte((int)value);}case 8->out.writeUTF("value-"+value);
            case 9->{out.writeByte(3);out.writeInt(2);out.writeInt(7);out.writeInt((int)value);}
            case 10->{out.writeByte(3);out.writeUTF("number");out.writeInt((int)value);out.writeByte(0);}
            case 11->{out.writeInt(2);out.writeInt(7);out.writeInt((int)value);}case 12->{out.writeInt(2);out.writeLong(7);out.writeLong(value);}
            default->throw new AssertionError("fixture tag type");
        }out.writeByte(0);return bytes.toByteArray();
    }
    private static byte[] replaceUtf(byte[] raw,String from,String to)throws IOException{
        byte[] old=utf(from),replacement=utf(to);outer:for(int i=0;i<=raw.length-old.length;i++){
            for(int j=0;j<old.length;j++)if(raw[i+j]!=old[j])continue outer;
            var bytes=new ByteArrayOutputStream();bytes.write(raw,0,i);bytes.write(replacement);bytes.write(raw,i+old.length,raw.length-i-old.length);return bytes.toByteArray();
        }throw new AssertionError("fixture string not found: "+from);
    }
    private static byte[] overlongUtf(String text)throws IOException{
        byte[] canonical=utf(text);int first=canonical[2]&255;var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
        out.writeShort(canonical.length-1);out.writeByte(0xC0|(first>>>6));out.writeByte(0x80|(first&63));out.write(canonical,3,canonical.length-3);return bytes.toByteArray();
    }
    private static void fingerprintsNormalizeModifiedUtf()throws Exception{
        byte[] nativeAlt=NATIVE_STAIRS.clone(),key=utf("Name"),alternative=overlongUtf("Name");
        for(int i=0;i<=nativeAlt.length-key.length;i++)if(Arrays.equals(Arrays.copyOfRange(nativeAlt,i,i+key.length),key)){
            var bytes=new ByteArrayOutputStream();bytes.write(nativeAlt,0,i);bytes.write(alternative);bytes.write(nativeAlt,i+key.length,nativeAlt.length-i-key.length);nativeAlt=bytes.toByteArray();break;
        }
        check(fingerprint(stateTile(NATIVE_STAIRS,null,utf("mod:area"))).equals(fingerprint(stateTile(nativeAlt,null,overlongUtf("mod:area")))),"Modified UTF normalization applies to NBT keys and biome strings");
    }
    // Optional real MC NbtIo read/write validation; this is a standalone JVM, never a game launch.
    private static void nativeNbtRoundtrip()throws Exception{
        Class<?> io=Class.forName("net.minecraft.nbt.NbtIo"),compound=Class.forName("net.minecraft.nbt.CompoundTag");
        var read=io.getMethod("read",DataInput.class);var write=io.getMethod("write",compound,DataOutput.class);
        List<byte[]> cases=new ArrayList<>(List.of(NATIVE_STAIRS,CONVERTER_STAIRS,complexNbt(false,false),complexNbt(true,false)));
        for(int type=1;type<=12;type++)cases.add(typedNbt(type,type==5||type==6?0:1));
        for(byte[] raw:cases){
            Object value=read.invoke(null,new DataInputStream(new ByteArrayInputStream(raw)));var bytes=new ByteArrayOutputStream();write.invoke(null,value,new DataOutputStream(bytes));
            if(!fingerprint(stateTile(raw,raw,null)).equals(fingerprint(stateTile(bytes.toByteArray(),bytes.toByteArray(),null)))){
                System.out.println("CANONICAL_BEFORE="+HexFormat.of().formatHex(normalizeForFingerprint(stateTile(raw,null,null)).pixels()[0].blockState().bytes()));
                System.out.println("CANONICAL_AFTER="+HexFormat.of().formatHex(normalizeForFingerprint(stateTile(bytes.toByteArray(),null,null)).pixels()[0].blockState().bytes()));
            }
            check(fingerprint(stateTile(raw,raw,null)).equals(fingerprint(stateTile(bytes.toByteArray(),bytes.toByteArray(),null))),"actual native NbtIo roundtrip preserves semantic fingerprint including unknown NBT/list/arrays");
        }
        System.out.println("NATIVE_NBT semantic read/write probe passed");
    }
    // Removing sparse-record handling or interpreting tile -1 as a pixel breaks these assertions.
    private static void emptySparseAndPaletteSemantics() throws Exception {
        Region empty = decode(new byte[]{(byte)255,0,6,0,8});
        check(empty != null, "empty region decodes");
        check(Arrays.stream(empty.tiles()).allMatch(Objects::isNull), "empty region has no tiles");
        check(Arrays.equals(encode(empty), new byte[]{(byte)255,0,6,0,8}), "empty output stays empty");
        Region region = decode(fixture("mod:local", 0x70));
        check(region.tile(31 * 32 + 28) != null, "sparse coord 7,7 / tile 3,0 maps to 31,28");
        check(region.tile(31 * 32 + 29) != null, "following tile with cross-tile references present");
        check(region.tile(0) == null, "omitted sparse group stays absent");
        Pixel first = region.tile(31 * 32 + 28).pixels()[0];
        check(first.params() == 0x1105A03, "opaque params survive except palette flags");
        check(first.topHeight() == 0xEC, "top height byte preserved unsigned");
        check(first.blockState().equals(new Blob(nbt("mod:local"))), "opaque root NBT incl properties preserved");
        check(first.biome().equals(new Blob(utf("mod:deep\u0000\u4e16\u754c"))), "modified UTF including NUL preserved");
        check(first.overlays().length == 2, "overlay count retained");
        check(first.overlays()[0].params() == 0x2AD1, "explicit overlay opaque params retained");
        check(first.overlays()[0].blockState().equals(first.blockState()), "overlay resolves shared block palette");
        check(first.overlays()[1].blockState() == null, "implicit water needs no state");
        check(region.tile(31 * 32 + 28).footer().equals(new Footer(0xFE, -127, 0xE1)), "all footer bytes retained");
        Pixel later = region.tile(31 * 32 + 29).pixels()[0];
        check(later.blockState().equals(first.blockState()), "cross-tile state reference resolved");
        check(later.biome().equals(first.biome()), "cross-tile biome reference resolved");
        equivalent(region, decode(encode(region)));
    }
    // A region-wide replacement, wrong local mask, or raw input byte-splicing breaks this test.
    private static void mergePreservesSelectedLocalAndFillsMissing() throws Exception {
        Region local = decode(fixture("mod:local", 0x70));
        Region server = decode(fixture("mod:server", 0x10));
        Tile[] localTiles = local.tiles();
        localTiles[0] = local.tile(31 * 32 + 28);
        localTiles[31 * 32 + 29] = null;
        local = new Region(localTiles);
        boolean[] keep = new boolean[1024];
        keep[31 * 32 + 28] = true;
        keep[0] = true;
        Region merged = decode(encode(combine(local, server, keep)));
        check(merged.tile(31 * 32 + 28).pixels()[0].blockState().equals(new Blob(nbt("mod:local"))), "mask retains newer local tile");
        check(merged.tile(31 * 32 + 29).pixels()[0].blockState().equals(new Blob(nbt("mod:server"))), "missing local filled despite keep mask false");
        check(merged.tile(0) != null, "keep mask preserves local when server absent");
        keep[31 * 32 + 28] = false;
        Region newerServer = combine(local, server, keep);
        check(newerServer.tile(31 * 32 + 28).pixels()[0].blockState().equals(new Blob(nbt("mod:server"))), "newer server selected by caller replaces local");
        keep[31 * 32 + 29] = true;
        check(combine(local, server, keep).tile(31 * 32 + 29) != null, "keep absent local still fills server");
        keep[0] = false;
        check(combine(local, server, keep).tile(0) == null, "external tombstone policy may explicitly delete local");
        try { combine(local,server,new boolean[2]); throw new AssertionError("bad mask accepted"); }
        catch(IllegalArgumentException expected) { checks++; }
    }
    // Constant fingerprints or hashes containing original palette references break these assertions.
    private static void fingerprintsIgnoreInputPaletteLayout() throws Exception {
        Region region=decode(fixture("mod:local",0x70));
        Tile a=region.tile(31*32+28),b=region.tile(31*32+29);
        String hash=fingerprint(a);
        check(hash.matches("[0-9a-f]{64}"),"SHA256 lowercase hex fingerprint");
        check(hash.equals(fingerprint(b)),"inline vs cross-tile palette ref hash identically");
        check(hash.equals(fingerprint(decode(encode(region)).tile(31*32+28))),"reencoding leaves fingerprint stable");
        check(!hash.equals(fingerprint(decode(fixture("mod:other",0x70)).tile(31*32+28))),"changed state changes fingerprint");
        Pixel[] pixels=a.pixels();Pixel p=pixels[1];
        pixels[1]=new Pixel(p.params()^0x10,p.blockState(),p.topHeight(),p.overlays(),p.biome());
        check(!hash.equals(fingerprint(new Tile(pixels,a.footer()))),"opaque params participate in fingerprint");
    }
    // Missing bounds/version/palette validation lets these corrupt payloads reach disk.
    private static void rejectsCorruption() throws Exception {
        byte[] good = fixture("mod:local",0x70);
        for (int size : new int[]{0,1,4,6,good.length-1}) bad(Arrays.copyOf(good,size),"truncated " + size);
        byte[] badMagic=good.clone(); badMagic[0]=0; bad(badMagic,"unknown magic");
        byte[] major=good.clone(); major[2]=7; bad(major,"unknown major");
        byte[] minor=good.clone(); minor[4]=9; bad(minor,"unknown minor");
        byte[] coord=good.clone(); coord[5]=(byte)0x87; bad(coord,"out of range coord");
        byte[] duplicate=Arrays.copyOf(good,good.length+65); duplicate[good.length]=0x77;
        Arrays.fill(duplicate,good.length+1,duplicate.length,(byte)255); bad(duplicate,"duplicate coord");
        byte[] invalidReference=good.clone();
        // First tile has 12 null slots; first pixel state-new flag is cleared without replacing NBT.
        invalidReference[5+1+12*4+1]&=(byte)~0x20; bad(invalidReference,"invalid block ref");
        byte[] invalidNbt=good.clone(); invalidNbt[5+1+12*4+4]=99; bad(invalidNbt,"unknown NBT type");
        var deep = new ByteArrayOutputStream(); var out = new DataOutputStream(deep);
        out.write(new byte[]{(byte)255,0,6,0,8,0}); out.writeInt(0x200001);
        for(int i=0;i<70;i++){out.writeByte(10);out.writeUTF("");}
        bad(deep.toByteArray(),"NBT depth bounded");
        var array = new ByteArrayOutputStream(); out=new DataOutputStream(array);
        out.write(new byte[]{(byte)255,0,6,0,8,0});out.writeInt(0x200001);
        out.writeByte(10);out.writeUTF("");out.writeByte(7);out.writeUTF("bad");out.writeInt(Integer.MAX_VALUE);
        bad(array.toByteArray(),"NBT array length bounded");
    }
    private static void defensiveCopies() throws Exception {
        Region region = decode(fixture("mod:local",0x70));
        Tile[] copy=region.tiles();copy[31*32+28]=null;
        check(region.tile(31*32+28)!=null,"caller cannot replace region backing tiles");
        Pixel[] pixels=region.tile(31*32+28).pixels();pixels[0]=null;
        check(region.tile(31*32+28).pixels()[0]!=null,"caller cannot replace tile backing pixels");
        byte[] nbt=region.tile(31*32+28).pixels()[0].blockState().bytes();nbt[0]=99;
        check(region.tile(31*32+28).pixels()[0].blockState().bytes()[0]==10,"caller cannot mutate NBT backing bytes");
    }
    private static void nativeZipRoundtrip(Path path) throws Exception {
        byte[] raw;
        try(ZipFile zip=new ZipFile(path.toFile());InputStream input=zip.getInputStream(zip.getEntry("region.xaero"))){
            raw=input.readNBytes(MAX_RAW_BYTES+1);
        }
        Region first=decode(raw);byte[] encoded=encode(first);equivalent(first,decode(encoded));
        Region mixed=combine(first,decode(fixture("mod:replacement",0x10)),new boolean[1024]);
        equivalent(mixed,decode(encode(mixed)));
        System.out.println("NATIVE "+path+" input="+raw.length+" reencoded="+encoded.length+" tiles="+Arrays.stream(first.tiles()).filter(Objects::nonNull).count());
    }
    private static void equivalent(Region left,Region right) {
        for(int i=0;i<1024;i++){
            Tile a=left.tile(i),b=right.tile(i);
            if(a==null||b==null){check(a==b,"tile presence " + i);continue;}
            check(a.footer().equals(b.footer()),"footer " + i);
            Pixel[] ap=a.pixels(),bp=b.pixels();
            for(int p=0;p<256;p++)check(ap[p].equals(bp[p]),"pixel semantics " + i + "/" + p);
        }
    }
    private static void bad(byte[] bytes,String reason) throws Exception {
        try{decode(bytes);throw new AssertionError("accepted: "+reason);}catch(IOException expected){checks++;}
    }
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static byte[] nbt(String name)throws IOException{
        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
        out.writeByte(10);out.writeUTF("");out.writeByte(8);out.writeUTF("Name");out.writeUTF(name);
        out.writeByte(10);out.writeUTF("Properties");out.writeByte(8);out.writeUTF("facing");out.writeUTF("north");out.writeByte(0);
        out.writeByte(7);out.writeUTF("opaqueExtension");out.writeInt(3);out.write(new byte[]{0,1,(byte)255});out.writeByte(0);
        return bytes.toByteArray();
    }
    private static byte[] utf(String text)throws IOException{var bytes=new ByteArrayOutputStream();new DataOutputStream(bytes).writeUTF(text);return bytes.toByteArray();}
    private static byte[] fixture(String block,int opaque)throws IOException{
        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
        out.write(new byte[]{(byte)255,0,6,0,8,0x77});
        for(int slot=0;slot<16;slot++){
            if(slot!=12&&slot!=13){out.writeInt(-1);continue;}
            for(int p=0;p<256;p++){
                if(p!=0){out.writeInt(0x2F000|opaque);continue;}
                int params=0x1105A03;
                if(slot==12)params|=0x600000;
                out.writeInt(params);
                if(slot==12)out.write(nbt(block));else out.writeInt(0);
                out.writeByte(0xEC);out.writeByte(2);
                out.writeInt(0x2AD1);out.writeInt(0); // explicit state shared with base pixel
                out.writeInt(0x1840); // implicit water
                if(slot==12)out.write(utf("mod:deep\u0000\u4e16\u754c"));else out.writeInt(0);
            }
            out.writeByte(0xFE);out.writeInt(-127);out.writeByte(0xE1);
        }
        return bytes.toByteArray();
    }
}
