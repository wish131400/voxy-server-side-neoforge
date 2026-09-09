package dev.xantha.vss.client.prediction.feature;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.ticks.BlackholeTickAccess;
import net.minecraft.world.ticks.LevelTickAccess;

/**
 * Isolated, side-effect-free level used while a configured feature is
 * simulated into a predictive stamp.  Feature code sees the same ground and
 * block mutation contract as world generation, but no chunks, entities,
 * sounds, ticks or server state are touched.
 */
public class FeatureStampLevel implements WorldGenLevel {
    public static final int GROUND_Y = 64;
    public static final int SEA_LEVEL = 63;
    private static final int MIN_Y = -64;
    private static final int HEIGHT = 384;

    private final Map<BlockPos, BlockState> placed = new HashMap<>();
    private final Set<Predicate<BlockState>> groundPredicates = new LinkedHashSet<>();
    private final RandomSource random;
    private final long seed;
    private final RegistryAccess access;
    private final BlockState ground;
    private final LevelData levelData = new FeatureStampLevelData();

    public FeatureStampLevel(long seed, RegistryAccess access, BlockState ground) {
        this.seed = seed;
        this.access = access;
        this.ground = ground == null ? Blocks.GRASS_BLOCK.defaultBlockState() : ground;
        this.random = RandomSource.create(seed);
    }

    public Map<BlockPos, BlockState> placed() {
        return placed;
    }

    public Set<Predicate<BlockState>> groundPredicates() {
        return groundPredicates;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        BlockState state = placed.get(pos);
        if (state != null) return state;
        if (pos.getY() >= GROUND_Y) return Blocks.AIR.defaultBlockState();
        if (pos.getY() == SEA_LEVEL || !ground.is(Blocks.GRASS_BLOCK)) return ground;
        return Blocks.DIRT.defaultBlockState();
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public boolean isStateAtPosition(BlockPos pos, Predicate<BlockState> predicate) {
        if (pos.getY() < GROUND_Y && !placed.containsKey(pos)) groundPredicates.add(predicate);
        return predicate.test(getBlockState(pos));
    }

    @Override
    public boolean isFluidAtPosition(BlockPos pos, Predicate<FluidState> predicate) {
        return predicate.test(getFluidState(pos));
    }

    @Override
    public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursion) {
        placed.put(pos.immutable(), state);
        return true;
    }

    @Override
    public boolean removeBlock(BlockPos pos, boolean moving) {
        placed.put(pos.immutable(), Blocks.AIR.defaultBlockState());
        return true;
    }

    @Override
    public boolean destroyBlock(BlockPos pos, boolean drop, Entity entity, int recursion) {
        return removeBlock(pos, false);
    }

    @Override
    public BlockPos getHeightmapPos(Heightmap.Types type, BlockPos pos) {
        return new BlockPos(pos.getX(), getHeight(type, pos.getX(), pos.getZ()), pos.getZ());
    }

    @Override
    public int getHeight(Heightmap.Types type, int x, int z) {
        int top = GROUND_Y;
        for (Map.Entry<BlockPos, BlockState> entry : placed.entrySet()) {
            BlockPos pos = entry.getKey();
            if (pos.getX() == x && pos.getZ() == z && !entry.getValue().isAir()) {
                top = Math.max(top, pos.getY() + 1);
            }
        }
        return top;
    }

    @Override public int getMinBuildHeight() { return MIN_Y; }
    @Override public int getHeight() { return HEIGHT; }
    @Override public int getSeaLevel() { return SEA_LEVEL; }
    @Override public RandomSource getRandom() { return random; }
    public long seed() { return seed; }
    @Override public long getSeed() { return seed; }
    @Override public boolean isClientSide() { return false; }
    @Override public boolean hasChunk(int x, int z) { return true; }
    @Override public float getShade(Direction direction, boolean shade) { return 1.0F; }
    @Override public int getBlockTint(BlockPos pos, ColorResolver resolver) { return 0xFFFFFF; }
    @Override public int getSkyDarken() { return 0; }
    @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
    @Override public <T extends BlockEntity> Optional<T> getBlockEntity(BlockPos pos, BlockEntityType<T> type) {
        return Optional.empty();
    }
    @Override public <T extends Entity> List<T> getEntities(EntityTypeTest<Entity, T> test, AABB box,
                                                              Predicate<? super T> predicate) {
        return List.of();
    }
    @Override public List<Entity> getEntities(Entity entity, AABB box, Predicate<? super Entity> predicate) {
        return List.of();
    }
    @Override public List<? extends Player> players() { return List.of(); }
    @Override public List<VoxelShape> getEntityCollisions(Entity entity, AABB box) { return List.of(); }
    @Override public FeatureFlagSet enabledFeatures() { return FeatureFlags.DEFAULT_FLAGS; }
    @Override public long dayTime() { return 0L; }
    @Override public long nextSubTickCount() { return 0L; }
    @Override public void playSound(Player player, BlockPos pos, SoundEvent sound, SoundSource source,
                                    float volume, float pitch) { }
    @Override public void addParticle(ParticleOptions options, double x, double y, double z,
                                      double dx, double dy, double dz) { }
    @Override public void levelEvent(Player player, int event, BlockPos pos, int data) { }
    @Override public void gameEvent(Holder<GameEvent> event, Vec3 position, GameEvent.Context context) { }
    @Override public ServerLevel getLevel() { throw unsupported("getLevel"); }
    @Override public MinecraftServer getServer() { throw unsupported("getServer"); }
    @Override public RegistryAccess registryAccess() { return access; }
    @Override public DimensionType dimensionType() { throw unsupported("dimensionType"); }
    @Override public BiomeManager getBiomeManager() { throw unsupported("getBiomeManager"); }
    @Override public Holder<Biome> getUncachedNoiseBiome(int x, int y, int z) { throw unsupported("biome"); }
    @Override public Holder<Biome> getNoiseBiome(int x, int y, int z) { throw unsupported("biome"); }
    @Override public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean create) {
        throw unsupported("getChunk");
    }
    @Override public ChunkSource getChunkSource() { throw unsupported("getChunkSource"); }
    @Override public BlockGetter getChunkForCollisions(int x, int z) { throw unsupported("collisions"); }
    @Override public WorldBorder getWorldBorder() { throw unsupported("worldBorder"); }
    @Override public LevelLightEngine getLightEngine() { throw unsupported("lightEngine"); }
    @Override public LevelData getLevelData() { return levelData; }
    @Override public DifficultyInstance getCurrentDifficultyAt(BlockPos pos) { throw unsupported("difficulty"); }
    @Override public LevelTickAccess<net.minecraft.world.level.block.Block> getBlockTicks() {
        return BlackholeTickAccess.emptyLevelList();
    }
    @Override public LevelTickAccess<Fluid> getFluidTicks() { return BlackholeTickAccess.emptyLevelList(); }

    private static UnsupportedOperationException unsupported(String method) {
        return new UnsupportedOperationException("VSS feature stamp level does not support " + method);
    }
}
