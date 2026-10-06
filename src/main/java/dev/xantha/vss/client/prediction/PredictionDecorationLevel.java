package dev.xantha.vss.client.prediction;

import dev.xantha.vss.client.prediction.feature.FeatureStampLevel;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/** Bounded decoration region at real world coordinates, with no live chunk access. */
final class PredictionDecorationLevel extends FeatureStampLevel {
    private static final int MAX_WRITES = 65_536;
    /**
     * Decoration jobs are bounded to an 80 by 80 column window. Keep the
     * native records in that window directly addressable. A packed {@code
     * Long} key has very poor hash distribution for the nearby x/z coordinates
     * used by feature predicates, which turns HashMap buckets into trees.
     */
    private static final int COLUMN_CACHE_AXIS = 80;
    private static final int COLUMN_CACHE_SIZE = COLUMN_CACHE_AXIS * COLUMN_CACHE_AXIS;
    private static final int BLOCK_QUERY_CACHE_SIZE = 512;
    private static final int HEIGHT_QUERY_CACHE_SIZE = 256;
    private static final int BIOME_QUERY_CACHE_SIZE = 1 << 15;
    private final ClientTerrainSampler terrain;
    private final ClientTerrainSampler context;
    private final int originX;
    private final int originZ;
    private final BiomeManager biomes;
    // Feature placement revisits nearby quart coordinates heavily. Keep the
    // hot answers in primitive coordinate arrays; a collision only causes a
    // fresh context lookup and can never return an answer for another point.
    private int[] biomeXs, biomeYs, biomeZs;
    private Holder<Biome>[] biomeValues;
    private int biomeEntries;
    private final VssLodSampleCache sharedColumns;
    private ClientColumnSample[] columns;
    private ClientColumnSample[] displaySamples;
    // State IDs are richer than ClientColumnSample's block IDs. Retain the
    // original immutable record for this bounded job instead of looking it up
    // in the shared sampler for every ground/heightmap/tree-space query.
    private int[][] nativeColumns;
    private int[][] displayColumns;
    // Feature predicates often ask for the same block more than once (for
    // example DiskFeature checks a column through several nested predicates).
    // Cache complete state answers, including air and fluids. Cancellation is
    // checked before cache hits. Writes invalidate their own position and
    // height column; switching the terrain view invalidates every answer.
    private final int[] blockQueryXs = new int[BLOCK_QUERY_CACHE_SIZE];
    private final int[] blockQueryYs = new int[BLOCK_QUERY_CACHE_SIZE];
    private final int[] blockQueryZs = new int[BLOCK_QUERY_CACHE_SIZE];
    private final long[] blockQueryEpochs = new long[BLOCK_QUERY_CACHE_SIZE];
    private final BlockState[] blockQueryStates = new BlockState[BLOCK_QUERY_CACHE_SIZE];
    private long blockQueryEpoch = 1L;
    private final int[] heightQueryXs = new int[HEIGHT_QUERY_CACHE_SIZE];
    private final int[] heightQueryZs = new int[HEIGHT_QUERY_CACHE_SIZE];
    private final int[] heightQueryTypes = new int[HEIGHT_QUERY_CACHE_SIZE];
    private final int[] heightQueryValues = new int[HEIGHT_QUERY_CACHE_SIZE];
    private final long[] heightQueryEpochs = new long[HEIGHT_QUERY_CACHE_SIZE];
    private final int[] heightQueryColumnVersions = new int[HEIGHT_QUERY_CACHE_SIZE];
    private int[] columnQueryVersions;
    private final BlockPos.MutableBlockPos heightQueryPos = new BlockPos.MutableBlockPos();
    private final boolean[] exactEdits = new boolean[COLUMN_CACHE_SIZE];
    private final boolean[] displayEdits = new boolean[COLUMN_CACHE_SIZE];
    private boolean displayTerrain;
    private int[] changedTops;
    private final int[] biomeBlockXs = new int[BLOCK_QUERY_CACHE_SIZE];
    private final int[] biomeBlockYs = new int[BLOCK_QUERY_CACHE_SIZE];
    private final int[] biomeBlockZs = new int[BLOCK_QUERY_CACHE_SIZE];
    @SuppressWarnings("unchecked")
    private final Holder<Biome>[] biomeBlockValues = (Holder<Biome>[]) new Holder<?>[BLOCK_QUERY_CACHE_SIZE];
    private final Map<BlockPos, BlockState> undo = new HashMap<>();
    private it.unimi.dsi.fastutil.ints.Int2ByteOpenHashMap undoColumnModes;
    private final java.util.Set<BlockPos> structureBlocks = new java.util.HashSet<>();
    private boolean transaction;
    private boolean structureTransaction;
    private int writes;
    private final net.minecraft.world.level.chunk.ChunkAccess[] virtualChunks =
            new net.minecraft.world.level.chunk.ChunkAccess[5 * 5];

    /** Chunk-facing block queries stay in the same bounded transaction as level writes.
     * Postprocessing/ticks are visual-generation metadata; no live world is touched. */
    @Override public net.minecraft.world.level.chunk.ChunkAccess getChunk(int x, int z,
            net.minecraft.world.level.chunk.status.ChunkStatus status, boolean create) {
        checkColumnBounds(x * 16, z * 16);
        int localX = x - (Math.floorDiv(originX, 16) - 2);
        int localZ = z - (Math.floorDiv(originZ, 16) - 2);
        int slot = localZ * 5 + localX;
        net.minecraft.world.level.chunk.ChunkAccess cached = virtualChunks[slot];
        if (cached != null) return cached;
        cached = new net.minecraft.world.level.chunk.ProtoChunk(
                new net.minecraft.world.level.ChunkPos(x, z), net.minecraft.world.level.chunk.UpgradeData.EMPTY,
                this, registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME), null) {
            @Override public BlockState getBlockState(BlockPos pos) { return PredictionDecorationLevel.this.getBlockState(pos); }
            @Override public net.minecraft.world.level.material.FluidState getFluidState(BlockPos pos) {
                return getBlockState(pos).getFluidState();
            }
            @Override public BlockState setBlockState(BlockPos pos, BlockState state, boolean moving) {
                var previous = getBlockState(pos);
                PredictionDecorationLevel.this.setBlock(pos, state, 19, 0);
                return previous;
            }
            @Override public int getHeight(Heightmap.Types type, int localX, int localZ) {
                return PredictionDecorationLevel.this.getHeight(type, x * 16 + (localX & 15), z * 16 + (localZ & 15)) - 1;
            }
            @Override public Holder<Biome> getNoiseBiome(int qx, int qy, int qz) {
                return PredictionDecorationLevel.this.getNoiseBiome(qx, qy, qz);
            }
            @Override public void markPosForPostprocessing(BlockPos pos) { }
        };
        virtualChunks[slot] = cached;
        return cached;
    }

    PredictionDecorationLevel(ClientTerrainSampler terrain, ClientTerrainSampler context,
                              RegistryAccess access, int chunkX, int chunkZ) {
        this(terrain, context, access, chunkX, chunkZ, null);
    }

    PredictionDecorationLevel(ClientTerrainSampler terrain, ClientTerrainSampler context,
                              RegistryAccess access, int chunkX, int chunkZ, VssLodSampleCache sharedColumns) {
        super(terrain.profile().seed(), access, Blocks.GRASS_BLOCK.defaultBlockState());
        this.terrain = terrain;
        this.context = context;
        this.originX = chunkX * 16;
        this.originZ = chunkZ * 16;
        this.biomes = new BiomeManager(this, BiomeManager.obfuscateSeed(getSeed()));
        this.sharedColumns = sharedColumns;
    }

    ClientColumnSample column(int x, int z) {
        checkQueryActive();
        return columnActive(x, z);
    }

    private ClientColumnSample columnActive(int x, int z) {
        checkColumnBounds(x, z);
        if (interiorTerrain()) {
            int slot = columnSlot(x, z);
            if (columns == null) columns = new ClientColumnSample[COLUMN_CACHE_SIZE];
            ClientColumnSample sample = columns[slot];
            if (sample == null) {
                long packed = key(x, z);
                sample = sharedColumns == null ? terrain.sampleInterior(x, z)
                        : sharedColumns.getOrCompute(packed, ignored -> terrain.sampleInterior(x, z));
                columns[slot] = sample;
            }
            return sample;
        }
        if (displayTerrain && terrain instanceof RustTerrainSampler rust) {
            int slot = columnSlot(x, z);
            if (displaySamples == null) displaySamples = new ClientColumnSample[COLUMN_CACHE_SIZE];
            ClientColumnSample sample = displaySamples[slot];
            if (sample == null) displaySamples[slot] = sample = rust.surfaceSample(nativeColumn(rust, x, z));
            return sample;
        }
        int slot = columnSlot(x, z);
        if (columns == null) columns = new ClientColumnSample[COLUMN_CACHE_SIZE];
        ClientColumnSample sample = columns[slot];
        if (sample == null) {
            if (terrain instanceof RustTerrainSampler rust) {
                sample = rust.surfaceSample(nativeColumn(rust, x, z));
            } else {
                long packed = key(x, z);
                sample = sharedColumns == null ? terrain.sampleSurface(x, z)
                        : sharedColumns.getOrCompute(packed, ignored -> terrain.sampleSurface(x, z));
            }
            columns[slot] = sample;
        }
        return sample;
    }

    private int[] nativeColumn(RustTerrainSampler rust, int x, int z) {
        checkColumnBounds(x, z);
        // Callers check cancellation before any retained cache hit.
        int slot = columnSlot(x, z);
        if (displayTerrain) {
            if (displayColumns == null) displayColumns = new int[COLUMN_CACHE_SIZE][];
            int[] record = displayColumns[slot];
            if (record == null) {
                int ox = Math.floorDiv(x,4)*4, oz = Math.floorDiv(z,4)*4;
                int[][] rows = rust.decorationDisplayPage(ox,oz);
                for (int i=0;i<16;i++) displayColumns[columnSlot(ox+i/4,oz+i%4)] = rows[i];
                record = displayColumns[slot];
            }
            return record;
        }
        if (nativeColumns == null) nativeColumns = new int[COLUMN_CACHE_SIZE][];
        int[] record = nativeColumns[slot];
        if (record == null) {
            record = rust.surfaceRecord(x, z);
            nativeColumns[slot] = record;
        }
        return record;
    }

    private int columnSlot(int x, int z) {
        int localX = x - (originX - 32);
        int localZ = z - (originZ - 32);
        if ((localX | localZ) < 0 || localX >= COLUMN_CACHE_AXIS || localZ >= COLUMN_CACHE_AXIS) {
            throw new IllegalArgumentException("Decoration column outside cache window");
        }
        return localZ * COLUMN_CACHE_AXIS + localX;
    }

    void useDisplayTerrain(boolean display) {
        if (displayTerrain == display) return;
        displayTerrain = display;
        invalidateQueryEpoch();
    }
    void restoreSurface(Map<BlockPos, BlockState> blocks) {
        // A disk upgrade restores existing geometry; it is not a new feature
        // transaction and may contain more writes than one feature's budget.
        blocks.forEach((pos, state) -> {
            checkColumnBounds(pos.getX(), pos.getZ());
            noteWrite(pos, state);
            noteTop(pos);
        });
        clearPendingUploads();
    }
    boolean interiorTerrain() { return terrain.interiorTerrain(); }
    boolean usesDisplayTerrain() { return displayTerrain; }

    @Override
    protected void noteWrite(BlockPos pos, BlockState state) {
        super.noteWrite(pos, state);
        // Block answers have no neighbor dependency. Rollbacks use the same
        // path, so unrelated positions and height columns remain reusable.
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        int slot = blockQuerySlot(x, y, z);
        if (blockQueryXs[slot] == x && blockQueryYs[slot] == y && blockQueryZs[slot] == z)
            blockQueryEpochs[slot] = 0L;
        if (columnQueryVersions != null && ++columnQueryVersions[columnSlot(x, z)] == 0)
            java.util.Arrays.fill(heightQueryEpochs, 0L);
    }

    /** Structures/custom feature cuts retain their original extraction floor.
     * Pure visual plant columns use the same ground as their placement. */
    ClientColumnSample exteriorColumn(int x, int z) {
        boolean previous = displayTerrain;
        int slot = columnSlot(x, z);
        displayTerrain = !exactEdits[slot] && displayEdits[slot];
        try { return column(x,z); } finally { displayTerrain = previous; }
    }

    private void checkColumnBounds(int x, int z) {
        if (x < originX - 32 || x >= originX + 48 || z < originZ - 32 || z >= originZ + 48) {
            throw new UnsupportedOperationException("decoration read outside its bounded region");
        }
    }

    void beginFeature() {
        writes = 0;
        undo.clear();
        if (undoColumnModes != null) undoColumnModes.clear();
        transaction = true;
        structureTransaction = false;
    }

    int featureWriteCount() { return writes; }

    void beginStructure() { beginFeature(); structureTransaction = true; }

    void endFeature(boolean success) {
        if (!success && !undo.isEmpty()) {
            undo.forEach((pos, state) -> {
                // Rollbacks must register writes for the next native transfer.
                noteWrite(pos, state);
            });
            if (undoColumnModes != null) {
                var entries = undoColumnModes.int2ByteEntrySet().fastIterator();
                while (entries.hasNext()) {
                    var entry = entries.next();
                    int slot = entry.getIntKey();
                    exactEdits[slot] = (entry.getByteValue() & 1) != 0;
                    displayEdits[slot] = (entry.getByteValue() & 2) != 0;
                }
            }
            rebuildChangedTops();
        }
        if (success && structureTransaction) structureBlocks.addAll(undo.keySet());
        undo.clear();
        if (undoColumnModes != null) undoColumnModes.clear();
        transaction = false;
    }

    private void rebuildChangedTops() {
        changedTops = null;
        placed().forEach((pos, state) -> {
            if (!state.isAir()) noteTop(pos);
        });
    }

    boolean isStructureBlock(BlockPos pos) { return structureBlocks.contains(pos); }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        checkQueryActive();
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        int slot = blockQuerySlot(x, y, z);
        if (blockQueryEpochs[slot] == blockQueryEpoch
                && blockQueryXs[slot] == x && blockQueryYs[slot] == y && blockQueryZs[slot] == z) {
            return blockQueryStates[slot];
        }
        BlockState result = resolveBlockState(pos);
        cacheBlockQuery(slot, x, y, z, result);
        return result;
    }

    private BlockState resolveBlockState(BlockPos pos) {
        BlockState placed = placed().get(pos);
        if (placed != null) return placed;
        if (pos.getY() < getMinBuildHeight() || pos.getY() >= getMaxBuildHeight()) {
            return Blocks.AIR.defaultBlockState();
        }
        if (interiorTerrain()) {
            int block = columnActive(pos.getX(), pos.getZ()).volume().blockAt(pos.getY());
            return block == ClientColumnSample.NO_BLOCK ? Blocks.AIR.defaultBlockState()
                    : BuiltInRegistries.BLOCK.byId(block).defaultBlockState();
        }
        if (terrain instanceof RustTerrainSampler rust) {
            return rust.proxyBlock(nativeColumn(rust, pos.getX(), pos.getZ()), pos.getY());
        }
        ClientColumnSample sample = columnActive(pos.getX(), pos.getZ());
        if (pos.getY() >= sample.surfaceY()) {
            if (sample.hasFluid() && pos.getY() < sample.fluidY()) {
                if (sample.ice() && pos.getY() == sample.fluidY() - 1) return Blocks.ICE.defaultBlockState();
                return (sample.fluid() == 2 ? Blocks.LAVA : Blocks.WATER).defaultBlockState();
            }
            return Blocks.AIR.defaultBlockState();
        }
        if (!sample.hasSurface()) return Blocks.AIR.defaultBlockState();
        int depth = sample.surfaceY() - 1 - pos.getY();
        int block = depth == 0 ? PredictionMaterialPalette.surfaceBlock(sample)
                : depth < 4 ? sample.underBlockIndex() : sample.deepBlockIndex();
        return block == ClientColumnSample.NO_BLOCK ? Blocks.STONE.defaultBlockState()
                : BuiltInRegistries.BLOCK.byId(block).defaultBlockState();
    }

    @Override
    public boolean isStateAtPosition(BlockPos pos, java.util.function.Predicate<BlockState> predicate) {
        return predicate.test(getBlockState(pos));
    }

    @Override
    public boolean ensureCanWrite(BlockPos pos) {
        return pos.getY() >= getMinBuildHeight() && pos.getY() < getMaxBuildHeight()
                && pos.getX() >= originX - 16 && pos.getX() < originX + 32
                && pos.getZ() >= originZ - 16 && pos.getZ() < originZ + 32;
    }

    @Override
    public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursion) {
        if (Thread.currentThread().isInterrupted()) {
            throw new java.util.concurrent.CancellationException("prediction decoration interrupted");
        }
        if (!ensureCanWrite(pos)) {
            throw new UnsupportedOperationException("decoration write outside its bounded region");
        }
        if (++writes > MAX_WRITES) {
            throw new UnsupportedOperationException("decoration exceeded its work budget");
        }
        noteTop(pos);
        int columnSlot = columnSlot(pos.getX(), pos.getZ());
        if (transaction && (displayTerrain ? !displayEdits[columnSlot] : !exactEdits[columnSlot])) {
            if (undoColumnModes == null) {
                undoColumnModes = new it.unimi.dsi.fastutil.ints.Int2ByteOpenHashMap(8);
                undoColumnModes.defaultReturnValue((byte) -1);
            }
            if (!undoColumnModes.containsKey(columnSlot))
                undoColumnModes.put(columnSlot, (byte) ((exactEdits[columnSlot] ? 1 : 0) | (displayEdits[columnSlot] ? 2 : 0)));
        }
        if (!displayTerrain) exactEdits[columnSlot] = true;
        else displayEdits[columnSlot] = true;
        if (transaction && !undo.containsKey(pos)) undo.put(pos.immutable(), placed().get(pos));
        return super.setBlock(pos, state, flags, recursion);
    }

    @Override
    public boolean removeBlock(BlockPos pos, boolean moving) {
        return setBlock(pos, Blocks.AIR.defaultBlockState(), 0, 0);
    }

    @Override
    public int getHeight(Heightmap.Types type, int x, int z) {
        checkQueryActive();
        checkColumnBounds(x, z);
        int columnSlot = columnSlot(x, z);
        if (columnQueryVersions == null) columnQueryVersions = new int[COLUMN_CACHE_SIZE];
        int columnVersion = columnQueryVersions[columnSlot];
        int typeId = type.ordinal();
        int querySlot = heightQuerySlot(x, z, typeId);
        if (heightQueryEpochs[querySlot] == blockQueryEpoch
                && heightQueryXs[querySlot] == x && heightQueryZs[querySlot] == z
                && heightQueryTypes[querySlot] == typeId
                && heightQueryColumnVersions[querySlot] == columnVersion) return heightQueryValues[querySlot];
        ClientColumnSample sample = columnActive(x, z);
        int top = Math.max(Math.max(sample.surfaceY(), sample.fluidY()),
                changedTops == null ? getMinBuildHeight() : changedTops[columnSlot]);
        BlockPos.MutableBlockPos pos = heightQueryPos.set(x, top, z);
        for (int y = Math.min(top - 1, getMaxBuildHeight() - 1); y >= getMinBuildHeight(); y--) {
            if (type.isOpaque().test(getBlockState(pos.setY(y)))) {
                cacheHeightQuery(querySlot, x, z, typeId, y + 1, columnVersion);
                return y + 1;
            }
        }
        int result = getMinBuildHeight();
        cacheHeightQuery(querySlot, x, z, typeId, result, columnVersion);
        return result;
    }

    @Override public int getMinBuildHeight() { return terrain.profile().minY(); }
    @Override public int getHeight() { return terrain.profile().height(); }
    @Override public int getSeaLevel() { return terrain.seaLevel(); }
    @Override public BiomeManager getBiomeManager() { return biomes; }
    // Surface decoration runs without a chunk light engine. Crop survival needs
    // the exposed sky and local emission query even before vanilla's lighting stage.
    @Override public boolean canSeeSky(BlockPos pos) {
        if (interiorTerrain()) return false;
        return pos.getY() >= getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ());
    }
    @Override public int getRawBrightness(BlockPos pos, int skyDarken) {
        int sky = canSeeSky(pos) ? Math.max(0, 15 - skyDarken) : 0;
        return Math.max(sky, getBlockState(pos).getLightEmission());
    }
    @Override public int getBrightness(net.minecraft.world.level.LightLayer layer, BlockPos pos) {
        // Features run before propagated chunk lighting. In particular Biome's
        // snow/freeze checks query BLOCK light, not getRawBrightness(). Do not
        // delegate to the absent live-world light engine or allocate one here.
        return layer == net.minecraft.world.level.LightLayer.BLOCK
                ? getBlockState(pos).getLightEmission() : canSeeSky(pos) ? 15 : 0;
    }
    @Override public Holder<Biome> getBiome(BlockPos pos) {
        checkQueryActive();
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        int slot = blockQuerySlot(x, y, z);
        Holder<Biome> value = biomeBlockValues[slot];
        if (value != null && biomeBlockXs[slot] == x && biomeBlockYs[slot] == y && biomeBlockZs[slot] == z) {
            return value;
        }
        value = biomes.getBiome(pos);
        biomeBlockXs[slot] = x; biomeBlockYs[slot] = y; biomeBlockZs[slot] = z;
        biomeBlockValues[slot] = value;
        return value;
    }

    @Override public Holder<Biome> getNoiseBiome(int x, int y, int z) {
        checkQueryActive();
        // A tree job visits only a few quart positions. Do not zero half a
        // megabyte of biome arrays for every source chunk before its first read.
        if (biomeValues == null) growBiomeCache(1024);
        int mask = biomeValues.length - 1;
        int home = biomeSlot(x, y, z, mask);
        int slot = -1;
        for (int probe = 0; probe < 8; probe++) {
            int candidate = (home + probe) & mask;
            Holder<Biome> cached = biomeValues[candidate];
            if (cached == null) { slot = candidate; break; }
            if (biomeXs[candidate] == x && biomeYs[candidate] == y && biomeZs[candidate] == z) return cached;
        }
        if ((slot < 0 || biomeEntries >= biomeValues.length / 2)
                && biomeValues.length < BIOME_QUERY_CACHE_SIZE) {
            growBiomeCache(biomeValues.length * 2);
            return getNoiseBiome(x, y, z);
        }
        if (slot < 0) slot = home;
        Holder<Biome> biome = context.noiseBiome(x, y, z);
        if (biomeValues[slot] == null) biomeEntries++;
        biomeXs[slot] = x; biomeYs[slot] = y; biomeZs[slot] = z; biomeValues[slot] = biome;
        return biome;
    }

    @SuppressWarnings("unchecked")
    private void growBiomeCache(int size) {
        int[] oldXs = biomeXs, oldYs = biomeYs, oldZs = biomeZs;
        Holder<Biome>[] oldValues = biomeValues;
        biomeXs = new int[size]; biomeYs = new int[size]; biomeZs = new int[size];
        biomeValues = (Holder<Biome>[]) new Holder<?>[size];
        biomeEntries = 0;
        if (oldValues == null) return;
        int mask = size - 1;
        for (int i = 0; i < oldValues.length; i++) {
            if (oldValues[i] == null) continue;
            int home = biomeSlot(oldXs[i], oldYs[i], oldZs[i], mask), slot = home;
            for (int probe = 0; probe < 8; probe++) {
                int candidate = (home + probe) & mask;
                if (biomeValues[candidate] == null) { slot = candidate; break; }
            }
            if (biomeValues[slot] == null) biomeEntries++;
            biomeXs[slot] = oldXs[i]; biomeYs[slot] = oldYs[i]; biomeZs[slot] = oldZs[i];
            biomeValues[slot] = oldValues[i];
        }
    }
    @Override public Holder<Biome> getUncachedNoiseBiome(int x, int y, int z) {
        return getNoiseBiome(x, y, z);
    }

    private static long key(int x, int z) { return (long) x << 32 | z & 0xFFFFFFFFL; }

    private void noteTop(BlockPos pos) {
        if (changedTops == null) {
            changedTops = new int[COLUMN_CACHE_SIZE];
            java.util.Arrays.fill(changedTops, getMinBuildHeight());
        }
        int slot = columnSlot(pos.getX(), pos.getZ());
        changedTops[slot] = Math.max(changedTops[slot], pos.getY() + 1);
    }

    private void checkQueryActive() {
        if (terrain instanceof RustTerrainSampler rust) rust.handle();
        else if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
    }

    private static int blockQuerySlot(int x, int y, int z) {
        int hash = x * 0x9E3779B9 ^ Integer.rotateLeft(y * 0x85EBCA6B, 11)
                ^ Integer.rotateLeft(z * 0xC2B2AE35, 22);
        return (hash ^ hash >>> 16) & (BLOCK_QUERY_CACHE_SIZE - 1);
    }

    private static int heightQuerySlot(int x, int z, int type) {
        int hash = x * 0x9E3779B9 ^ Integer.rotateLeft(z * 0x85EBCA6B, 13);
        // Mixing type before folding aliases WORLD_SURFACE and OCEAN_FLOOR
        // at every column. An odd multiplier preserves distinct type slots.
        return ((hash ^ hash >>> 16) ^ type * 0x9E3779B9) & (HEIGHT_QUERY_CACHE_SIZE - 1);
    }

    private static int biomeSlot(int x, int y, int z, int mask) {
        int hash = x * 0x9E3779B9 ^ Integer.rotateLeft(y * 0x85EBCA6B, 11)
                ^ Integer.rotateLeft(z * 0xC2B2AE35, 22);
        return (hash ^ hash >>> 16) & mask;
    }

    private void cacheBlockQuery(int slot, int x, int y, int z, BlockState state) {
        blockQueryXs[slot] = x; blockQueryYs[slot] = y; blockQueryZs[slot] = z;
        blockQueryStates[slot] = state; blockQueryEpochs[slot] = blockQueryEpoch;
    }

    private void cacheHeightQuery(int slot, int x, int z, int type, int height, int columnVersion) {
        heightQueryXs[slot] = x; heightQueryZs[slot] = z; heightQueryTypes[slot] = type;
        heightQueryColumnVersions[slot] = columnVersion;
        heightQueryValues[slot] = height; heightQueryEpochs[slot] = blockQueryEpoch;
    }

    private void invalidateQueryEpoch() {
        if (++blockQueryEpoch == 0L) {
            java.util.Arrays.fill(blockQueryEpochs, 0L);
            java.util.Arrays.fill(heightQueryEpochs, 0L);
            blockQueryEpoch = 1L;
        }
    }
}
