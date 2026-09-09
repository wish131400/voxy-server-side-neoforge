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
    private final VssLodSampleCache sharedColumns;
    private final Map<Long, ClientColumnSample> columns = new HashMap<>();
    private final Map<Long, Integer> changedTops = new HashMap<>();
    private final Map<BlockPos, BlockState> undo = new HashMap<>();
    private final java.util.Set<BlockPos> structureBlocks = new java.util.HashSet<>();
    private boolean transaction;
    private boolean structureTransaction;
    private int writes;

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
        if (x < originX - 32 || x >= originX + 48 || z < originZ - 32 || z >= originZ + 48) {
            throw new UnsupportedOperationException("decoration read outside its bounded region");
        }
        return columns.computeIfAbsent(key(x, z), packed -> sharedColumns == null
                ? terrain.sampleSurface(x, z) : sharedColumns.getOrCompute(packed, ignored -> terrain.sampleSurface(x, z)));
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
            if (state == null) placed().remove(pos);
            else placed().put(pos, state);
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
        if (terrain instanceof RustTerrainSampler rust) {
            // Enforce the same read boundary before accessing native columns.
            column(pos.getX(),pos.getZ());
            return rust.proxyBlock(pos.getX(),pos.getY(),pos.getZ());
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
        return pos.getY() >= getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ());
    }
    @Override public int getRawBrightness(BlockPos pos, int skyDarken) {
        int sky = canSeeSky(pos) ? Math.max(0, 15 - skyDarken) : 0;
        return Math.max(sky, getBlockState(pos).getLightEmission());
    }
    @Override public Holder<Biome> getNoiseBiome(int x, int y, int z) {
        return context.noiseBiome(x, y, z);
    }
    @Override public Holder<Biome> getUncachedNoiseBiome(int x, int y, int z) {
        return getNoiseBiome(x, y, z);
    }

    private static long key(int x, int z) { return (long) x << 32 | z & 0xFFFFFFFFL; }
}
