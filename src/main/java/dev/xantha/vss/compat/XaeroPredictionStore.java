package dev.xantha.vss.compat;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Small regional sidecars: provenance survives Xaero region unloads and reconnects.
 * Only matching content can be refined; unknown/changed map content is authoritative.
 * No disk operation runs on a game thread. Failures conservatively make existing tiles read-only. */
final class XaeroPredictionStore {
    record Stamp(long hash, XaeroPredictionBridge.Quality quality) { }
    private static final int MAGIC=0x56535031, MAX_REGIONS=32;
    private static final ExecutorService IO=Executors.newSingleThreadExecutor(r -> {
        Thread t=new Thread(r,"vss-xaero-provenance"); t.setDaemon(true); return t;
    });
    private record Key(Path root,int x,int z) { }
    private static final class Region {
        final Key key;
        volatile boolean loaded;
        boolean writing, failed;
        long version;
        final Map<Integer,Stamp> stamps=new HashMap<>();
        final Map<Integer,Stamp> pending=new HashMap<>();
        Region(Key key) { this.key=key; }
    }
    private final LinkedHashMap<Key,Region> regions=new LinkedHashMap<>(32,.75f,true);
    synchronized Region region(Path root,int x,int z) {
        Key key=new Key(root,x>>5,z>>5);
        Region r=regions.get(key);
        if (r==null) {
            // Pending loads/saves retain their slot; bounded even under stalled storage.
            var it=regions.values().iterator();
            while (regions.size()>=MAX_REGIONS && it.hasNext()) {
                Region old=it.next(); if (old.loaded && !old.writing) it.remove();
            }
            if (regions.size()>=MAX_REGIONS) return null;
            r=new Region(key); regions.put(key,r); Region load=r; IO.execute(() -> load(load));
        }
        return r;
    }
    synchronized boolean ready(Path root,int x,int z) { Region r=region(root,x,z); return r!=null && r.loaded; }
    synchronized Stamp get(Path root,int x,int z) {
        Region r=region(root,x,z); return r==null ? null : r.stamps.get(index(x,z));
    }
    synchronized void put(Path root,int x,int z,Stamp stamp) {
        Region r=region(root,x,z);
        if (r==null || r.failed) return;
        if (!r.loaded) {
            // Coalesce real removals while loading; never enqueue one IO task per delivered column.
            r.pending.put(index(x,z),stamp); return;
        }
        if (stamp==null) { if(r.stamps.remove(index(x,z))==null) return; }
        else r.stamps.put(index(x,z),stamp);
        r.version++;
        if (!r.writing) { r.writing=true; IO.execute(() -> save(r)); }
    }
    private static int index(int x,int z) { return (x&31)*32+(z&31); }
    private static Path path(Key key) { return key.root.resolve("vss-prediction").resolve("r."+key.x+"."+key.z+".dat"); }
    private void load(Region r) {
        var stamps=new HashMap<Integer,Stamp>(); boolean failed=false;
        Path file=path(r.key);
        try (var in=new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (in.readInt()!=MAGIC) throw new IOException("Unknown prediction provenance version");
            int count=in.readInt(); if(count<0 || count>1024) throw new IOException("Invalid provenance count");
            for(int n=0;n<count;n++) {
                int index=in.readUnsignedShort(); long hash=in.readLong();
                int terrain=in.readInt(),feature=in.readInt(); boolean complete=in.readBoolean();
                if(index>=1024 || terrain<=0 || feature<=0) throw new IOException("Invalid prediction provenance");
                stamps.put(index,new Stamp(hash,new XaeroPredictionBridge.Quality(terrain,feature,complete)));
            }
        } catch(NoSuchFileException absent) {
            // No sidecar means all existing map tiles are real/unknown, never prediction-owned.
        } catch(IOException error) { failed=true; }
        synchronized(this) {
            r.failed=failed;
            if(!failed) {
                r.stamps.putAll(stamps);
                boolean changed=false;
                for(var e:r.pending.entrySet()) {
                    if(e.getValue()==null) changed |= r.stamps.remove(e.getKey())!=null;
                    else { r.stamps.put(e.getKey(),e.getValue()); changed=true; }
                }
                if(changed) { r.version++; r.writing=true; IO.execute(() -> save(r)); }
            }
            r.pending.clear(); r.loaded=true;
        }
    }
    private void save(Region r) {
        for(;;) {
            Map<Integer,Stamp> snapshot; long version;
            synchronized(this) { snapshot=new HashMap<>(r.stamps); version=r.version; }
            Path file=path(r.key),temp=file.resolveSibling(file.getFileName()+".tmp");
            try {
                Files.createDirectories(file.getParent());
                try(var out=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))) {
                    out.writeInt(MAGIC); out.writeInt(snapshot.size());
                    for(var e:snapshot.entrySet()) {
                        var s=e.getValue(); out.writeShort(e.getKey()); out.writeLong(s.hash);
                        out.writeInt(s.quality.terrainStep()); out.writeInt(s.quality.featureStep());
                        out.writeBoolean(s.quality.complete());
                    }
                }
                try { Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
                catch(AtomicMoveNotSupportedException fallback) { Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING); }
            } catch(IOException failure) {
                synchronized(this) { r.failed=true; r.writing=false; r.stamps.clear(); } return;
            }
            synchronized(this) { if(r.version==version) { r.writing=false; return; } }
        }
    }
    synchronized void clear() { regions.clear(); }
    synchronized int cachedRegionsForTest() { return regions.size(); }
    static void awaitIoForTest() throws Exception { IO.submit(() -> {}).get(10,TimeUnit.SECONDS); }
}
