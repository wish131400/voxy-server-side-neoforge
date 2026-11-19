package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.xantha.vss.client.prediction.feature.FeatureStampLevel;
import dev.xantha.vss.testutil.TerrainPredictionFixtures;
import dev.xantha.vss.testutil.VoxyMixinHarness;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipOutputStream;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.CappedProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The production mixin woven into vanilla's CappedProcessor: on a prediction level the processor picks the same blocks
 * as real generation with the same world seed, without asking the prediction level for a server level; every other
 * level keeps reading the server's seed.
 */
class CappedProcessorPredictionSeedTest {
    private static final long SEED = -4172144997902289642L;
    private static final long OTHER_SEED = 1234567890123L;
    private static final BlockPos OFFSET = new BlockPos(18, 41, 34);
    @TempDir static Path directory;
    private static Class<?> woven;

    @BeforeAll
    static void weave() throws Exception {
        TerrainPredictionFixtures.bootstrap();
        Path empty = directory.resolve("classpath.zip");
        try (var zip = new ZipOutputStream(java.nio.file.Files.newOutputStream(empty))) { }
        String target = "net.minecraft.world.level.levelgen.structure.templatesystem.CappedProcessor";
        var harness = new VoxyMixinHarness(empty.toString(), """
                {"required":true,"minVersion":"0.8","package":"dev.xantha.vss.mixin",
                 "compatibilityLevel":"JAVA_21","client":["client.CappedProcessorPredictionSeedMixin"],
                 "injectors":{"defaultRequire":1}}
                """);
        harness.initialize();
        Class.forName("com.llamalad7.mixinextras.MixinExtrasBootstrap").getMethod("init").invoke(null);
        byte[] transformed = harness.apply(target);
        class Loader extends ClassLoader {
            Loader() { super(CappedProcessorPredictionSeedTest.class.getClassLoader()); }
            Class<?> define() { return defineClass(target, transformed, 0, transformed.length); }
        }
        woven = new Loader().define();
    }

    /** Turns every stone into gold, so the blocks the cap admitted are visible. */
    private static final class Gild extends StructureProcessor {
        @Override
        public StructureBlockInfo processBlock(LevelReader level, BlockPos offset, BlockPos pos, StructureBlockInfo original,
                StructureBlockInfo relative, StructurePlaceSettings settings) {
            return new StructureBlockInfo(relative.pos(), Blocks.GOLD_BLOCK.defaultBlockState(), relative.nbt());
        }
        @Override protected StructureProcessorType<?> getType() { return StructureProcessorType.NOP; }
    }

    private static List<BlockPos> gilded(Object processor, ServerLevelAccessor level) throws Throwable {
        var blocks = new ArrayList<StructureBlockInfo>();
        for (int i = 0; i < 40; i++) blocks.add(new StructureBlockInfo(OFFSET.offset(i % 8, i / 8, 0), Blocks.STONE.defaultBlockState(), null));
        Method finalize = processor.getClass().getMethod("finalizeProcessing", ServerLevelAccessor.class, BlockPos.class,
                BlockPos.class, List.class, List.class, StructurePlaceSettings.class);
        try {
            @SuppressWarnings("unchecked")
            var result = (List<StructureBlockInfo>) finalize.invoke(processor, level, OFFSET, OFFSET, List.copyOf(blocks),
                    new ArrayList<>(blocks), new StructurePlaceSettings());
            return result.stream().filter(info -> info.state().is(Blocks.GOLD_BLOCK)).map(StructureBlockInfo::pos).toList();
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private static Object processor(Class<?> type) throws Exception {
        return type.getConstructor(StructureProcessor.class, net.minecraft.util.valueproviders.IntProvider.class)
                .newInstance(new Gild(), ConstantInt.of(5));
    }

    private static ServerLevel server(long seed) {
        var server = mock(ServerLevel.class);
        when(server.getSeed()).thenReturn(seed);
        return server;
    }

    private static WorldGenRegion region(long seed) {
        var region = mock(WorldGenRegion.class);
        var server = server(seed);
        when(region.getLevel()).thenReturn(server);
        return region;
    }

    private static FeatureStampLevel prediction(long seed) {
        return new FeatureStampLevel(seed, TerrainPredictionFixtures.access(), Blocks.GRASS_BLOCK.defaultBlockState());
    }

    @Test
    void vanillaProcessorFailsOnAPredictionLevel() throws Exception {
        var failure = assertThrows(UnsupportedOperationException.class, () -> gilded(processor(CappedProcessor.class), prediction(SEED)));
        assertTrue(failure.getMessage().contains("getLevel"), failure.getMessage());
    }

    @Test
    void predictionLevelChoosesTheBlocksRealGenerationChoosesWithTheSameSeed() throws Throwable {
        var real = gilded(processor(CappedProcessor.class), region(SEED));
        assertEquals(5, real.size());
        assertEquals(real, gilded(processor(woven), prediction(SEED)));
        assertNotEquals(real, gilded(processor(woven), prediction(OTHER_SEED)), "the prediction level's own seed is used");
    }

    @Test
    void realGenerationAndLevelsKeepTheServerSeed() throws Throwable {
        var region = region(OTHER_SEED);
        assertEquals(gilded(processor(CappedProcessor.class), region(OTHER_SEED)), gilded(processor(woven), region));
        verify(region).getLevel();
        var level = server(SEED);
        when(level.getLevel()).thenReturn(level);
        assertEquals(gilded(processor(CappedProcessor.class), region(SEED)), gilded(processor(woven), level));
        verify(level).getLevel();
        var accessor = mock(ServerLevelAccessor.class);
        var server = server(SEED);
        when(accessor.getLevel()).thenReturn(server);
        assertEquals(gilded(processor(CappedProcessor.class), region(SEED)), gilded(processor(woven), accessor));
        verify(accessor).getLevel();
    }
}
