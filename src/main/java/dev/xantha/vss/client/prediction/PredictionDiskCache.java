package dev.xantha.vss.client.prediction;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.config.VSSClientConfig;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

/** Compressed generation results. IO runs on builders or the serial commit worker, never on the render thread. */
final class PredictionDiskCache implements AutoCloseable {
    private static final int MAGIC = 0x56535044, SCHEMA = 2;
    private static final int MAX_COLUMNS = 66 * 66, MAX_BLOCKS = 262_144;
    private static final ExecutorService COMMITS = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "vss-prediction-disk");
        thread.setDaemon(true);
        return thread;
    });
    private static final ConcurrentMap<Path, Shared> ROOTS = new ConcurrentHashMap<>();
    private static final class Shared {
        volatile PredictionDiskCache owner;
        final Map<Key, Entry> active = new HashMap<>();
        final Map<Key, Long> invalidations = new HashMap<>();
        long revision;
        boolean deleting;
    }
    private static final class Entry { volatile boolean valid = true; int users; }
    record Key(int kind, int x, int z, int detail) {
        static Key terrain(int x, int z, int lod) { return new Key(0, x, z, lod); }
        static Key surface(int x, int z, int settings) { return new Key(1, x, z, settings); }
    }
    // Schema 3 includes surface features from their actual decoration stages.
    // Old empty/vegetation-only surface entries must regenerate; terrain stays reusable.
    private static int schema(Key key) { return key.kind == 1 ? 3 : SCHEMA; }
    final class Lease implements AutoCloseable {
        final Key key;
        final Entry entry;
        final boolean readable;
        private volatile boolean released;
        Lease(Key key, Entry entry, boolean readable) { this.key = key; this.entry = entry; this.readable = readable; }
        boolean valid() { return !released && entry.valid && !closed && shared.owner == PredictionDiskCache.this; }
        @Override public void close() {
            synchronized (shared) {
                if (released) return;
                released = true;
                if (--entry.users == 0) shared.active.remove(key, entry);
            }
        }
    }
    private final Path root;
    private final long fingerprint;
    private final Shared shared;
    private volatile boolean closed;
    private final LongAdder hits = new LongAdder(), misses = new LongAdder(), writes = new LongAdder(), errors = new LongAdder();
    private final Map<String, BlockState> decodedStates = new LinkedHashMap<>(64, .75F, true);
    private final Map<BlockState, String> encodedStates = new LinkedHashMap<>(64, .75F, true);
    private final LongAdder stateDecodes = new LongAdder();
    private final java.util.concurrent.atomic.AtomicBoolean loggedError = new java.util.concurrent.atomic.AtomicBoolean();

    PredictionDiskCache(Path root, long fingerprint) {
        this.root = root.toAbsolutePath().normalize();
        this.fingerprint = fingerprint;
        shared = ROOTS.computeIfAbsent(this.root, ignored -> new Shared());
        synchronized (shared) {
            shared.active.values().forEach(entry -> entry.valid = false);
            shared.active.clear();
            shared.owner = this;
        }
    }

    Lease lease(Key key) {
        synchronized (shared) {
            Entry entry = shared.active.computeIfAbsent(key, ignored -> new Entry());
            entry.users++;
            return new Lease(key, entry, !shared.invalidations.containsKey(key));
        }
    }

    record TerrainData(ClientColumnSample[] samples, long colorFingerprint,
                       int[] surfaceTints, int[] foliageTints, int[] waterTints) {
        int cellAxis() { return (int) Math.sqrt(samples.length) - VssLodLayout.SAMPLE_MARGIN * 2; }
        boolean colorsMatch(long fingerprint) {
            return fingerprint != Long.MIN_VALUE && fingerprint == colorFingerprint && surfaceTints != null;
        }
    }

    ClientColumnSample[] readTerrain(Lease lease, int count) {
        TerrainData data = readTerrainData(lease, count);
        return data == null ? null : data.samples();
    }

    TerrainData readTerrainData(Lease lease, int count) {
        return read(lease, (input, version) -> {
            int size = bounded(input.readInt(), MAX_COLUMNS);
            if (count > 0 && size != count) throw new IOException("grid size changed");
            int paletteSize = bounded(input.readInt(), MAX_COLUMNS * 3);
            int[] palette = new int[paletteSize];
            for (int i = 0; i < paletteSize; i++) {
                String name = input.readUTF();
                ResourceLocation id = name.isEmpty() ? null : ResourceLocation.tryParse(name);
                if (!name.isEmpty() && (id == null || !BuiltInRegistries.BLOCK.containsKey(id))) throw new IOException("block unavailable: " + name);
                palette[i] = name.isEmpty() ? ClientColumnSample.NO_BLOCK : BuiltInRegistries.BLOCK.getId(BuiltInRegistries.BLOCK.get(id));
            }
            ClientColumnSample[] samples = new ClientColumnSample[size];
            for (int i = 0; i < size; i++) {
                int y = input.readInt(), fluidY = input.readInt(), biome = input.readInt();
                int top = palette[index(input.readInt(), palette.length)];
                int structure = input.readInt(), tree = input.readInt(), density = input.readInt(), height = input.readInt();
                int fluid = input.readInt(), flags = input.readInt(), ground = input.readInt();
                int under = palette[index(input.readInt(), palette.length)], deep = palette[index(input.readInt(), palette.length)];
                samples[i] = new ClientColumnSample(y, fluidY, biome, top, structure, tree, density, height, fluid, flags,
                        ground, under, deep, input.readInt(), input.readInt(), input.readInt(), input.readInt());
            }
            long colors = Long.MIN_VALUE;
            int[] surface = null, foliage = null, water = null;
            if (version >= 2) {
                colors = input.readLong();
                if (input.readBoolean()) {
                    surface=new int[size];foliage=new int[size];water=new int[size];
                    for(int i=0;i<size;i++) {surface[i]=input.readInt();foliage[i]=input.readInt();water[i]=input.readInt();}
                }
            }
            return new TerrainData(samples, colors, surface, foliage, water);
        });
    }

    boolean writeTerrain(Lease lease, ClientColumnSample[] samples) {
        return writeTerrain(lease, new TerrainData(samples, Long.MIN_VALUE, null, null, null));
    }

    boolean writeTerrain(Lease lease, TerrainData terrain) {
        ClientColumnSample[] samples = terrain.samples();
        if (samples.length > MAX_COLUMNS) return false;
        return write(lease, output -> {
            Map<Integer, Integer> palette = new LinkedHashMap<>();
            for (var sample : samples) for (int block : new int[]{sample.topBlockIndex(), sample.underBlockIndex(), sample.deepBlockIndex()}) {
                palette.computeIfAbsent(block, ignored -> palette.size());
            }
            output.writeInt(samples.length);
            output.writeInt(palette.size());
            for (int block : palette.keySet()) output.writeUTF(block == ClientColumnSample.NO_BLOCK ? ""
                    : BuiltInRegistries.BLOCK.getKey(BuiltInRegistries.BLOCK.byId(block)).toString());
            for (var sample : samples) {
                output.writeInt(sample.surfaceY()); output.writeInt(sample.fluidY()); output.writeInt(sample.biomeIndex());
                output.writeInt(palette.get(sample.topBlockIndex())); output.writeInt(sample.structureIndex());
                output.writeInt(sample.treeKind()); output.writeInt(sample.treeDensity()); output.writeInt(sample.treeHeight());
                output.writeInt(sample.fluid()); output.writeInt(sample.flags()); output.writeInt(sample.groundFeatureKind());
                output.writeInt(palette.get(sample.underBlockIndex())); output.writeInt(palette.get(sample.deepBlockIndex()));
                output.writeInt(sample.surfaceBottom()); output.writeInt(sample.lowerTop()); output.writeInt(sample.lowerBottom()); output.writeInt(sample.spanFloor());
            }
            output.writeLong(terrain.colorFingerprint());
            boolean colors=terrain.colorsMatch(terrain.colorFingerprint());
            output.writeBoolean(colors);
            if(colors)for(int i=0;i<samples.length;i++) {
                output.writeInt(terrain.surfaceTints()[i]);output.writeInt(terrain.foliageTints()[i]);output.writeInt(terrain.waterTints()[i]);
            }
        });
    }

    Map<BlockPos, BlockState> readSurface(Lease lease) {
        return read(lease, (input, version) -> {
            int count = bounded(input.readInt(), MAX_BLOCKS);
            int paletteSize = bounded(input.readInt(), MAX_BLOCKS);
            BlockState[] palette = new BlockState[paletteSize];
            for (int i = 0; i < paletteSize; i++) palette[i] = decodeState(input.readUTF());
            Map<BlockPos, BlockState> blocks = new HashMap<>();
            for (int i = 0; i < count; i++) {
                BlockPos pos = new BlockPos(input.readInt(), input.readInt(), input.readInt());
                blocks.put(pos, palette[index(input.readInt(), palette.length)]);
            }
            return Map.copyOf(blocks);
        });
    }

    boolean writeSurface(Lease lease, Map<BlockPos, BlockState> blocks) {
        if (blocks.size() > MAX_BLOCKS) return false;
        return write(lease, output -> {
            Map<BlockState, Integer> palette = new LinkedHashMap<>();
            blocks.values().forEach(state -> palette.computeIfAbsent(state, ignored -> palette.size()));
            output.writeInt(blocks.size()); output.writeInt(palette.size());
            for (var state : palette.keySet()) output.writeUTF(encodeState(state));
            for (var entry : blocks.entrySet()) {
                BlockPos p = entry.getKey();
                output.writeInt(p.getX()); output.writeInt(p.getY()); output.writeInt(p.getZ()); output.writeInt(palette.get(entry.getValue()));
            }
        });
    }

    private BlockState decodeState(String json) {
        synchronized (decodedStates) {
            BlockState state = decodedStates.get(json);
            if (state != null) return state;
            state = BlockState.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
            stateDecodes.increment();
            if (decodedStates.size() >= 4096) decodedStates.remove(decodedStates.keySet().iterator().next());
            if (!closed) decodedStates.put(json, state);
            return state;
        }
    }

    private String encodeState(BlockState state) {
        synchronized (encodedStates) {
            String json = encodedStates.get(state);
            if (json != null) return json;
            json = BlockState.CODEC.encodeStart(JsonOps.INSTANCE, state).getOrThrow().toString();
            if (encodedStates.size() >= 4096) encodedStates.remove(encodedStates.keySet().iterator().next());
            if (!closed) encodedStates.put(state, json);
            return json;
        }
    }

    long stateDecodes() { return stateDecodes.sum(); }

    private <T> T read(Lease lease, Decoder<T> decoder) {
        if (!lease.readable || !lease.valid()) { misses.increment(); return null; }
        Path file = file(lease.key);
        try (var input = new DataInputStream(new InflaterInputStream(new BufferedInputStream(Files.newInputStream(file))))) {
            if (input.readInt() != MAGIC) throw new IOException("cache magic mismatch");
            int version=input.readInt();
            if ((version != schema(lease.key) && !(lease.key.kind == 0 && version == 1)) || input.readLong() != fingerprint
                    || input.readInt() != lease.key.kind || input.readInt() != lease.key.x || input.readInt() != lease.key.z
                    || input.readInt() != lease.key.detail) throw new IOException("cache identity mismatch");
            T result = decoder.read(input, version);
            if (input.read() != -1) throw new IOException("trailing cache data"); // also validates zlib checksum
            if (!lease.valid()) { misses.increment(); return null; }
            hits.increment();
            return result;
        } catch (NoSuchFileException absent) {
            misses.increment();
        } catch (IOException | RuntimeException failure) {
            error(failure);
            misses.increment();
        }
        return null;
    }

    private boolean write(Lease lease, Encoder encoder) {
        if (!lease.valid() || Thread.currentThread().isInterrupted()) return false;
        Path temporary = null;
        try {
            Path target = file(lease.key);
            Files.createDirectories(target.getParent());
            temporary = Files.createTempFile(target.getParent(), "pending-", ".tmp");
            try (var output = new DataOutputStream(new DeflaterOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary))))) {
                output.writeInt(MAGIC); output.writeInt(schema(lease.key)); output.writeLong(fingerprint);
                output.writeInt(lease.key.kind); output.writeInt(lease.key.x); output.writeInt(lease.key.z); output.writeInt(lease.key.detail);
                encoder.write(output);
            }
            Path completed = temporary;
            // Only a path is queued. The potentially large sample/block map is
            // never retained in an asynchronous write queue.
            Future<Boolean> commit;
            synchronized (shared) {
                commit = COMMITS.submit(() -> {
                    try {
                        synchronized (shared) {
                            if (!lease.valid() || shared.invalidations.containsKey(lease.key)) return false;
                        }
                        try { Files.move(completed, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
                        catch (AtomicMoveNotSupportedException unsupported) { Files.move(completed, target, StandardCopyOption.REPLACE_EXISTING); }
                        writes.increment();
                        return true;
                    } finally { Files.deleteIfExists(completed); }
                });
            }
            temporary = null; // commit owns cleanup even when this worker is interrupted
            return commit.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException | ExecutionException | RuntimeException failure) { error(failure); }
        finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
        return false;
    }

    /** Marks leases immediately; deletions coalesce on the IO thread. */
    void invalidate(Collection<Key> keys) {
        synchronized (shared) {
            if (closed || shared.owner != this) return;
            for (Key key : keys) {
                Entry entry = shared.active.remove(key);
                if (entry != null) entry.valid = false;
                shared.invalidations.put(key, ++shared.revision);
            }
            if (!shared.deleting && !shared.invalidations.isEmpty()) {
                shared.deleting = true;
                COMMITS.execute(this::drainInvalidations);
            }
        }
    }

    private void drainInvalidations() {
        Map<Key, Long> pending;
        synchronized (shared) { pending = new HashMap<>(shared.invalidations); }
        for (var entry : pending.entrySet()) {
            try {
                Files.deleteIfExists(file(entry.getKey()));
                synchronized (shared) { shared.invalidations.remove(entry.getKey(), entry.getValue()); }
            } catch (IOException failure) {
                // Leave a tombstone in memory: a failed deletion must not let
                // stale data re-enter the current world. No hot retry loop.
                error(failure);
            }
        }
        synchronized (shared) {
            shared.deleting = false;
            boolean newWork = shared.invalidations.entrySet().stream().anyMatch(entry -> !Objects.equals(pending.get(entry.getKey()), entry.getValue()));
            if (newWork) { shared.deleting = true; COMMITS.execute(this::drainInvalidations); }
        }
    }

    static Set<Key> affected(int chunkX, int chunkZ) {
        Set<Key> keys = new HashSet<>();
        for (int lod = 0; lod <= PredictionTileManager.MAX_LOD_LEVEL; lod++) {
            long spacing = 1L << lod, span = 64 * spacing, margin = Math.max(16, span / 8);
            for (long z = Math.floorDiv(chunkZ * 16L - margin, span); z <= Math.floorDiv(chunkZ * 16L + 15 + margin, span); z++) {
                for (long x = Math.floorDiv(chunkX * 16L - margin, span); x <= Math.floorDiv(chunkX * 16L + 15 + margin, span); x++) {
                    keys.add(Key.terrain((int) x, (int) z, lod));
                }
            }
        }
        // Decoration can read 32 blocks beyond its source chunk and place
        // into neighbors. Invalidate every source whose bounded reads overlap.
        for (int z = chunkZ - 2; z <= chunkZ + 2; z++) for (int x = chunkX - 2; x <= chunkX + 2; x++) {
            for (int settings = 0; settings < 4; settings++) keys.add(Key.surface(x, z, settings));
        }
        return keys;
    }

    void invalidateChunk(int x, int z) { invalidate(affected(x, z)); }
    Path file(Key key) {
        return root.resolve(key.kind + "-" + key.detail).resolve((key.x >> 5) + "_" + (key.z >> 5))
                .resolve(key.x + "_" + key.z + ".vpd");
    }
    String diagnostics() { return "disk={hits=" + hits.sum() + ",misses=" + misses.sum() + ",writes=" + writes.sum()
            + ",stateDecodes=" + stateDecodes.sum() + ",errors=" + errors.sum() + "}"; }
    Path root() { return root; }
    long hits() { return hits.sum(); }
    void flush() {
        try { COMMITS.submit(() -> { }).get(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (ExecutionException failure) { error(failure); }
    }
    @Override public void close() {
        closed = true;
        synchronized (decodedStates) { decodedStates.clear(); }
        synchronized (encodedStates) { encodedStates.clear(); }
        synchronized (shared) {
            if (shared.owner == this) {
                shared.active.values().forEach(entry -> entry.valid = false);
                shared.active.clear();
                shared.owner = null;
            }
        }
    }
    private void error(Throwable failure) {
        errors.increment();
        if (VSSClientConfig.CONFIG.debugLogging && loggedError.compareAndSet(false, true)) VSSLogger.debug("VSS prediction disk cache miss/write failure: " + failure);
    }
    private static int bounded(int value, int max) throws IOException { if (value < 0 || value > max) throw new IOException("cache count out of range"); return value; }
    private static int index(int value, int size) throws IOException { if (value < 0 || value >= size) throw new IOException("cache palette index out of range"); return value; }
    @FunctionalInterface private interface Decoder<T> { T read(DataInputStream input, int version) throws IOException; }
    @FunctionalInterface private interface Encoder { void write(DataOutputStream output) throws IOException; }
}
