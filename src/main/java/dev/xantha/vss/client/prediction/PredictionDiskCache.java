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
    private static final int MAGIC = 0x56535044, SCHEMA = 5;
    private static final int MAX_COLUMNS = 66 * 66, MAX_BLOCKS = 262_144;
    private static final ThreadPoolExecutor COMMITS = new ThreadPoolExecutor(1, 1, 0L,
            TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), task -> {
        Thread thread = new Thread(task, "vss-prediction-disk");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    private static final ConcurrentMap<Path, Shared> ROOTS = new ConcurrentHashMap<>();
    private static final class Shared {
        volatile PredictionDiskCache owner;
        PredictionRegionStorage regions;
        final Set<Key> migrations = new HashSet<>();
        boolean maintenanceQueued;
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
    private static int schema(Key key) { return SCHEMA; }
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
    // P2-12: one counter could not tell "no file yet" (normal on first visit)
    // from "file present but rejected" (a fingerprint or format bug). These
    // split the same misses so a low hit rate can be explained rather than
    // guessed at.
    private final LongAdder missAbsent = new LongAdder(), missStale = new LongAdder(),
            missCorrupt = new LongAdder(), missIdentity = new LongAdder(), missRaced = new LongAdder();
    private final Map<String, BlockState> decodedStates = new LinkedHashMap<>(64, .75F, true);
    private final Map<BlockState, String> encodedStates = new LinkedHashMap<>(64, .75F, true);
    private final LongAdder stateDecodes = new LongAdder();
    private final java.util.concurrent.atomic.AtomicBoolean loggedError = new java.util.concurrent.atomic.AtomicBoolean();
    private final Map<Key, Integer> terrainHints = new LinkedHashMap<>(256, .75F, true);
    private final ArrayDeque<Key> probeQueue = new ArrayDeque<>();
    private boolean probing;

    /** Header-only batch lookup; hints never replace the validated read or its lease. */
    void probeTerrain(Collection<Key> candidates) {
        synchronized (shared) {
            if (closed || shared.owner != this) return;
            probeQueue.clear();
            int count = 0;
            for (Key key : candidates) {
                if (key.kind == 0 && !terrainHints.containsKey(key)) probeQueue.add(key);
                if (++count >= 32768) break;
            }
            probeNextBatch();
        }
    }

    private void probeNextBatch() {
        List<Lease> batch = new ArrayList<>();
        synchronized (shared) {
            if (closed || shared.owner != this || probing) return;
            while (!probeQueue.isEmpty()) {
                Key key = probeQueue.removeFirst();
                if (key.kind != 0 || terrainHints.containsKey(key) || shared.invalidations.containsKey(key)) continue;
                batch.add(lease(key));
                if (batch.size() == 128) break;
            }
            if (batch.isEmpty()) return;
            probing = true;
        }
        COMMITS.execute(() -> {
            try {
                for (Lease lease : batch) try (lease) {
                    int axis = 0;
                    if (lease.valid() && lease.readable) {
                        try (var input = new DataInputStream(new ByteArrayInputStream(shared.regions.header(lease.key)))) {
                            int magic = input.readInt(), version = input.readInt();
                            if (magic == MAGIC && version >= 1 && version <= SCHEMA && input.readLong() == fingerprint
                                    && input.readInt() == 0 && input.readInt() == lease.key.x
                                    && input.readInt() == lease.key.z && input.readInt() == lease.key.detail) {
                                int count = input.readInt(), candidate = (int) Math.sqrt(count) - 2;
                                if (candidate > 0 && candidate <= 64 && (candidate & (candidate - 1)) == 0
                                        && (candidate + 2) * (candidate + 2) == count) axis = candidate;
                            }
                        } catch (IOException | RuntimeException ignored) { }
                    }
                    synchronized (shared) {
                        if (lease.valid() && lease.readable) rememberTerrain(lease.key, axis);
                    }
                }
            } finally {
                synchronized (shared) {
                    probing = false;
                    // Yield between batches to writes/invalidations, without waiting for a planner tick.
                    probeNextBatch();
                }
            }
        });
    }

    int cachedTerrainAxis(Key key) {
        synchronized (shared) {
            return closed || shared.owner != this || shared.invalidations.containsKey(key)
                    ? 0 : terrainHints.getOrDefault(key, 0);
        }
    }

    void forgetTerrain(Key key) { synchronized (shared) { rememberTerrain(key, 0); } }

    private void rememberTerrain(Key key, int axis) {
        if (closed || shared.owner != this) return;
        terrainHints.put(key, axis);
        while (terrainHints.size() > 32768) terrainHints.remove(terrainHints.keySet().iterator().next());
    }

    PredictionDiskCache(Path root, long fingerprint) {
        this.root = root.toAbsolutePath().normalize();
        this.fingerprint = fingerprint;
        shared = ROOTS.computeIfAbsent(this.root, ignored -> new Shared());
        synchronized (shared) {
            if (shared.regions == null) shared.regions = new PredictionRegionStorage(this.root);
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
                int bottom = input.readInt(), lowerTop = input.readInt(), lowerBottom = input.readInt(), floor = input.readInt();
                PredictionColumnVolume volume = null;
                if (version >= 3 && input.readBoolean()) {
                    int runs = bounded(input.readInt(), PredictionColumnVolume.MAX_RUNS);
                    int[] intervals = new int[runs * 4];
                    for (int r = 0; r < intervals.length; r += 4) {
                        intervals[r] = input.readInt(); intervals[r + 1] = input.readInt();
                        intervals[r + 2] = palette[index(input.readInt(), palette.length)];
                        intervals[r + 3] = input.readUnsignedByte();
                    }
                    volume = new PredictionColumnVolume(intervals);
                }
                samples[i] = new ClientColumnSample(y, fluidY, biome, top, structure, tree, density, height, fluid, flags,
                        ground, under, deep, bottom, lowerTop, lowerBottom, floor, volume);
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
        boolean volumes = java.util.Arrays.stream(samples).anyMatch(sample -> sample.volume() != null);
        return write(lease, volumes ? 3 : 2, output -> {
            Map<Integer, Integer> palette = new LinkedHashMap<>();
            for (var sample : samples) for (int block : new int[]{sample.topBlockIndex(), sample.underBlockIndex(), sample.deepBlockIndex()}) {
                palette.computeIfAbsent(block, ignored -> palette.size());
            }
            for (var sample : samples) if (sample.volume() != null) {
                for (int i = 0; i < sample.volume().size(); i++)
                    palette.computeIfAbsent(sample.volume().block(i), ignored -> palette.size());
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
                var volume = sample.volume();
                if (volumes) output.writeBoolean(volume != null);
                if (volume != null) {
                    output.writeInt(volume.size());
                    for (int i = 0; i < volume.size(); i++) {
                        output.writeInt(volume.bottom(i)); output.writeInt(volume.top(i));
                        output.writeInt(palette.get(volume.block(i))); output.writeByte(volume.fluid(i));
                    }
                }
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
        SurfaceData data = readSurfaceData(lease);
        return data == null ? null : data.blocks();
    }

    record SurfaceData(Map<BlockPos, BlockState> blocks, boolean canonical, boolean weatherChecked) { }

    SurfaceData readSurfaceData(Lease lease) {
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
            return new SurfaceData(Map.copyOf(blocks), version >= 4, version >= 5);
        });
    }

    boolean writeSurface(Lease lease, Map<BlockPos, BlockState> blocks) {
        return writeSurface(lease, blocks, false);
    }

    boolean writeSurface(Lease lease, Map<BlockPos, BlockState> blocks, boolean canonical) {
        if (blocks.size() > MAX_BLOCKS) return false;
        return write(lease, canonical ? 5 : 3, output -> {
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
        if (!lease.readable || !lease.valid()) { missStale.increment(); misses.increment(); return null; }
        // Buffer decompressed bytes too: DataInputStream.readInt otherwise
        // enters the inflater once per byte for every column field.
        try {
            var record = shared.regions.read(lease.key);
            try (var input = new DataInputStream(new BufferedInputStream(
                    new InflaterInputStream(new ByteArrayInputStream(record.bytes())), 32 * 1024))) {
            if (input.readInt() != MAGIC) { missCorrupt.increment(); throw new IOException("cache magic mismatch"); }
            int version=input.readInt();
            if ((version != schema(lease.key) && version != 4 && version != 3 && !(lease.key.kind == 0 && (version == 1 || version == 2))) || input.readLong() != fingerprint
                    || input.readInt() != lease.key.kind || input.readInt() != lease.key.x || input.readInt() != lease.key.z
                    || input.readInt() != lease.key.detail) { missIdentity.increment(); throw new IOException("cache identity mismatch"); }
            T result = decoder.read(input, version);
            if (input.read() != -1) { missCorrupt.increment(); throw new IOException("trailing cache data"); } // also validates zlib checksum
            if (!lease.valid()) { missRaced.increment(); misses.increment(); return null; }
            hits.increment();
            if (record.legacy()) migrateLater(lease);
            return result;
            }
        } catch (NoSuchFileException absent) {
            missAbsent.increment();
            misses.increment();
        } catch (IOException | RuntimeException failure) {
            error(failure);
            misses.increment();
        }
        return null;
    }

    private boolean write(Lease lease, Encoder encoder) {
        return write(lease, schema(lease.key), encoder);
    }

    private boolean write(Lease lease, int version, Encoder encoder) {
        if (!lease.valid() || Thread.currentThread().isInterrupted()) return false;
        Path temporary = null;
        try {
            Path target = file(lease.key);
            Files.createDirectories(target.getParent());
            temporary = Files.createTempFile(target.getParent(), "pending-", ".tmp");
            // Batch primitive writes before crossing into zlib, without changing
            // the on-disk schema or retaining a whole decoded tile in the IO queue.
            try (var output = new DataOutputStream(new BufferedOutputStream(
                    new DeflaterOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary))), 32 * 1024))) {
                output.writeInt(MAGIC); output.writeInt(version); output.writeLong(fingerprint);
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
                        shared.regions.write(lease.key, completed);
                        writes.increment();
                        synchronized (shared) { terrainHints.remove(lease.key); }
                        scheduleMaintenance();
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
                terrainHints.remove(key);
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
                shared.regions.delete(entry.getKey());
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
        scheduleMaintenance();
    }

    private void migrateLater(Lease lease) {
        synchronized (shared) {
            if (!lease.valid() || shared.migrations.size() >= 32 || !shared.migrations.add(lease.key)) return;
            Entry token = lease.entry;
            long revision = shared.revision;
            Key key = lease.key;
            COMMITS.execute(() -> {
                try {
                    synchronized (shared) {
                        if (closed || shared.owner != this || !token.valid || shared.revision != revision
                                || shared.invalidations.containsKey(key)) return;
                    }
                    shared.regions.migrate(key);
                } catch (IOException failure) { error(failure); }
                finally { synchronized (shared) { shared.migrations.remove(key); } }
            });
        }
    }

    private void scheduleMaintenance() {
        if (!shared.regions.hasMaintenance()) return;
        synchronized (shared) {
            if (shared.maintenanceQueued || closed || shared.owner != this) return;
            shared.maintenanceQueued = true;
            COMMITS.execute(() -> {
                try {
                    if (!closed && shared.owner == this && COMMITS.getQueue().isEmpty()) shared.regions.compactOne();
                } catch (IOException failure) { error(failure); }
                finally { synchronized (shared) { shared.maintenanceQueued = false; } }
            });
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
            // Bit 2 separates reusable visual trees from exact feature replay.
            for (int settings = 0; settings < 32; settings++) {
                keys.add(Key.surface(x, z, settings));
                keys.add(Key.surface(x, z, settings | 96));
            }
        }
        return keys;
    }

    void invalidateChunk(int x, int z) { invalidate(affected(x, z)); }

    void invalidateCapture(int x, int z) {
        var keys = affected(x, z);
        // Authoritative arrivals only replace sampled columns. Match the live
        // manager's sparse-grid dependency check for unloaded tiles too; otherwise
        // a chunk between far grid points deletes useful persisted ancestors.
        // Explicit world edits still use the conservative invalidateChunk path.
        keys.removeIf(key -> key.kind != 0 || !captureIntersects(key, x, z));
        invalidate(keys);
    }

    private static boolean captureIntersects(Key key, int x, int z) {
        int spacing = 1 << key.detail, span = VssLodLayout.BASE_TILE_BLOCKS << key.detail;
        return PredictionTileManager.captureIntersectsAxis(x * 16L, key.x * (long) span, span, spacing)
                && PredictionTileManager.captureIntersectsAxis(z * 16L, key.z * (long) span, span, spacing);
    }
    Path file(Key key) {
        return shared.regions.legacy(key);
    }
    String diagnostics() { return "disk={hits=" + hits.sum() + ",misses=" + misses.sum() + ",writes=" + writes.sum()
            + ",stateDecodes=" + stateDecodes.sum() + ",errors=" + errors.sum() + "}"
            // Why the misses happened. `absent` is the healthy case (nothing
            // written yet); `identity`/`corrupt` mean a stored entry was
            // rejected, which is the state that silently destroys a warm cache.
            + ",missReason={absent=" + missAbsent.sum() + ",stale=" + missStale.sum()
            + ",identity=" + missIdentity.sum() + ",corrupt=" + missCorrupt.sum()
            + ",raced=" + missRaced.sum() + "}"; }
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
            terrainHints.clear();
            probeQueue.clear();
            if (shared.owner == this) {
                shared.active.values().forEach(entry -> entry.valid = false);
                shared.active.clear();
                shared.owner = null;
                COMMITS.execute(() -> {
                    // Storage methods synchronize with builders and a newly opened session.
                    synchronized (shared) { if (shared.owner != null) return; }
                    try { shared.regions.close(); } catch (IOException failure) { error(failure); }
                });
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
