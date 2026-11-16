package org.motiquies.map;

import java.io.*;
import java.util.*;

/**
 * Lossless semantic codec for native Xaero 6.8 region.xaero streams (not ZIPs).
 * Tile index is localChunkX * 32 + localChunkZ. All values are immutable.
 * Palette flags are transport details; all other params, NBT and UTF bytes survive.
 */
public final class NativeXaeroRegionCodec {
    public static final int MAX_RAW_BYTES=64*1024*1024;
    private static final int MAX_NBT_BYTES=1024*1024, MAX_DEPTH=64, MAX_VALUES=2_000_000;
    private static final int STATE_NEW=0x200000, BIOME_NEW=0x400000, OVERLAY_NEW=0x400;
    private NativeXaeroRegionCodec(){}
    public static final class Blob {
        private final byte[] bytes;
        private final int hash;
        private volatile Blob fingerprintNbt,fingerprintUtf;
        public Blob(byte[] bytes){this.bytes=bytes.clone();hash=Arrays.hashCode(this.bytes);}
        public byte[] bytes(){return bytes.clone();}
        @Override public boolean equals(Object other){return this==other||(other instanceof Blob b&&hash==b.hash&&Arrays.equals(bytes,b.bytes));}
        @Override public int hashCode(){return hash;}
    }
    public record Overlay(int params,Blob blockState){
        public Overlay{
            params&=~OVERLAY_NEW;
            if(((params&1)!=0)!=(blockState!=null))throw new IllegalArgumentException("Overlay state flag mismatch");
        }
    }
    public record Pixel(int params,Blob blockState,int topHeight,Overlay[] overlays,Blob biome){
        public Pixel{
            params&=~(STATE_NEW|BIOME_NEW);
            overlays=overlays.clone();
            if(params==-1||((params&1)!=0)!=(blockState!=null)
                    ||((params&2)!=0)!=(overlays.length>0)||overlays.length>255
                    ||((params&0x100000)!=0)!=(biome!=null)
                    ||((params&0x1000000)!=0)!=(topHeight>=0)||topHeight>255||topHeight< -1)
                throw new IllegalArgumentException("Pixel fields do not match params");
            for(Overlay overlay:overlays)Objects.requireNonNull(overlay,"overlay");
        }
        @Override public Overlay[] overlays(){return overlays.clone();}
        @Override public boolean equals(Object other){return other instanceof Pixel p&&params==p.params&&java.util.Objects.equals(blockState,p.blockState)&&topHeight==p.topHeight&&Arrays.equals(overlays,p.overlays)&&java.util.Objects.equals(biome,p.biome);}
        @Override public int hashCode(){return java.util.Objects.hash(params,blockState,topHeight,Arrays.hashCode(overlays),biome);}
    }
    public record Footer(int interpretationVersion,int caveStart,int caveDepth){
        public Footer{
            if((interpretationVersion&~255)!=0||(caveDepth&~255)!=0)throw new IllegalArgumentException("Footer byte out of range");
        }
    }
    public record Tile(Pixel[] pixels,Footer footer){
        public Tile{
            pixels=pixels.clone();
            if(pixels.length!=256)throw new IllegalArgumentException("Tile requires 256 pixels");
            for(Pixel pixel:pixels)Objects.requireNonNull(pixel,"pixel");
            Objects.requireNonNull(footer,"footer");
        }
        @Override public Pixel[] pixels(){return pixels.clone();}
    }
    public record Region(Tile[] tiles){
        public Region{
            tiles=tiles.clone();
            if(tiles.length!=1024)throw new IllegalArgumentException("Region requires 1024 tile slots");
        }
        @Override public Tile[] tiles(){return tiles.clone();}
        public Tile tile(int index){return tiles[index];}
    }
    public static Region decode(byte[] raw)throws IOException{
        Objects.requireNonNull(raw,"raw");
        if(raw.length>MAX_RAW_BYTES)throw new IOException("Xaero region exceeds raw byte limit");
        Cursor in=new Cursor(raw);
        if(in.u8()!=255||in.i32()!=0x00060008)throw new IOException("Only native Xaero region format 6.8 is supported");
        Tile[] tiles=new Tile[1024];boolean[] groups=new boolean[64];
        List<Blob> states=new ArrayList<>(),biomes=new ArrayList<>();
        while(in.remaining()>0){
            int coordinate=in.u8(),gx=coordinate>>>4,gz=coordinate&15;
            if(gx>7||gz>7||groups[gx*8+gz])throw new IOException("Invalid or duplicate tile-chunk coordinate");
            groups[gx*8+gz]=true;
            for(int tx=0;tx<4;tx++)for(int tz=0;tz<4;tz++){
                int first=in.i32();if(first==-1)continue;
                Pixel[] pixels=new Pixel[256];
                for(int p=0;p<256;p++)pixels[p]=pixel(in,p==0?first:in.i32(),states,biomes);
                Footer footer=new Footer(in.u8(),in.i32(),in.u8());
                tiles[(gx*4+tx)*32+gz*4+tz]=new Tile(pixels,footer);
            }
        }
        return new Region(tiles);
    }
    private static Pixel pixel(Cursor in,int params,List<Blob> states,List<Blob> biomes)throws IOException{
        if(params==-1)throw new IOException("Absent sentinel inside populated tile");
        in.value();
        Blob state=(params&1)==0?null:reference(in,states,(params&STATE_NEW)!=0,true);
        int top=(params&0x1000000)==0?-1:in.u8();
        Overlay[] overlays=new Overlay[0];
        if((params&2)!=0){
            int count=in.u8();
            if(count==0)throw new IOException("Overlay flag with zero overlays");
            overlays=new Overlay[count];
            for(int o=0;o<count;o++){
                in.value();int overlay=in.i32();
                Blob overState=(overlay&1)==0?null:reference(in,states,(overlay&OVERLAY_NEW)!=0,true);
                overlays[o]=new Overlay(overlay,overState);
            }
        }
        Blob biome=(params&0x100000)==0?null:reference(in,biomes,(params&BIOME_NEW)!=0,false);
        return new Pixel(params,state,top,overlays,biome);
    }
    private static Blob reference(Cursor in,List<Blob> palette,boolean fresh,boolean nbt)throws IOException{
        if(fresh){
            Blob parsed=nbt?in.nbt():in.utf();
            // Intern duplicate definitions once. Repeated long state refs then remain O(1) to hash.
            Blob value=in.canonical.computeIfAbsent(parsed,key->key);palette.add(value);return value;
        }
        int index=in.i32();
        if(index<0||index>=palette.size())throw new IOException("Invalid region palette reference");
        return palette.get(index);
    }
    public static byte[] encode(Region region)throws IOException{
        Objects.requireNonNull(region,"region");
        BoundedOutput bytes=new BoundedOutput();DataOutputStream out=new DataOutputStream(bytes);
        Map<Blob,Integer> states=new HashMap<>(),biomes=new HashMap<>();
        out.writeByte(255);out.writeInt(0x00060008);
        for(int gx=0;gx<8;gx++)for(int gz=0;gz<8;gz++){
            boolean present=false;
            for(int tx=0;tx<4;tx++)for(int tz=0;tz<4;tz++)present|=region.tiles[(gx*4+tx)*32+gz*4+tz]!=null;
            if(!present)continue;
            out.writeByte((gx<<4)|gz);
            for(int tx=0;tx<4;tx++)for(int tz=0;tz<4;tz++){
                Tile tile=region.tiles[(gx*4+tx)*32+gz*4+tz];
                if(tile==null){out.writeInt(-1);continue;}
                for(Pixel pixel:tile.pixels)writePixel(out,pixel,states,biomes,false);
                out.writeByte(tile.footer.interpretationVersion);out.writeInt(tile.footer.caveStart);out.writeByte(tile.footer.caveDepth);
            }
        }
        out.flush();return bytes.bytes.toByteArray();
    }
    private static void writePixel(DataOutputStream out,Pixel pixel,Map<Blob,Integer> states,Map<Blob,Integer> biomes,boolean semantic)throws IOException{
        Blob state=semantic?normalizedNbt(pixel.blockState):pixel.blockState,biome=semantic?normalizedUtf(pixel.biome):pixel.biome;
        int params=pixel.params;
        if(state!=null&&!states.containsKey(state))params|=STATE_NEW;
        if(biome!=null&&!biomes.containsKey(biome))params|=BIOME_NEW;
        out.writeInt(params);writeReference(out,state,states,true,semantic);
        if(pixel.topHeight>=0)out.writeByte(pixel.topHeight);
        if(pixel.overlays.length>0){
            out.writeByte(pixel.overlays.length);
            for(Overlay overlay:pixel.overlays){
                Blob overlayState=semantic?normalizedNbt(overlay.blockState):overlay.blockState;int overlayParams=overlay.params;
                if(overlayState!=null&&!states.containsKey(overlayState))overlayParams|=OVERLAY_NEW;
                out.writeInt(overlayParams);writeReference(out,overlayState,states,true,semantic);
            }
        }
        writeReference(out,biome,biomes,false,semantic);
    }
    private static void writeReference(DataOutputStream out,Blob value,Map<Blob,Integer> palette,boolean nbt,boolean validated)throws IOException{
        if(value==null)return;
        Integer index=palette.get(value);
        if(index!=null){out.writeInt(index);return;}
        // Public model constructors may be used by a native-memory serializer: validate their blobs too.
        if(!validated){
            Cursor check=new Cursor(value.bytes);
            if(nbt)check.nbt();else check.utf();
            if(check.remaining()!=0)throw new IOException("Trailing bytes in opaque palette value");
        }
        out.write(value.bytes);palette.put(value,palette.size());
    }
    /** Caller owns absence/tombstone policy: false selects the server even when it is null. */
    public static Region combine(Region local,Region server,boolean[] keepLocal){
        Objects.requireNonNull(local,"local");Objects.requireNonNull(server,"server");
        if(keepLocal==null||keepLocal.length!=1024)throw new IllegalArgumentException("Priority mask requires 1024 entries");
        Tile[] tiles=new Tile[1024];
        for(int i=0;i<tiles.length;i++)tiles[i]=keepLocal[i]&&local.tiles[i]!=null?local.tiles[i]:server.tiles[i];
        return new Region(tiles);
    }
    /** SHA256 of semantic NBT/UTF at fixed local coordinate 0,0 with fresh palettes. */
    public static String fingerprint(Tile tile)throws IOException{
        return tileFingerprint(tile,true);
    }
    /** Exact pre-semantic-normalization algorithm, retained for existing sidecar proofs. */
    public static String legacyFingerprint(Tile tile)throws IOException{
        return tileFingerprint(tile,false);
    }
    private static String tileFingerprint(Tile tile,boolean semantic)throws IOException{
        Objects.requireNonNull(tile,"tile");
        try{
            var digest=java.security.MessageDigest.getInstance("SHA-256");var out=new DataOutputStream(new DigestOutput(digest));
            out.writeByte(255);out.writeInt(0x60008);out.writeByte(0);
            Map<Blob,Integer> states=new HashMap<>(),biomes=new HashMap<>();
            for(Pixel pixel:tile.pixels)writePixel(out,pixel,states,biomes,semantic);
            out.writeByte(tile.footer.interpretationVersion);out.writeInt(tile.footer.caveStart);out.writeByte(tile.footer.caveDepth);
            for(int i=1;i<16;i++)out.writeInt(-1); // Exact old encode(single tile at0,0) byte stream.
            out.flush();return HexFormat.of().formatHex(digest.digest());
        }catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    public static boolean fingerprintMatches(Tile tile,String expected)throws IOException{
        return expected!=null&&(expected.equals(fingerprint(tile))||expected.equals(legacyFingerprint(tile)));
    }
    /** Nonmutating proof view; callers must keep the original tile for lossless actual writes. */
    public static Tile normalizeForFingerprint(Tile tile)throws IOException{
        Objects.requireNonNull(tile,"tile");Pixel[] pixels=new Pixel[256];boolean changed=false;
        for(int i=0;i<pixels.length;i++){
            Pixel pixel=tile.pixels[i];Blob state=normalizedNbt(pixel.blockState),biome=normalizedUtf(pixel.biome);
            Overlay[] overlays=pixel.overlays;boolean different=state!=pixel.blockState||biome!=pixel.biome;
            for(int j=0;j<overlays.length;j++){
                Overlay overlay=overlays[j];Blob value=normalizedNbt(overlay.blockState);
                if(value!=overlay.blockState){if(overlays==pixel.overlays)overlays=overlays.clone();overlays[j]=new Overlay(overlay.params,value);different=true;}
            }
            pixels[i]=different?new Pixel(pixel.params,state,pixel.topHeight,overlays,biome):pixel;changed|=different;
        }
        return changed?new Tile(pixels,tile.footer):tile;
    }
    private static Blob normalizedUtf(Blob value)throws IOException{
        if(value==null)return null;Blob cached=value.fingerprintUtf;if(cached!=null)return cached;
        synchronized(value){
            if(value.fingerprintUtf!=null)return value.fingerprintUtf;
            Cursor in=new Cursor(value.bytes);String text=in.text();if(in.remaining()!=0)throw new IOException("Trailing bytes in UTF palette value");
            var bytes=new ByteArrayOutputStream();new DataOutputStream(bytes).writeUTF(text);byte[] canonical=bytes.toByteArray();
            Blob result=Arrays.equals(value.bytes,canonical)?value:new Blob(canonical);result.fingerprintUtf=result;value.fingerprintUtf=result;return result;
        }
    }
    private static Blob normalizedNbt(Blob value)throws IOException{
        if(value==null)return null;Blob cached=value.fingerprintNbt;if(cached!=null)return cached;
        synchronized(value){
            if(value.fingerprintNbt!=null)return value.fingerprintNbt;
            if(value.bytes.length>MAX_NBT_BYTES)throw new IOException("BlockState NBT exceeds byte limit");
            Cursor in=new Cursor(value.bytes);if(in.u8()!=10)throw new IOException("Expected compound BlockState NBT");
            in.text(); // Native NbtIo reads an unnamed root and ignores its encoded root name.
            CanonicalValue root=canonicalPayload(in,10,0);
            if(in.remaining()!=0)throw new IOException("Trailing bytes in BlockState NBT");
            BoundedOutput bytes=new BoundedOutput(MAX_NBT_BYTES);var out=new DataOutputStream(bytes);
            out.writeByte(10);out.writeUTF("");root.write(out);out.flush();byte[] canonical=bytes.bytes.toByteArray();
            Blob result=Arrays.equals(value.bytes,canonical)?value:new Blob(canonical);result.fingerprintNbt=result;value.fingerprintNbt=result;return result;
        }
    }
    private interface CanonicalValue{void write(DataOutputStream out)throws IOException;}
    private record RawValue(byte[] bytes,int offset,int length)implements CanonicalValue{
        public void write(DataOutputStream out)throws IOException{out.write(bytes,offset,length);}
    }
    private record StringValue(String value)implements CanonicalValue{
        public void write(DataOutputStream out)throws IOException{out.writeUTF(value);}
    }
    private record NamedValue(int type,CanonicalValue value){}
    private record CompoundValue(SortedMap<String,NamedValue> fields)implements CanonicalValue{
        public void write(DataOutputStream out)throws IOException{
            for(var entry:fields.entrySet()){out.writeByte(entry.getValue().type);out.writeUTF(entry.getKey());entry.getValue().value.write(out);}out.writeByte(0);
        }
    }
    private record ListValue(int type,List<CanonicalValue> values)implements CanonicalValue{
        public void write(DataOutputStream out)throws IOException{out.writeByte(values.isEmpty()?0:type);out.writeInt(values.size());for(var value:values)value.write(out);}
    }
    private static CanonicalValue canonicalPayload(Cursor in,int type,int depth)throws IOException{
        if(depth>MAX_DEPTH)throw new IOException("BlockState NBT depth limit exceeded");in.value();
        return switch(type){
            case 1,2,3,4->{int offset=in.at;in.skip(type==1?1:type==2?2:type==3?4:8);yield new RawValue(in.data,offset,in.at-offset);}
            // Native FloatTag/DoubleTag.valueOf cache zero, and DataOutput canonicalizes NaNs.
            case 5->{float parsed=Float.intBitsToFloat(in.i32()),number=parsed==0?0:parsed;yield out->out.writeFloat(number);}
            case 6->{long bits=((long)in.i32()<<32)|(in.i32()&0xffffffffL);double parsed=Double.longBitsToDouble(bits),number=parsed==0?0:parsed;yield out->out.writeDouble(number);}
            case 7,11,12->{int offset=in.at;in.array(type==7?1:type==11?4:8);yield new RawValue(in.data,offset,in.at-offset);}
            case 8->new StringValue(in.text());
            case 9->{
                int element=in.u8(),count=in.i32();
                if(count<0||count>MAX_VALUES-in.values||element>12||(element==0&&count!=0))throw new IOException("Invalid NBT list");
                List<CanonicalValue> values=new ArrayList<>(Math.min(count,1024));for(int i=0;i<count;i++)values.add(canonicalPayload(in,element,depth+1));yield new ListValue(element,values);
            }
            case 10->{
                SortedMap<String,NamedValue> fields=new TreeMap<>();
                for(int tag=in.u8();tag!=0;tag=in.u8()){
                    if(tag>12)throw new IOException("Unknown NBT tag type");String name=in.text();
                    // Match native CompoundTag/NbtIo map semantics: the last duplicate name wins.
                    fields.put(name,new NamedValue(tag,canonicalPayload(in,tag,depth+1)));
                }yield new CompoundValue(fields);
            }
            default->throw new IOException("Unknown NBT tag type");
        };
    }
    private static final class DigestOutput extends OutputStream{
        final java.security.MessageDigest digest;int written;
        DigestOutput(java.security.MessageDigest digest){this.digest=digest;}
        public void write(int value)throws IOException{limit(1);digest.update((byte)value);}
        public void write(byte[] bytes,int offset,int length)throws IOException{limit(length);digest.update(bytes,offset,length);}
        void limit(int count)throws IOException{if(count<0||count>MAX_RAW_BYTES-written)throw new IOException("Encoded Xaero data exceeds byte limit");written+=count;}
    }
    private static final class BoundedOutput extends OutputStream{
        final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        final int maximum;
        BoundedOutput(){this(MAX_RAW_BYTES);}
        BoundedOutput(int maximum){this.maximum=maximum;}
        public void write(int value)throws IOException{limit(1);bytes.write(value);}
        public void write(byte[] value,int offset,int length)throws IOException{limit(length);bytes.write(value,offset,length);}
        private void limit(int count)throws IOException{if(count<0||count>maximum-bytes.size())throw new IOException("Encoded Xaero data exceeds byte limit");}
    }
    private static final class Cursor{
        final byte[] data;int at,values;
        final Map<Blob,Blob> canonical=new HashMap<>();
        Cursor(byte[] data){this.data=data;}
        int remaining(){return data.length-at;}
        void need(int count)throws IOException{if(count<0||count>remaining())throw new EOFException("Truncated Xaero region payload");}
        void skip(int count)throws IOException{need(count);at+=count;}
        int u8()throws IOException{need(1);return data[at++]&255;}
        int u16()throws IOException{return(u8()<<8)|u8();}
        int i32()throws IOException{return(u8()<<24)|(u8()<<16)|(u8()<<8)|u8();}
        void value()throws IOException{if(++values>MAX_VALUES)throw new IOException("Xaero value count limit exceeded");}
        Blob utf()throws IOException{
            int start=at;text();return new Blob(Arrays.copyOfRange(data,start,at));
        }
        String text()throws IOException{
            int start=at,length=u16();skip(length);
            // Validate Java's modified UTF without changing its original byte representation.
            return new DataInputStream(new ByteArrayInputStream(data,start,at-start)).readUTF();
        }
        Blob nbt()throws IOException{
            int start=at;
            if(u8()!=10)throw new IOException("Expected compound BlockState NBT");
            utf();payload(10,0,start);
            return new Blob(Arrays.copyOfRange(data,start,at));
        }
        void payload(int type,int depth,int start)throws IOException{
            if(depth>MAX_DEPTH||at-start>MAX_NBT_BYTES)throw new IOException("BlockState NBT limit exceeded");
            value();
            switch(type){
                case 1->skip(1);case 2->skip(2);case 3,5->skip(4);case 4,6->skip(8);
                case 7->array(1);case 8->utf();
                case 9->{
                    int element=u8(),count=i32();
                    if(count<0||count>MAX_VALUES-values||element>12||(element==0&&count!=0))throw new IOException("Invalid NBT list");
                    for(int i=0;i<count;i++)payload(element,depth+1,start);
                }
                case 10->{
                    for(int tag=u8();tag!=0;tag=u8()){
                        if(tag>12)throw new IOException("Unknown NBT tag type");
                        utf();payload(tag,depth+1,start);
                    }
                }
                case 11->array(4);case 12->array(8);
                default->throw new IOException("Unknown NBT tag type");
            }
            if(at-start>MAX_NBT_BYTES)throw new IOException("BlockState NBT exceeds byte limit");
        }
        void array(int width)throws IOException{
            int count=i32();
            if(count<0||count>MAX_NBT_BYTES/width)throw new IOException("NBT array length limit exceeded");
            skip(count*width);
        }
    }
}
