package dev.xantha.vss.compat;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** Optional 2D projection of immutable prediction runs. Never requests more world generation. */
public final class XaeroPredictionBridge {
    private XaeroPredictionBridge() { }
    public record Run(int bottom, int top, BlockState state) { }
    public record Pixel(List<Run> runs, String biome) { }
    public record Quality(int terrainStep, int featureStep, boolean complete) {
        public boolean canReplace(Quality old) {
            return terrainStep <= old.terrainStep && featureStep <= old.featureStep
                    && (complete || !old.complete);
        }
    }
    /** Chunk bounds are inclusive/exclusive; even a huge LOD is represented by one cursor. */
    public record Area(Object key, int minX, int minZ, int maxX, int maxZ, long revision) { }
    public record Surface(Pixel[] pixels, Quality quality, BooleanSupplier current) { }
    public interface Source {
        long mapRevision();
        List<Area> mapAreas();
        Surface mapChunk(int chunkX, int chunkZ);
    }
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "vss-xaero-projection"); t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY); return t;
    });
    private static Context context;
    private static final class Cursor {
        final Area area; final int firstX,firstZ,width,height;
        int x,z; long offset;
        Cursor(Area area) {
            this.area=area; firstX=Math.floorDiv(area.minX,4); firstZ=Math.floorDiv(area.minZ,4);
            width=Math.floorDiv(area.maxX-1,4)-firstX+1; height=Math.floorDiv(area.maxZ-1,4)-firstZ+1;
            locate(); while(!inside()) { offset++; locate(); }
        }
        private void locate() {
            long group=offset/16;
            x=(firstX+(int)(group%width))*4+(int)(offset%4);
            z=(firstZ+(int)(group/width))*4+(int)(offset/4%4);
        }
        private boolean inside() { return x>=area.minX && z>=area.minZ && x<area.maxX && z<area.maxZ; }
        boolean advance() {
            do { if(++offset>=(long)width*height*16) return false; locate(); } while(!inside());
            return true;
        }
    }
    static List<Long> cursorForTest(Area area,int limit) {
        Cursor cursor=new Cursor(area); var result=new ArrayList<Long>();
        do { result.add(((long)cursor.x<<32)|(cursor.z&0xffffffffL)); }
        while(result.size()<limit && cursor.advance());
        return result;
    }
    static final class Context {
        final Object dimension; final Source source; final int bottom, top;
        final Map<Object, Long> seen = new HashMap<>();
        final LinkedHashMap<Object, Cursor> work = new LinkedHashMap<>();
        long revision = Long.MIN_VALUE;
        volatile boolean closed; boolean running;
        Context(Object dimension, Source source, int bottom, int top) {
            this.dimension=dimension; this.source=source; this.bottom=bottom; this.top=top;
        }
    }
    /** Client tick owns attachment. Re-enabling replays the source's current immutable tiles. */
    public static synchronized void attach(ResourceKey<Level> dimension, Source source, int bottom, int top) {
        if (!XaeroMapCompat.acceptsPrediction()) { detach(source); return; }
        if (context != null && context.source == source) return;
        reset(); context = new Context(dimension, source, bottom, top);
    }
    public static synchronized void detach(Source source) {
        if (context != null && context.source == source) reset();
    }
    static synchronized void reset() {
        if (context != null) { context.closed=true; context.work.clear(); context.seen.clear(); }
        context=null;
    }
    static synchronized boolean current(Context c) { return context == c && !c.closed; }
    static synchronized void tick() {
        Context c=context;
        if (c == null) return;
        if (!XaeroMapCompat.acceptsPrediction()) { reset(); return; }
        if (c.running || !XaeroMapCompat.predictionHasRoom()) return;
        if (c.work.isEmpty() && c.revision==c.source.mapRevision()) return;
        c.running=true;
        WORKER.execute(() -> project(c));
    }
    private static void project(Context c) {
        try {
            long revision=c.source.mapRevision();
            if (c.revision != revision) {
                List<Area> areas=c.source.mapAreas();
                Set<Object> live=new HashSet<>();
                synchronized (XaeroPredictionBridge.class) {
                    if (!current(c)) return;
                    for (Area a:areas) {
                        live.add(a.key);
                        Long old=c.seen.put(a.key,a.revision);
                        if (old == null || old.longValue()!=a.revision) c.work.put(a.key,new Cursor(a));
                    }
                    c.work.keySet().retainAll(live); c.seen.keySet().retainAll(live); c.revision=revision;
                }
            }
            long start=System.nanoTime();
            for (int n=0;n<8 && System.nanoTime()-start<8_000_000L;n++) {
                Cursor cursor; int x,z;
                synchronized (XaeroPredictionBridge.class) {
                    if (!current(c) || c.work.isEmpty() || !XaeroMapCompat.predictionHasRoom()) return;
                    var it=c.work.entrySet().iterator(); var first=it.next(); cursor=first.getValue();
                    x=cursor.x; z=cursor.z;
                    if (!cursor.advance()) it.remove();
                    else if (cursor.offset%16==0) { it.remove(); c.work.put(first.getKey(),cursor); }
                    // Finish a 4x4 tile chunk together so Xaero rebuilds its texture once.
                }
                Surface surface=c.source.mapChunk(x,z);
                if (surface == null || !surface.current.getAsBoolean()) continue;
                var tile=XaeroTileExtractor.extractPrediction(x,z,c.bottom,c.top,surface);
                // Disconnect, dimension changes and disabling all revoke the context, including in-flight work.
                synchronized (XaeroPredictionBridge.class) {
                    if (!current(c)) return;
                    XaeroMapCompat.offerPrediction(c,tile,surface.quality,surface.current);
                }
            }
        } catch (Exception failure) {
            dev.xantha.vss.common.VSSLogger.warn("Xaero prediction projection failed",failure);
        } finally {
            synchronized (XaeroPredictionBridge.class) { c.running=false; }
        }
    }
    static void awaitProjectionForTest() throws Exception { WORKER.submit(() -> {}).get(10,TimeUnit.SECONDS); }
}
