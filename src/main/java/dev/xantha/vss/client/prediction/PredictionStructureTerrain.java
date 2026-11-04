package dev.xantha.vss.client.prediction;

import java.util.LinkedHashMap;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/** Exact generator answers, owned by one prediction world and active only during structure replay. */
public final class PredictionStructureTerrain {
    private static final ThreadLocal<PredictionStructureTerrain> ACTIVE = new ThreadLocal<>();
    private final Object generator, random;
    private record Key(int x, int z, int minY, int height, Heightmap.Types type) { }
    private final LinkedHashMap<Key,Integer> heights = new LinkedHashMap<>(64, .75f, true);
    private final LinkedHashMap<Key,BlockState[]> columns = new LinkedHashMap<>(64, .75f, true);
    PredictionStructureTerrain(Object generator, Object random) { this.generator=generator; this.random=random; }

    public static PredictionStructureTerrain current(Object generator, Object random) {
        var scope=ACTIVE.get();
        return scope!=null && scope.generator==generator && scope.random==random ? scope : null;
    }
    Scope enter() { var previous=ACTIVE.get(); ACTIVE.set(this); return new Scope(previous); }
    static final class Scope implements AutoCloseable {
        private final PredictionStructureTerrain previous;
        Scope(PredictionStructureTerrain previous) { this.previous=previous; }
        @Override public void close() { if(previous==null)ACTIVE.remove();else ACTIVE.set(previous); }
    }
    public synchronized Integer height(int x,int z,int minY,int height,Heightmap.Types type) {
        return heights.get(new Key(x,z,minY,height,type));
    }
    public synchronized void height(int x,int z,int minY,int height,Heightmap.Types type,int value) {
        heights.put(new Key(x,z,minY,height,type),value);
        if(heights.size()>16384)heights.remove(heights.keySet().iterator().next());
    }
    public NoiseColumn column(int x,int z,int minY,int height) {
        BlockState[] saved;
        synchronized(this) { saved=columns.get(new Key(x,z,minY,height,null)); }
        return saved==null ? null : new NoiseColumn(minY,saved.clone());
    }
    public void column(int x,int z,int minY,int height,NoiseColumn column) {
        var saved=new BlockState[height];
        for(int y=0;y<height;y++)saved[y]=column.getBlock(minY+y);
        synchronized(this) {
            columns.put(new Key(x,z,minY,height,null),saved);
            if(columns.size()>512)columns.remove(columns.keySet().iterator().next());
        }
    }
}
