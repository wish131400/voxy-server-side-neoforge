package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.xantha.vss.testutil.TerrainPredictionFixtures;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.*;
import org.junit.jupiter.api.*;

class ClientSurfaceResolverTest {
    @BeforeAll static void bootstrap() { TerrainPredictionFixtures.bootstrap(); }

    @Test void reusedChunkContextReadsTheCurrentTerrain() throws Exception {
        var source = mock(SurfaceRules.RuleSource.class, invocation -> {
            if (!invocation.getMethod().getName().equals("apply")) return null;
            Object context = invocation.getArgument(0);
            var field = context.getClass().getDeclaredField("chunk"); field.setAccessible(true);
            var chunk = (ChunkAccess) field.get(context);
            Class<?> rule = Class.forName("net.minecraft.world.level.levelgen.SurfaceRules$SurfaceRule");
            return Proxy.newProxyInstance(rule.getClassLoader(), new Class<?>[]{rule}, (proxy, method, args) ->
                    chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, 3, 3) >= 90
                            ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.DIRT.defaultBlockState());
        });
        var base = TerrainPredictionFixtures.noise().generatorSettings().value();
        var settings = new NoiseGeneratorSettings(base.noiseSettings(), base.defaultBlock(), base.defaultFluid(),
                base.noiseRouter(), source, base.spawnTarget(), base.seaLevel(), base.disableMobGeneration(),
                base.isAquifersEnabled(), base.oreVeinsEnabled(), base.useLegacyRandomSource());
        var c = PredictionJavaExteriorTest.context(settings);
        var resolver = new ClientSurfaceResolver(c.generator(), c.random(), c.heights(),
                TerrainPredictionFixtures.access().registryOrThrow(Registries.BIOME));
        int stone = BuiltInRegistries.BLOCK.getId(Blocks.STONE);
        var sample = PredictionColumnVolume.sample(60, 20, y -> stone, block -> 0).asSample();
        try (var request = resolver.request()) {
            for (boolean preview : new boolean[]{false, true}) {
                assertEquals(BuiltInRegistries.BLOCK.getId(Blocks.DIRT), (preview
                        ? resolver.resolvePreview(sample, 3, 3, (x,z) -> 80)
                        : resolver.resolve(sample, 3, 3, (x,z) -> 80)).topBlockIndex());
                assertEquals(BuiltInRegistries.BLOCK.getId(Blocks.GRASS_BLOCK), (preview
                        ? resolver.resolvePreview(sample, 7, 7, (x,z) -> 96)
                        : resolver.resolve(sample, 7, 7, (x,z) -> 96)).topBlockIndex());
            }
        }
    }

    @Test void rulesBoundAtCreationUseActualChunksForExactPreviewAndInterior() throws Exception {
        var bound = new ArrayList<ChunkPos>();
        // Models UTF CellCondition: bind the chunk once when the rule is created,
        // then read local coordinates against that chunk on every apply.
        var source = mock(SurfaceRules.RuleSource.class, invocation -> {
            if (!invocation.getMethod().getName().equals("apply")) return null;
            Object context = invocation.getArgument(0);
            var field = context.getClass().getDeclaredField("chunk"); field.setAccessible(true);
            var position = ((ChunkAccess) field.get(context)).getPos(); bound.add(position);
            Class<?> rule = Class.forName("net.minecraft.world.level.levelgen.SurfaceRules$SurfaceRule");
            return Proxy.newProxyInstance(rule.getClassLoader(), new Class<?>[]{rule}, (proxy, method, args) ->
                    ((position.x + position.z) & 1) == 0 ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.DIRT.defaultBlockState());
        });
        var base = TerrainPredictionFixtures.noise().generatorSettings().value();
        var settings = new NoiseGeneratorSettings(base.noiseSettings(), base.defaultBlock(), base.defaultFluid(),
                base.noiseRouter(), source, base.spawnTarget(), base.seaLevel(), base.disableMobGeneration(),
                base.isAquifersEnabled(), base.oreVeinsEnabled(), base.useLegacyRandomSource());
        var c = PredictionJavaExteriorTest.context(settings);
        var resolver = new ClientSurfaceResolver(c.generator(), c.random(), c.heights(),
                TerrainPredictionFixtures.access().registryOrThrow(Registries.BIOME));
        int stone = BuiltInRegistries.BLOCK.getId(Blocks.STONE);
        var sample = PredictionColumnVolume.sample(60, 20, y -> stone, block -> 0).asSample();
        for (int x : new int[]{3, 19, -13, 1635, 3}) {
            int z = -29;
            int expected = BuiltInRegistries.BLOCK.getId((((x >> 4) + (z >> 4)) & 1) == 0 ? Blocks.GRASS_BLOCK : Blocks.DIRT);
            assertEquals(expected, resolver.resolve(sample, x, z, (bx,bz) -> 80).topBlockIndex());
            assertEquals(expected, resolver.resolvePreview(sample, x, z, (bx,bz) -> 80).topBlockIndex());
            assertEquals(expected, resolver.resolveInterior(sample, x, z, (bx,bz) -> 80).topBlockIndex());
        }
        assertEquals(8, bound.size(), "exact/interior share four chunk contexts; previews have their own four");
        assertFalse(bound.contains(new ChunkPos(0,0)));
        for (int x = 0; x < 32; x++) resolver.resolve(sample, x * 16, 0, (bx,bz) -> 80);
        var field = ClientSurfaceResolver.class.getDeclaredField("workers"); field.setAccessible(true);
        assertTrue(((java.util.Map<?,?>) ((ThreadLocal<?>) field.get(resolver)).get()).size() <= 8,
                "travelling through chunks must not retain every rule context");
        try (var request = resolver.request()) {
            try (var nested = resolver.request()) { resolver.resolve(sample, 3, -29, (bx,bz) -> 80); }
            assertFalse(((java.util.Map<?,?>) ((ThreadLocal<?>) field.get(resolver)).get()).isEmpty());
        }
        assertTrue(((java.util.Map<?,?>) ((ThreadLocal<?>) field.get(resolver)).get()).isEmpty(),
                "task completion releases chunk-bound rules and upstream pooled tiles");
    }
}
