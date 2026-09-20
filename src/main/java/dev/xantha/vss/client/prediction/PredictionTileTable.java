package dev.xantha.vss.client.prediction;

import java.util.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;

/** Immutable snapshot pages: a changed tile copies its hash page, not the whole world. */
final class PredictionTileTable<V> extends AbstractMap<PredictionTileKey,V> {
    private static final int BUCKETS=32;
    private record Item<V>(PredictionTileKey key,V value) implements Map.Entry<PredictionTileKey,V> {
        public PredictionTileKey getKey() { return key; }
        public V getValue() { return value; }
        public V setValue(V value) { throw new UnsupportedOperationException("immutable tile snapshot"); }
        @Override public boolean equals(Object other) {
            return other instanceof Map.Entry<?,?> entry && Objects.equals(key,entry.getKey()) && Objects.equals(value,entry.getValue());
        }
        @Override public int hashCode() { return Objects.hashCode(key)^Objects.hashCode(value); }
    }
    private final ResourceKey<Level> dimension;
    private final Long2ObjectOpenHashMap<Item<V>>[] pages;
    private final int size;
    @SuppressWarnings("unchecked")
    PredictionTileTable(ResourceKey<Level> dimension) {
        this(dimension,new Long2ObjectOpenHashMap[(PredictionTileManager.MAX_LOD_LEVEL+1)*BUCKETS],0);
    }
    private PredictionTileTable(ResourceKey<Level> dimension,Long2ObjectOpenHashMap<Item<V>>[] pages,int size) {
        this.dimension=dimension;this.pages=pages;this.size=size;
    }

    PredictionTileTable<V> changed(Map<PredictionTileKey,V> upserts,Collection<PredictionTileKey> removed) {
        if(upserts.isEmpty() && removed.isEmpty()) return this;
        var next=pages.clone();boolean[] copied=new boolean[pages.length];int nextSize=size;
        for(var key:removed) {
            if(!valid(key)) continue;
            long at=pack(key.tileX(),key.tileZ());int page=page(key.lod(),at);
            if(next[page]==null || !next[page].containsKey(at)) continue;
            copy(next,copied,page);next[page].remove(at);nextSize--;
            if(next[page].isEmpty()) next[page]=null;
        }
        for(var entry:upserts.entrySet()) {
            var key=entry.getKey();if(!valid(key)) continue;
            Objects.requireNonNull(entry.getValue(),"tile snapshot value");
            long at=pack(key.tileX(),key.tileZ());int page=page(key.lod(),at);
            var old=next[page]==null?null:next[page].get(at);
            if(old!=null && old.value()==entry.getValue()) continue;
            copy(next,copied,page);
            if(next[page]==null) next[page]=new Long2ObjectOpenHashMap<>();
            next[page].put(at,new Item<>(key,entry.getValue()));if(old==null) nextSize++;
        }
        return new PredictionTileTable<>(dimension,next,nextSize);
    }
    private static <V> void copy(Long2ObjectOpenHashMap<Item<V>>[] next,boolean[] copied,int page) {
        if(copied[page]) return;
        if(next[page]!=null) next[page]=next[page].clone();copied[page]=true;
    }
    private boolean valid(PredictionTileKey key) {
        return key.dimension().equals(dimension) && key.lod()>=0 && key.lod()<=PredictionTileManager.MAX_LOD_LEVEL;
    }
    V at(int x,int z,int lod) {
        if(lod<0 || lod>PredictionTileManager.MAX_LOD_LEVEL) return null;
        long key=pack(x,z);var values=pages[page(lod,key)];var entry=values==null?null:values.get(key);
        return entry==null?null:entry.value();
    }
    @Override public V get(Object key) {
        return key instanceof PredictionTileKey tile && valid(tile)?at(tile.tileX(),tile.tileZ(),tile.lod()):null;
    }
    @Override public boolean containsKey(Object key) { return get(key)!=null; }
    @Override public int size() { return size; }
    @Override public Set<Map.Entry<PredictionTileKey,V>> entrySet() {
        return new AbstractSet<>() {
            @Override public int size() { return size; }
            @Override public Iterator<Map.Entry<PredictionTileKey,V>> iterator() {
                return new Iterator<>() {
                    private int page;
                    private Iterator<Item<V>> current=Collections.emptyIterator();
                    public boolean hasNext() {
                        while(!current.hasNext() && page<pages.length) {
                            var values=pages[page++];if(values!=null) current=values.values().iterator();
                        }
                        return current.hasNext();
                    }
                    public Map.Entry<PredictionTileKey,V> next() {
                        if(!hasNext()) throw new NoSuchElementException();return current.next();
                    }
                };
            }
        };
    }
    private static int page(int lod,long key) {
        return lod*BUCKETS+((int)it.unimi.dsi.fastutil.HashCommon.mix(key)&(BUCKETS-1));
    }
    private static long pack(int x,int z) { return (long)x<<32 | z&0xffffffffL; }
}
