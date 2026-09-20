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
    private final ClientTerrainSampler terrain;
    private final ClientTerrainSampler context;
    private final int originX;
    private final int originZ;
    private final BiomeManager biomes;
    private record Quart(int x, int y, int z) { }
    // A job keeps its own exact answers even when concurrent regions churn the shared cache.
    private final Map<Quart, Holder<Biome>> jobBiomes = new HashMap<>();
    private final VssLodSampleCache sharedColumns;
    private final Map<Long, ClientColumnSample> columns = new HashMap<>();
    // State IDs are richer than ClientColumnSample's block IDs. Retain the
    // original immutable record for this bounded job instead of looking it up
    // in the shared sampler for every ground/heightmap/tree-space query.
    private final Map<Long, int[]> nativeColumns = new HashMap<>();
    private final Map<Long, int[]> displayColumns = new HashMap<>();
    private final java.util.Set<Long> exactEdits = new java.util.HashSet<>();
    private final java.util.Set<Long> displayEdits = new java.util.HashSet<>();
    private boolean displayTerrain;
    private final Map<Long, Integer> changedTops = new HashMap<>();
    private final Map<BlockPos, BlockState> undo = new HashMap<>();
    private final java.util.Set<BlockPos> structureBlocks = new java.util.HashSet<>();
    private boolean transaction;
    private boolean structureTransaction;
    private int writes;
    private final Map<Long, net.minecraft.world.level.chunk.ChunkAccess> virtualChunks = new HashMap<>();

    /** Chunk-facing block queries stay in the same bounded transaction as level writes.
     * Postprocessing/ticks are visual-generation metadata; no live world is touched. */
    @Override public net.minecraft.world.level.chunk.ChunkAccess getChunk(int x, int z,
            net.minecraft.world.level.chunk.status.ChunkStatus status, boolean create) {
        checkColumnBounds(x * 16, z * 16);
        return virtualChunks.computeIfAbsent(key(x, z), ignored -> new net.minecraft.world.level.chunk.ProtoChunk(
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
        });
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
        checkColumnBounds(x, z);
        if (interiorTerrain()) return columns.computeIfAbsent(key(x, z), packed -> sharedColumns == null
                ? terrain.sampleInterior(x, z) : sharedColumns.getOrCompute(packed, ignored -> terrain.sampleInterior(x, z)));
        if (displayTerrain && terrain instanceof RustTerrainSampler rust)
            return rust.surfaceSample(nativeColumn(rust,x,z));
        return columns.computeIfAbsent(key(x, z), packed -> {
            if (terrain instanceof RustTerrainSampler rust) {
                return rust.surfaceSample(nativeColumn(rust, x, z));
            }
            return sharedColumns == null ? terrain.sampleSurface(x, z)
                    : sharedColumns.getOrCompute(packed, ignored -> terrain.sampleSurface(x, z));
        });
    }

    private int[] nativeColumn(RustTerrainSampler rust, int x, int z) {
        checkColumnBounds(x, z);
        rust.handle(); // Retained data must not keep a cancelled world usable.
        long key = key(x, z);
        if (displayTerrain) {
            int[] record = displayColumns.get(key);
            if (record == null) {
                int ox = Math.floorDiv(x,4)*4, oz = Math.floorDiv(z,4)*4;
                int[][] rows = rust.decorationDisplayPage(ox,oz);
                for (int i=0;i<16;i++) displayColumns.put(key(ox+i/4,oz+i%4),rows[i]);
                record = displayColumns.get(key);
            }
            return record;
        }
        int[] record = nativeColumns.get(key);
        if (record == null) {
            record = rust.surfaceRecord(x, z);
            nativeColumns.put(key, record);
        }
        return record;
    }

    void useDisplayTerrain(boolean display) { displayTerrain = display; }
    boolean interiorTerrain() { return terrain.interiorTerrain(); }
    boolean usesDisplayTerrain() { return displayTerrain; }

    /** Structures/custom feature cuts retain their original extraction floor.
     * Pure visual plant columns use the same ground as their placement. */
    ClientColumnSample exteriorColumn(int x, int z) {
        boolean previous = displayTerrain;
        displayTerrain = !exactEdits.contains(key(x,z)) && displayEdits.contains(key(x,z));
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
        transaction = true;
        structureTransaction = false;
    }

    void beginStructure() { beginFeature(); structureTransaction = true; }

    void endFeature(boolean success) {
        if (!success) undo.forEach((pos, state) -> {
            // Routed through `noteWrite` so the rollback is registered as a
            // write: an unregistered one would let the next round trip claim
            // the two sides agree.
            noteWrite(pos, state);
        });
        if (!success) {
            changedTops.clear();
            placed().forEach((pos, state) -> {
                if (!state.isAir()) changedTops.merge(key(pos.getX(), pos.getZ()), pos.getY() + 1, Math::max);
            });
        }
        if (success && structureTransaction) structureBlocks.addAll(undo.keySet());
        undo.clear();
        transaction = false;
    }

    boolean isStructureBlock(BlockPos pos) { return structureBlocks.contains(pos); }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        BlockState placed = placed().get(pos);
        if (placed != null) return placed;
        if (pos.getY() < getMinBuildHeight() || pos.getY() >= getMaxBuildHeight()) {
            return Blocks.AIR.defaultBlockState();
        }
        if (interiorTerrain()) {
            int block = column(pos.getX(), pos.getZ()).volume().blockAt(pos.getY());
            return block == ClientColumnSample.NO_BLOCK ? Blocks.AIR.defaultBlockState()
                    : BuiltInRegistries.BLOCK.byId(block).defaultBlockState();
        }
        if (terrain instanceof RustTerrainSampler rust) {
            // Bounds and cancellation are checked by nativeColumn. A block
            // query needs the original record, not a second metadata lookup.
            return rust.proxyBlock(nativeColumn(rust, pos.getX(), pos.getZ()), pos.getY());
        }
        ClientColumnSample sample = column(pos.getX(), pos.getZ());
        if (pos.getY() >= sample.surfaceY()) {
            if (sample.hasFluid() && pos.getY() < sample.fluidY()) {
                if (sample.ice() && pos.getY() == sample.fluidY() - 1) {
                    return Blocks.ICE.defaultBlockState();
                }
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
        if (!ensureCanWrite(pos)) {
            throw new UnsupportedOperationException("decoration write outside its bounded region");
        }
        if (++writes > MAX_WRITES || Thread.currentThread().isInterrupted()) {
            throw new UnsupportedOperationException("decoration exceeded its work budget");
        }
        changedTops.merge(key(pos.getX(), pos.getZ()), pos.getY() + 1, Math::max);
        if (!displayTerrain) exactEdits.add(key(pos.getX(),pos.getZ()));
        else displayEdits.add(key(pos.getX(),pos.getZ()));
        if (transaction && !undo.containsKey(pos)) undo.put(pos.immutable(), placed().get(pos));
        return super.setBlock(pos, state, flags, recursion);
    }

    @Override
    public boolean removeBlock(BlockPos pos, boolean moving) {
        return setBlock(pos, Blocks.AIR.defaultBlockState(), 0, 0);
    }

    @Override
    public int getHeight(Heightmap.Types type, int x, int z) {
        ClientColumnSample sample = column(x, z);
        int top = Math.max(Math.max(sample.surfaceY(), sample.fluidY()),
                changedTops.getOrDefault(key(x, z), getMinBuildHeight()));
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, top, z);
        for (int y = Math.min(top - 1, getMaxBuildHeight() - 1); y >= getMinBuildHeight(); y--) {
            if (type.isOpaque().test(getBlockState(pos.setY(y)))) return y + 1;
        }
        return getMinBuildHeight();
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
    @Override public Holder<Biome> getNoiseBiome(int x, int y, int z) {
        Quart key = new Quart(x, y, z);
        Holder<Biome> cached = jobBiomes.get(key);
        if (cached != null) return cached;
        Holder<Biome> biome = context.noiseBiome(x, y, z);
        if (jobBiomes.size() < 16384) jobBiomes.put(key, biome);
        return biome;
    }
    @Override public Holder<Biome> getUncachedNoiseBiome(int x, int y, int z) {
        return getNoiseBiome(x, y, z);
    }

    private static long key(int x, int z) { return (long) x << 32 | z & 0xFFFFFFFFL; }
}
