package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.xantha.vss.client.prediction.feature.FeatureStampLevel;
import dev.xantha.vss.testutil.TerrainPredictionFixtures;
import dev.xantha.vss.testutil.VoxyMixinHarness;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelSimulatedReader;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Executes the production mixin on the installed RoadWeaver RoadSpatialIndex against prediction and real levels. */
@EnabledIfSystemProperty(named = "vss.roadweaverJar", matches = ".+")
class RoadWeaverPredictionRoadQueryTest {
    private static final String TARGET = "net.shiroha233.roadweaver.persistence.RoadSpatialIndex";
    private static final BlockPos POS = new BlockPos(8, 70, -24);
    private static Class<?> woven;
    private static Class<?> original;

    @BeforeAll
    static void weave() throws Exception {
        TerrainPredictionFixtures.bootstrap();
        String jar = System.getProperty("vss.roadweaverJar");
        var harness = new VoxyMixinHarness(jar, """
                {"required":true,"minVersion":"0.8","package":"dev.xantha.vss.mixin",
                 "compatibilityLevel":"JAVA_21","client":["client.RoadWeaverPredictionRoadQueryMixin"]}
                """);
        byte[] transformed = harness.apply(TARGET);
        byte[] shipped;
        try (var zip = new java.util.zip.ZipFile(jar)) {
            shipped = zip.getInputStream(zip.getEntry(TARGET.replace('.', '/') + ".class")).readAllBytes();
        }
        int hooks = 0;
        var node = new org.objectweb.asm.tree.ClassNode();
        new org.objectweb.asm.ClassReader(transformed).accept(node, 0);
        for (var method : node.methods)
            for (var instruction : method.instructions)
                if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call && call.name.contains("vss$")) hooks++;
        assertEquals(2, hooks, "both isNearRoad overloads carry the hook");
        woven = loader(jar).define(transformed);
        original = loader(jar).define(shipped);
    }

    private static final class Loader extends java.net.URLClassLoader {
        Loader(String jar) throws Exception {
            super(new java.net.URL[]{Path.of(jar).toUri().toURL()}, RoadWeaverPredictionRoadQueryTest.class.getClassLoader());
        }
        Class<?> define(byte[] bytes) { return defineClass(TARGET, bytes, 0, bytes.length); }
    }

    private static Loader loader(String jar) throws Exception { return new Loader(jar); }

    private static boolean query(Class<?> index, Class<?> parameter, Object level) throws Throwable {
        Method method = index.getMethod("isNearRoad", parameter, BlockPos.class);
        try {
            return (boolean) method.invoke(null, level, POS);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private static FeatureStampLevel prediction(ResourceLocation dimension) {
        return new FeatureStampLevel(42, TerrainPredictionFixtures.access(), Blocks.GRASS_BLOCK.defaultBlockState()) {
            @Override public ResourceLocation predictionDimension() { return dimension; }
        };
    }

    @Test
    void predictionLevelsOfOtherDimensionsAnswerFalseWithoutAServer() throws Throwable {
        for (String id : new String[]{"minecraft:the_nether", "minecraft:the_end", "twilightforest:twilight_forest"}) {
            var level = prediction(ResourceLocation.parse(id));
            assertThrows(UnsupportedOperationException.class, () -> query(original, WorldGenLevel.class, level), "shipped RoadWeaver: " + id);
            assertThrows(UnsupportedOperationException.class, () -> query(original, LevelSimulatedReader.class, level), "shipped RoadWeaver: " + id);
            assertFalse(query(woven, WorldGenLevel.class, level), id);
            assertFalse(query(woven, LevelSimulatedReader.class, level), id);
        }
    }

    @Test
    void overworldAndUnknownPredictionLevelsKeepRoadWeaversOwnPath() {
        for (var level : new FeatureStampLevel[]{prediction(Level.OVERWORLD.location()), prediction(null)}) {
            assertThrows(UnsupportedOperationException.class, () -> query(woven, WorldGenLevel.class, level));
            assertThrows(UnsupportedOperationException.class, () -> query(woven, LevelSimulatedReader.class, level));
        }
    }

    @Test
    void realLevelsKeepRoadWeaversOwnAnswer() throws Throwable {
        var nether = mock(ServerLevel.class);
        when(nether.dimension()).thenReturn(Level.NETHER);
        assertEquals(query(original, WorldGenLevel.class, nether), query(woven, WorldGenLevel.class, nether));
        assertFalse(query(woven, WorldGenLevel.class, nether));
        verify(nether, atLeastOnce()).dimension();
    }
}
