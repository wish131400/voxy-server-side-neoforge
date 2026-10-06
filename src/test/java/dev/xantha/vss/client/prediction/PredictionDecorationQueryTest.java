package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.Lifecycle;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import org.junit.jupiter.api.*;

class PredictionDecorationQueryTest {
    static RegistryAccess access;
    static Holder<Biome> warm, cold;
    static final DimensionProfile PROFILE = new DimensionProfile(
            net.minecraft.resources.ResourceLocation.withDefaultNamespace("overworld"), 42, -64, 384, "noise", "minecraft:overworld", 77);
    final AtomicInteger biomes = new AtomicInteger();

    @BeforeAll static void bootstrap() {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        var lookup = VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME);
        var registry = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        lookup.listElements().forEach(h -> registry.register(h.key(), h.value(), RegistrationInfo.BUILT_IN));
        registry.freeze();
        access = new RegistryAccess.ImmutableRegistryAccess(List.of(registry));
        warm = registry.getHolderOrThrow(Biomes.PLAINS);
        cold = registry.getHolderOrThrow(Biomes.SNOWY_PLAINS);
    }

    ClientTerrainSampler terrain() {
        return new ClientTerrainSampler(42, PROFILE) {
            @Override public ClientColumnSample sampleSurface(int x, int z) {
                var s = PredictionSimpleVegetationTest.sample(100);
                return new ClientColumnSample(s.surfaceY(), 104, 0, s.topBlockIndex(), 0, 0, 0, 0,
                        1, s.flags(), 0, s.underBlockIndex(), s.deepBlockIndex(),
                        s.surfaceBottom(), s.lowerTop(), s.lowerBottom(), s.spanFloor());
            }
            @Override Holder<Biome> noiseBiome(int x, int y, int z) {
                biomes.incrementAndGet();
                return ((x ^ y ^ z) & 1) == 0 ? warm : cold;
            }
        };
    }

    @Test void allTwentyFiveVirtualChunksAreStableAndDistinctAtNegativeCoordinates() {
        var terrain = terrain();
        var level = new PredictionDecorationLevel(terrain, terrain, access, -4, -7);
        Set<Object> chunks = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int z = -9; z <= -5; z++) for (int x = -6; x <= -2; x++) {
            var chunk = level.getChunk(x, z, net.minecraft.world.level.chunk.status.ChunkStatus.EMPTY, true);
            assertSame(chunk, level.getChunk(x, z, net.minecraft.world.level.chunk.status.ChunkStatus.FEATURES, false));
            assertEquals(x, chunk.getPos().x);
            assertEquals(z, chunk.getPos().z);
            assertTrue(chunks.add(chunk));
        }
        assertEquals(25, chunks.size());
        assertThrows(UnsupportedOperationException.class,
                () -> level.getChunk(-7, -7, net.minecraft.world.level.chunk.status.ChunkStatus.EMPTY, true));
        assertThrows(UnsupportedOperationException.class,
                () -> level.getChunk(-4, -4, net.minecraft.world.level.chunk.status.ChunkStatus.EMPTY, true));
    }

    @Test void optionalPredicateReadsUseGroundFluidsOffsetsAndTransactionalEdits() {
        var terrain = terrain();
        var level = new PredictionDecorationLevel(terrain, terrain, access, -1, -1);
        var position = new BlockPos(-16, 99, -16);
        assertTrue(level.predicateState(position, 0, 0, 0).is(Blocks.GRASS_BLOCK));
        assertTrue(level.predicateState(position, 0, 2, 0).is(Blocks.WATER));
        assertTrue(level.predicateState(position, 0, 6, 0).isAir());
        assertTrue(level.predicateState(position, 0, 300, 0).is(Blocks.VOID_AIR));
        var neighbor = position.offset(-1, 0, 0);
        level.beginFeature();
        level.setBlock(neighbor, Blocks.DIAMOND_BLOCK.defaultBlockState(), 0, 0);
        assertTrue(level.predicateState(position, -1, 0, 0).is(Blocks.DIAMOND_BLOCK));
        level.endFeature(false);
        assertTrue(level.predicateState(position, -1, 0, 0).is(Blocks.GRASS_BLOCK));
        assertThrows(UnsupportedOperationException.class, () -> level.predicateState(position, 64, 0, 0));
    }

    @Test void heightCachesDistinguishPredicatesAndInvalidateOnWriteRollbackAndRemoval() {
        var terrain = terrain();
        var level = new PredictionDecorationLevel(terrain, terrain, access, 0, 0);
        assertEquals(104, level.getHeight(Heightmap.Types.WORLD_SURFACE, 0, 0));
        assertEquals(100, level.getHeight(Heightmap.Types.OCEAN_FLOOR, 0, 0));
        for (int i = 0; i < 50; i++) {
            assertEquals(104, level.getHeight(Heightmap.Types.MOTION_BLOCKING, 0, 0));
            assertEquals(100, level.getHeight(Heightmap.Types.OCEAN_FLOOR, 0, 0));
        }
        var position = new BlockPos(0, 120, 0);
        level.beginFeature();
        level.setBlock(position, Blocks.STONE.defaultBlockState(), 0, 0);
        assertEquals(121, level.getHeight(Heightmap.Types.OCEAN_FLOOR, 0, 0));
        level.endFeature(false);
        assertEquals(100, level.getHeight(Heightmap.Types.OCEAN_FLOOR, 0, 0));
        assertEquals(104, level.getHeight(Heightmap.Types.WORLD_SURFACE, 0, 0));
        level.setBlock(new BlockPos(0, 99, 0), Blocks.AIR.defaultBlockState(), 0, 0);
        assertEquals(99, level.getHeight(Heightmap.Types.OCEAN_FLOOR, 0, 0));
    }

    @Test void growingBiomeQueriesRetainEarlierAnswersAcrossHeightAndNegativeCoordinates() {
        var terrain = terrain();
        var level = new PredictionDecorationLevel(terrain, terrain, access, -4, -4);
        for (int i = 0; i < 768; i++) {
            int x = i % 24 - 12, y = i / 192 - 2, z = i / 24 % 8 - 4;
            assertSame(((x ^ y ^ z) & 1) == 0 ? warm : cold, level.getNoiseBiome(x, y, z));
        }
        int reads = biomes.get();
        for (int i = 767; i >= 0; i--) {
            int x = i % 24 - 12, y = i / 192 - 2, z = i / 24 % 8 - 4;
            assertSame(((x ^ y ^ z) & 1) == 0 ? warm : cold, level.getNoiseBiome(x, y, z));
        }
        assertEquals(reads, biomes.get(), "growing the cache must retain repeated quart answers");
    }

    @Test void collidingBiomeCoordinatesKeepAllDimensionsAndReuseRepeatedBlockQueries() {
        var terrain = terrain();
        var level = new PredictionDecorationLevel(terrain, terrain, access, 0, 0);
        for (int i = 0; i < 40_000; i++) {
            int x = i - 20_000, y = i % 17, z = i % 29;
            Holder<Biome> expected = ((x ^ y ^ z) & 1) == 0 ? warm : cold;
            assertSame(expected, level.getNoiseBiome(x, y, z));
            assertSame(expected, level.getNoiseBiome(x, y, z));
        }
        var pos = new BlockPos(-3, 103, 7);
        Holder<Biome> expected = level.getBiomeManager().getBiome(pos);
        assertSame(expected, level.getBiome(pos));
        int count = biomes.get();
        for (int i = 0; i < 100; i++) assertSame(expected, level.getBiome(pos));
        assertEquals(count, biomes.get(), "a block-position hit must bypass BiomeManager's corner queries");
        assertSame(level.getBiomeManager().getBiome(pos.above(64)), level.getBiome(pos.above(64)));
    }
}
