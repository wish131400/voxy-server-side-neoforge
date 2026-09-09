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
    private final PredictionDecorationLevel level;
    private String[] names;
    private boolean[] supported;
    private final int x, z;
    private int step;
    private long volume;
    private boolean javaChanged = true;
    private boolean nativeChanged;
    private boolean permit;
    private boolean disabled;

    RustVegetationStage(RustTerrainSampler sampler, PredictionDecorationLevel level,
                        int x, int z) {
        this.sampler = sampler; this.level = level; this.x = x; this.z = z;
    }

    void selectStep(List<PlacedFeature> features, int step) {
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
                volume=RustWorldgenBackend.surfaceProxy(sampler.handle(),x,z);
                RustWorldgenBackend.decorationEntropy(volume,java.util.concurrent.ThreadLocalRandom.current().nextLong());
            } catch (IllegalArgumentException unavailable) {
                close(); disabled=true;
                sampler.handle(); // A world cancellation must not produce a cacheable partial result.
                if (VSSClientConfig.CONFIG.debugLogging) VSSLogger.debug("VSS native vegetation context needs Java: " + unavailable);
                return false;
            }
        }
        if (javaChanged) upload();
        try {
            RustWorldgenBackend.placedFeature(volume, names[index], x, z, index, step);
            nativeChanged = true;
        } catch (IllegalArgumentException requiresJava) {
            // The native transaction restored both the blocks and random stream.
            supported[index] = false;
            sampler.handle();
            return false;
        }
        // Publish each transaction before starting another feature. This also
        // bounds the sparse transfer to one feature's write budget.
        download();
        sampler.nativeFeatureCompleted();
        return true;
    }
    void beforeJava() { download(); }
    void afterJava() { javaChanged = true; sampler.javaFeatureCompleted(); }
    void finish() { download(); }

    private void upload() {
        if (level.placed().size() > MAX_EDITS) throw new IllegalStateException("Native decoration edit budget exceeded");
        ByteBuffer buffer = BUFFERS.get(); buffer.clear();
        for (var entry : level.placed().entrySet()) {
            BlockPos p = entry.getKey();
            int state = sampler.stateId(entry.getValue());
            if (state < 0) throw new IllegalStateException("Block state absent from native snapshot");
            buffer.putInt(p.getX()).putInt(p.getY()).putInt(p.getZ()).putInt(state);
        }
        RustWorldgenBackend.applyEdits(volume, buffer, level.placed().size());
        javaChanged = false;
    }
    private void download() {
        if (!nativeChanged) return;
        ByteBuffer buffer = BUFFERS.get(); buffer.clear();
        int count = RustWorldgenBackend.readEdits(volume, buffer);
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
