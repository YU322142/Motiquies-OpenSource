package com.mapsyncer.server;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** No game objects or IO in mark/take/finish. Times use one caller-provided monotonic unit. */
public final class DirtyRegionQueue {
    private static final int MAGIC=0x4d514431;
    private static final java.util.regex.Pattern DIMENSION=java.util.regex.Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    public record Key(String dimension,int regionX,int regionZ) {
        public Key {
            if(dimension==null||dimension.length()>256||!DIMENSION.matcher(dimension).matches())throw new IllegalArgumentException("invalid dimension");
        }
        public static Key fromChunk(String dimension,int chunkX,int chunkZ){return new Key(dimension,chunkX>>5,chunkZ>>5);}
    }
    public record Work(Key key,long revision,boolean fullDimension) {}
    private static final class Entry {
        long revision,first,last,retryAt;int failures;
        boolean backingOff;
        Key cursor;
        boolean rescan;
        Entry(long revision,long now){this.revision=revision;first=last=now;}
    }
    private final int limit;
    private final long debounce,maxWait,retry;
    private final Map<Key,Entry> regions=new HashMap<>();
    private final Map<String,Entry> dimensions=new HashMap<>();
    private long revision;
    private Work inFlight;
    private final Set<Key> duringListing=new HashSet<>();
    private boolean listingOverflow;
    private boolean closed;
    public DirtyRegionQueue(int limit,long debounce,long maxWait,long retry){
        if(limit<1||limit>65536||debounce<0||maxWait<debounce||retry<1)throw new IllegalArgumentException();
        this.limit=limit;this.debounce=debounce;this.maxWait=maxWait;this.retry=retry;
    }
    public synchronized boolean mark(Key key,long now){
        if(closed)return false;
        Entry region=regions.get(key);
        if(region!=null){touch(region,now,inFlight!=null&&!inFlight.fullDimension()&&inFlight.key().equals(key));return true;}
        Entry entry=dimensions.get(key.dimension());
        if(entry!=null){
            boolean listing=inFlight!=null&&inFlight.fullDimension()&&inFlight.key().dimension().equals(key.dimension());
            if(listing){if(duringListing.size()<limit)duringListing.add(key);else listingOverflow=true;}
            if(entry.cursor!=null&&ORDER.compare(key,entry.cursor)<=0){
                if(regions.size()<limit)regions.put(key,new Entry(++revision,now));
                else entry.rescan=true;
            }
            touch(entry,now,listing);return true;
        }
        if(regions.size()>=limit){
            long first=now;
            var iter=regions.entrySet().iterator();
            while(iter.hasNext()){
                var row=iter.next();if(!row.getKey().dimension().equals(key.dimension()))continue;
                first=Math.min(first,row.getValue().first);iter.remove();
            }
            entry=new Entry(++revision,now);entry.first=first;dimensions.put(key.dimension(),entry);
        }else regions.put(key,new Entry(++revision,now));
        return true;
    }
    public synchronized boolean markDimension(String dimension,long now){
        new Key(dimension,0,0);
        if(closed)return false;
        regions.entrySet().removeIf(e->e.getKey().dimension().equals(dimension));
        Entry entry=dimensions.get(dimension);
        if(entry==null)dimensions.put(dimension,new Entry(++revision,now));
        else {
            boolean listing=inFlight!=null&&inFlight.fullDimension()&&inFlight.key().dimension().equals(dimension);
            if(listing)listingOverflow=true;
            entry.cursor=null;entry.rescan=true;
            touch(entry,now,listing);
        }
        return true;
    }
    private void touch(Entry entry,long now,boolean running){
        if(running&&inFlight.revision()==entry.revision)entry.first=now;
        entry.revision=++revision;entry.last=now;
    }
    private boolean ready(Entry e,long now){return (!e.backingOff||now-e.retryAt>=0)&&(now-e.last>=debounce||now-e.first>=maxWait);}
    public synchronized Work take(long now,boolean pressure,boolean generationBusy){
        if(closed||pressure||generationBusy||inFlight!=null)return null;
        Work selected=null;Entry oldest=null;
        for(var row:dimensions.entrySet()){
            Entry e=row.getValue();if(regions.size()<limit&&!hasRegions(row.getKey())&&ready(e,now)&&(oldest==null||e.first<oldest.first)){
                selected=new Work(new Key(row.getKey(),0,0),e.revision,true);oldest=e;
            }
        }
        for(var row:regions.entrySet()){
            Entry e=row.getValue();if(ready(e,now)&&(oldest==null||e.first<oldest.first)){
                selected=new Work(row.getKey(),e.revision,false);oldest=e;
            }
        }
        inFlight=selected;duringListing.clear();listingOverflow=false;return selected;
    }
    private static final Comparator<Key> ORDER=Comparator.comparingInt(Key::regionX).thenComparingInt(Key::regionZ);
    private boolean hasRegions(String dimension){for(Key key:regions.keySet())if(key.dimension().equals(dimension))return true;return false;}
    /** Expand the next bounded batch; retain a durable cursor while more keys remain. */
    public synchronized boolean expandDimension(Work work,Collection<Key> listed,long now){
        if(closed||work==null||!work.equals(inFlight)||!work.fullDimension())return false;
        Entry entry=dimensions.get(work.key().dimension());if(entry==null)return false;
        int capacity=limit-regions.size();if(capacity<1)return false;
        // Retain only the next bounded lexicographic batch, independent of the
        // filesystem's directory order. The cursor survives normal restart.
        NavigableSet<Key> keys=new TreeSet<>(ORDER);
        boolean more=false;
        for(Key key:listed){
            if(!key.dimension().equals(work.key().dimension()))throw new IllegalArgumentException("dimension mismatch");
            if(entry.cursor!=null&&ORDER.compare(key,entry.cursor)<=0)continue;
            keys.add(key);if(keys.size()>capacity){keys.pollLast();more=true;}
        }
        for(Key key:duringListing){
            if(entry.cursor!=null&&ORDER.compare(key,entry.cursor)<=0){
                // A save racing enumeration before the cursor needs a later
                // sweep if it could not obtain a separate region slot.
                if(!regions.containsKey(key))entry.rescan=true;
            }else{keys.add(key);if(keys.size()>capacity){keys.pollLast();more=true;}}
        }
        if(listingOverflow)entry.rescan=true;
        if(keys.isEmpty()){
            if(entry.rescan){entry.cursor=null;entry.rescan=false;entry.revision=++revision;entry.first=entry.last=now-debounce;}
            else dimensions.remove(work.key().dimension());
        }else{
            entry.cursor=keys.last();entry.revision=++revision;entry.first=entry.last=now-debounce;
            if(!more&&!entry.rescan)dimensions.remove(work.key().dimension());
        }
        inFlight=null;duringListing.clear();listingOverflow=false;
        for(Key key:keys)regions.put(key,new Entry(++revision,now-debounce));
        return true;
    }
    public synchronized void finish(Work work,boolean success,long now){finish(work,success,false,now);}
    public synchronized void defer(Work work,long now){finish(work,false,true,now);}
    private void finish(Work work,boolean success,boolean deferred,long now){
        if(closed||work==null||!work.equals(inFlight))return;
        inFlight=null;
        duringListing.clear();listingOverflow=false;
        Entry e=work.fullDimension()?dimensions.get(work.key().dimension()):regions.get(work.key());if(e==null)return;
        if(success&&e.revision==work.revision()){
            if(work.fullDimension())dimensions.remove(work.key().dimension());else regions.remove(work.key());
        }else if(success){e.failures=0;e.backingOff=false;}
        else {
            long delay=deferred?Math.max(1,retry/5):retry*(1L<<Math.min(e.failures++,3));
            e.retryAt=now+delay;
            e.backingOff=true;
        }
    }
    public synchronized int size(){return regions.size()+dimensions.size();}
    public synchronized long revision(){return revision;}
    public synchronized void close(){closed=true;}
    private record Pending(Key key,long revision,boolean full,Key cursor,boolean rescan) {}
    /** Merge a loaded journal into an already-published queue without replacing early saves.
     * Existing new full markers/overflow win conservatively; otherwise preserve the old
     * bounded-listing cursor and all explicit regions, including newer keys behind it.
     */
    public int replayInto(DirtyRegionQueue target,long now){
        Objects.requireNonNull(target);if(target==this)throw new IllegalArgumentException("Cannot replay a queue into itself");
        List<Pending> copy=new ArrayList<>();long latest;
        synchronized(this){
            latest=revision;
            regions.forEach((key,e)->copy.add(new Pending(key,e.revision,false,null,false)));
            dimensions.forEach((dim,e)->copy.add(new Pending(new Key(dim,0,0),e.revision,true,e.cursor,e.rescan)));
        }
        target.recoveryRevision(latest);
        for(Pending entry:copy)target.recover(entry,now);
        return copy.size();
    }
    private synchronized void recoveryRevision(long latest){
        if(closed)throw new IllegalStateException("Recovery queue already closed");
        revision=Math.max(revision,latest);
    }
    private synchronized void recover(Pending pending,long now){
        if(closed)throw new IllegalStateException("Recovery queue already closed");
        if(!pending.full()){mark(pending.key(),now);return;}
        String dimension=pending.key().dimension();
        if(dimensions.containsKey(dimension)){markDimension(dimension,now);return;}
        Entry entry=new Entry(++revision,now-debounce);entry.cursor=pending.cursor();entry.rescan=pending.rescan();
        dimensions.put(dimension,entry); // Keep independently marked/pending regions of this dimension.
    }
    public void save(Path file)throws IOException{
        List<Pending> copy=new ArrayList<>();long latest;
        synchronized(this){
            latest=revision;
            regions.forEach((key,e)->copy.add(new Pending(key,e.revision,false,null,false)));
            dimensions.forEach((dim,e)->copy.add(new Pending(new Key(dim,0,0),e.revision,true,e.cursor,e.rescan)));
        }
        file=file.toAbsolutePath().normalize();Path parent=file.getParent();Files.createDirectories(parent);
        if(Files.isSymbolicLink(file)||Files.isSymbolicLink(parent))throw new IOException("Journal symlinks are not allowed");
        Path temp=Files.createTempFile(parent,".dirty-regions-",".tmp");
        try{
            try(var out=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))){
                out.writeInt(MAGIC);out.writeInt(2);out.writeLong(latest);out.writeInt(copy.size());
                for(Pending entry:copy){out.writeBoolean(entry.full());out.writeUTF(entry.key().dimension());out.writeInt(entry.key().regionX());out.writeInt(entry.key().regionZ());out.writeLong(entry.revision());out.writeBoolean(entry.cursor()!=null);if(entry.cursor()!=null){out.writeInt(entry.cursor().regionX());out.writeInt(entry.cursor().regionZ());}out.writeBoolean(entry.rescan());}
            }
            try(var channel=java.nio.channels.FileChannel.open(temp,StandardOpenOption.WRITE)){channel.force(true);}
            try{Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException e){Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(temp);}
    }
    public static DirtyRegionQueue load(Path file,int limit,long debounce,long maxWait,long retry,long now)throws IOException{
        var q=new DirtyRegionQueue(limit,debounce,maxWait,retry);
        if(!Files.exists(file))return q;
        if(Files.isSymbolicLink(file)||Files.size(file)>32*1024*1024L)throw new IOException("Invalid dirty journal path/size");
        try(var in=new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))){
            if(in.readInt()!=MAGIC)throw new IOException("Unsupported dirty journal");int version=in.readInt();if(version!=1&&version!=2)throw new IOException("Unsupported dirty journal");
            q.revision=in.readLong();int size=in.readInt();if(q.revision<0||size<0||size>131072)throw new IOException("Invalid journal bounds");
            for(int i=0;i<size;i++){
                boolean full=in.readBoolean();Key key=new Key(in.readUTF(),in.readInt(),in.readInt());long revision=in.readLong();
                if(revision<1||revision>q.revision)throw new IOException("Invalid revision");
                Entry e=new Entry(revision,now-debounce);
                if(version==2){if(in.readBoolean())e.cursor=new Key(key.dimension(),in.readInt(),in.readInt());e.rescan=in.readBoolean();if(!full&&(e.cursor!=null||e.rescan))throw new IOException("Invalid region cursor");}
                if(full){if(q.dimensions.put(key.dimension(),e)!=null)throw new IOException("Duplicate dimension");}
                else if(q.regions.put(key,e)!=null)throw new IOException("Duplicate region");
            }
            if(in.read()!=-1||q.regions.size()>limit)throw new IOException("Invalid journal tail/count");
            if(version==1)for(Key key:q.regions.keySet())if(q.dimensions.containsKey(key.dimension()))throw new IOException("Redundant full dimension entry");
        }catch(IllegalArgumentException e){throw new IOException("Invalid journal data",e);}
        return q;
    }
}
