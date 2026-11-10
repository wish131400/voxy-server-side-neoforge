package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import dev.xantha.vss.client.prediction.feature.FeatureStampLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;
import net.minecraft.world.level.levelgen.feature.featuresize.TwoLayersFeatureSize;
import net.minecraft.world.level.levelgen.feature.foliageplacers.BlobFoliagePlacer;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.feature.trunkplacers.StraightTrunkPlacer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PredictionConfiguredFeaturePlacementTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    private static FeatureStampLevel level() {
        return new FeatureStampLevel(42, RegistryAccess.EMPTY, Blocks.GRASS_BLOCK.defaultBlockState());
    }

    @Test void virtualDirectTreePlacementMatchesTheOrdinaryConfiguredFeature() {
        var tree = new ConfiguredFeature<>(Feature.TREE, new TreeConfiguration.TreeConfigurationBuilder(
                BlockStateProvider.simple(Blocks.OAK_LOG), new StraightTrunkPlacer(4,1,0),
                BlockStateProvider.simple(Blocks.OAK_LEAVES),
                new BlobFoliagePlacer(ConstantInt.of(2), ConstantInt.of(0), 3),
                new TwoLayersFeatureSize(1,0,1)).ignoreVines().build());
        var usual = level();
        var isolated = level();
        BlockPos pos = new BlockPos(0,64,0);
        assertTrue(tree.place(usual, null, RandomSource.create(123), pos));
        assertTrue(PredictionConfiguredFeaturePlacement.place(tree, isolated, null,
                RandomSource.create(123), pos, true));
        assertEquals(usual.placed(), isolated.placed());
        assertTrue(isolated.placed().values().stream().anyMatch(state -> state.is(Blocks.OAK_LOG)));
        assertTrue(isolated.placed().values().stream().anyMatch(state -> state.is(Blocks.OAK_LEAVES)));
    }

    @Test void bypassSkipsOnlyTheConfiguredWrapperInVirtualWorlds() {
        var called = new java.util.concurrent.atomic.AtomicInteger();
        var feature = new Feature<NoneFeatureConfiguration>(NoneFeatureConfiguration.CODEC) {
            @Override public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
                called.incrementAndGet();
                context.level().setBlock(context.origin(), Blocks.OAK_LOG.defaultBlockState(), 19, 0);
                return true;
            }
        };
        var configured = spy(new ConfiguredFeature<>(feature, NoneFeatureConfiguration.INSTANCE));
        var world = level();
        var random = RandomSource.create(7);
        BlockPos pos = new BlockPos(0,64,0);
        doThrow(new IllegalStateException("configured wrapper intercepted"))
                .when(configured).place(world, null, random, pos);
        assertTrue(PredictionConfiguredFeaturePlacement.place(configured, world, null, random, pos, true));
        assertEquals(1, called.get());
        assertEquals(Blocks.OAK_LOG, world.getBlockState(pos).getBlock());
        assertThrows(IllegalStateException.class, () ->
                PredictionConfiguredFeaturePlacement.place(configured, world, null, random, pos, false));
        assertEquals(1, called.get());
    }

    @Test void aRealWorldCannotUseTheVirtualBypass() {
        var configured = new ConfiguredFeature<>(Feature.TREE, new TreeConfiguration.TreeConfigurationBuilder(
                BlockStateProvider.simple(Blocks.OAK_LOG), new StraightTrunkPlacer(4,1,0),
                BlockStateProvider.simple(Blocks.OAK_LEAVES),
                new BlobFoliagePlacer(ConstantInt.of(2), ConstantInt.of(0), 3),
                new TwoLayersFeatureSize(1,0,1)).build());
        assertThrows(IllegalArgumentException.class, () -> PredictionConfiguredFeaturePlacement.place(
                configured, mock(net.minecraft.world.level.WorldGenLevel.class), null,
                RandomSource.create(1), BlockPos.ZERO, true));
    }

    @Test void virtualBypassRetainsTheFeatureWriteBoundaryBeforeRunningModCode() {
        var called = new java.util.concurrent.atomic.AtomicInteger();
        var feature = new Feature<NoneFeatureConfiguration>(NoneFeatureConfiguration.CODEC) {
            @Override public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
                called.incrementAndGet();
                return true;
            }
        };
        var world = new FeatureStampLevel(42, RegistryAccess.EMPTY, Blocks.GRASS_BLOCK.defaultBlockState()) {
            @Override public boolean ensureCanWrite(BlockPos pos) { return pos.getX() == 0; }
        };
        var configured = new ConfiguredFeature<>(feature, NoneFeatureConfiguration.INSTANCE);
        var random = RandomSource.create(7);
        assertFalse(PredictionConfiguredFeaturePlacement.place(configured, world, null, random, new BlockPos(1,64,0), true));
        assertEquals(0, called.get(), "Rejected origins must not execute feature code");
        assertTrue(PredictionConfiguredFeaturePlacement.place(configured, world, null, random, new BlockPos(0,64,0), true));
        assertEquals(1, called.get());
    }
}
