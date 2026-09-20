package dev.xantha.vss.client.prediction;

import com.google.gson.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.config.VSSClientConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/** One native neighbourhood per decoration job; Java mod features can join the same ordered stream. */
final class RustVegetationStage implements AutoCloseable {
    private static final int MAX_EDITS = 262_144;
    private static final Semaphore VOLUMES = new Semaphore(8,true);
    private static final ThreadLocal<ByteBuffer> BUFFERS = ThreadLocal.withInitial(() ->
            ByteBuffer.allocateDirect(MAX_EDITS * 16).order(ByteOrder.LITTLE_ENDIAN));
    private final RustTerrainSampler sampler;
    // P0-01: a stage is created per decoration job, so the round-trip costs are
    // accumulated statically. These separate the three JNI calls the decoration
    // path makes from the Java work around them.
    private static final java.util.concurrent.atomic.LongAdder PROXY_NANOS = new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder FEATURE_NANOS = new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder UPLOAD_NANOS = new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder DOWNLOAD_NANOS = new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder PROXIES = new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder FEATURES_PLACED = new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder EDITS_UP = new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder EDITS_DOWN = new java.util.concurrent.atomic.LongAdder();

    /** Counters for the decoration round trips; included in the sampler diagnostics. */
    static String diagnostics() {
        return "stageDetail={proxyMs=" + PROXY_NANOS.sum() / 1_000_000
                + ",featureMs=" + FEATURE_NANOS.sum() / 1_000_000
                + ",uploadMs=" + UPLOAD_NANOS.sum() / 1_000_000
                + ",downloadMs=" + DOWNLOAD_NANOS.sum() / 1_000_000
                + ",proxies=" + PROXIES.sum()
                + ",featuresPlaced=" + FEATURES_PLACED.sum()
                + ",editsUp=" + EDITS_UP.sum()
                + ",editsDown=" + EDITS_DOWN.sum() + "}";
    }

    private final PredictionDecorationLevel level;
    private String[] names;
    private boolean[] supported;
    private final int x, z;
    private int step;
    private long volume;
    private boolean javaChanged = true;
    private boolean nativeChanged;
    // Whether the current native volume has received the placed map at least
    // once. A freshly created proxy only holds column summaries, so the first
    // transfer after creation must always happen.
    private boolean synced;
    private boolean permit;
    private boolean disabled;
    private final boolean visualPlants;
    private boolean displayProxy;

    RustVegetationStage(RustTerrainSampler sampler, PredictionDecorationLevel level,
                        int x, int z) {
        this(sampler,level,x,z,false);
    }
    RustVegetationStage(RustTerrainSampler sampler, PredictionDecorationLevel level,
                        int x, int z, boolean visualPlants) {
        this.sampler = sampler; this.level = level; this.x = x; this.z = z;
        this.visualPlants = visualPlants;
    }

    void selectStep(List<PlacedFeature> features, int step) {
        boolean display = !sampler.interiorTerrain() && visualPlants && step == net.minecraft.world.level.levelgen.GenerationStep.Decoration.VEGETAL_DECORATION.ordinal();
        if (display != displayProxy) { finish(); close(); synced=false; displayProxy=display; }
        this.step = step;
        // Structures and Java features may have changed the shared level
        // between steps. Preserve their edits when reusing the native volume.
        javaChanged = true;
        names = new String[features.size()]; supported = new boolean[features.size()];
        var registry = sampler.decorationContext().decorationAccess().registryOrThrow(Registries.PLACED_FEATURE);
        String[][] order = sampler.featureOrder();
        for (int i = 0; i < features.size(); i++) {
            var key = registry.getKey(features.get(i));
            if (key == null) continue;
            names[i] = key.toString();
            if (step >= order.length || i >= order[step].length || !names[i].equals(order[step][i])) continue;
            supported[i] = sampler.supports(names[i]);
        }
    }

    boolean place(int index) {
        if (disabled || !supported[index]) return false;
        if (volume == 0) {
            try {
                if (!VOLUMES.tryAcquire()) throw new PredictionWorkDeferred();
                permit=true;
                long proxyStarted = System.nanoTime();
                volume=RustWorldgenBackend.decorationProxy(sampler.handle(),x,z,sampler.interiorTerrain() ? 2 : displayProxy ? 1 : 0);
                RustWorldgenBackend.decorationEntropy(volume,java.util.concurrent.ThreadLocalRandom.current().nextLong());
                PROXY_NANOS.add(System.nanoTime() - proxyStarted);
                PROXIES.increment();
                // A new proxy starts from column summaries only; whatever Java
                // placed earlier is not in it yet.
                synced = false;
            } catch (IllegalArgumentException unavailable) {
                close(); disabled=true;
                sampler.handle(); // A world cancellation must not produce a cacheable partial result.
                if (VSSClientConfig.CONFIG.debugLogging) VSSLogger.debug("VSS native vegetation context needs Java: " + unavailable);
                return false;
            }
        }
        if (javaChanged) upload();
        try {
            long featureStarted = System.nanoTime();
            RustWorldgenBackend.placedFeature(volume, names[index], x, z, index, step);
            FEATURE_NANOS.add(System.nanoTime() - featureStarted);
            FEATURES_PLACED.increment();
            nativeChanged = true;
        } catch (IllegalArgumentException requiresJava) {
            // The native transaction restored both the blocks and random stream.
            supported[index] = false;
            sampler.handle();
            return false;
        }
        // Publish each transaction before starting another feature. This also
        // bounds the sparse transfer to one feature's write budget.
        level.useDisplayTerrain(displayProxy);
        download();
        sampler.nativeFeatureCompleted();
        return true;
    }
    void beforeJava() { download(); }
    void afterJava() { javaChanged = true; sampler.javaFeatureCompleted(); }
    void finish() { download(); }

    private void upload() {
        // Nothing was written since the last successful transfer, so both sides
        // already agree and the whole placed map can be skipped. `selectStep`
        // sets `javaChanged` conservatively because structures may touch the
        // level between steps, so that flag alone must not force a rewrite -
        // `pendingUploads` is what actually proves something changed, and every
        // write path (including rollbacks) registers there.
        if (synced && level.pendingUploads().isEmpty()) {
            javaChanged = false;
            return;
        }
        if (level.placed().size() > MAX_EDITS) throw new IllegalStateException("Native decoration edit budget exceeded");
        long started = System.nanoTime();
        ByteBuffer buffer = BUFFERS.get(); buffer.clear();
        for (var entry : level.placed().entrySet()) {
            BlockPos p = entry.getKey();
            int state = sampler.stateId(entry.getValue());
            if (state < 0) throw new IllegalStateException("Block state absent from native snapshot");
            buffer.putInt(p.getX()).putInt(p.getY()).putInt(p.getZ()).putInt(state);
        }
        RustWorldgenBackend.applyEdits(volume, buffer, level.placed().size());
        UPLOAD_NANOS.add(System.nanoTime() - started);
        EDITS_UP.add(level.placed().size());
        // Only after the transfer succeeded: a thrown transfer must leave the
        // set intact so the next attempt still sees the pending writes.
        level.clearPendingUploads();
        synced = true;
        javaChanged = false;
    }
    private void download() {
        if (!nativeChanged) return;
        long started = System.nanoTime();
        ByteBuffer buffer = BUFFERS.get(); buffer.clear();
        int count = RustWorldgenBackend.readEdits(volume, buffer);
        DOWNLOAD_NANOS.add(System.nanoTime() - started);
        EDITS_DOWN.add(count);
        if (count < 0 || count > MAX_EDITS) throw new IllegalStateException("Invalid native vegetation output");
        BlockState[] states = sampler.states();
        // Validate the whole result before publishing it to the Java context.
        for (int i = 0; i < count; i++) {
            int id = buffer.getInt(i * 16 + 12);
            if (id < 0 || id >= states.length) {
                var table=JsonParser.parseString(RustWorldgenBackend.describe(volume)).getAsJsonObject().getAsJsonArray("states");
                throw new IllegalStateException("Native result used an unsynchronised state " + id + "/" + states.length
                        + ": " + (id>=0&&id<table.size()?table.get(id):"invalid"));
            }
            if (!level.ensureCanWrite(new BlockPos(buffer.getInt(i*16),buffer.getInt(i*16+4),buffer.getInt(i*16+8))))
                throw new IllegalStateException("Native result exceeded its write region");
        }
        level.beginFeature();
        boolean complete = false;
        try {
            for (int i = 0; i < count; i++) {
                int base = i * 16;
                BlockPos p = new BlockPos(buffer.getInt(base), buffer.getInt(base + 4), buffer.getInt(base + 8));
                BlockState state = states[buffer.getInt(base + 12)];
                if (level.placed().get(p) != state) level.setBlock(p, state, 19, 0);
            }
            complete = true;
        } finally { level.endFeature(complete); }
        nativeChanged = false;
    }
    @Override public void close() {
        try { if (volume != 0) { RustWorldgenBackend.close(volume); volume = 0; } }
        finally { if (permit) { permit=false;VOLUMES.release(); } }
    }
}
