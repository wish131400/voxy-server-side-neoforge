package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.xantha.vss.client.prediction.feature.FeatureStampLevel;
import dev.xantha.vss.testutil.TerrainPredictionFixtures;
import dev.xantha.vss.testutil.VoxyMixinHarness;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.placement.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Executes the production mixin on the installed FTF class, including its inherited placement stream. */
@EnabledIfSystemProperty(named="vss.freeTerraForgedJar", matches=".+")
class FreeTerraForgedDimensionMixinTest {
    private static Class<?> filter;
    @BeforeAll static void bootstrap() throws Exception {
        TerrainPredictionFixtures.bootstrap();
        String target="etcodehome.freeterraforged.world.worldgen.feature.placement.BlacklistDimensionFilter";
        var harness=new VoxyMixinHarness(System.getProperty("vss.freeTerraForgedJar"), """
                {"required":true,"minVersion":"0.8","package":"dev.xantha.vss.mixin.client",
                 "compatibilityLevel":"JAVA_21","client":["FreeTerraForgedPredictionDimensionMixin"]}
                """);
        byte[] transformed=harness.apply(target);
        class Loader extends java.net.URLClassLoader {
            Loader() throws Exception { super(new java.net.URL[]{java.nio.file.Path.of(
                    System.getProperty("vss.freeTerraForgedJar")).toUri().toURL()}, FreeTerraForgedDimensionMixinTest.class.getClassLoader()); }
            Class<?> define() { return defineClass(target,transformed,0,transformed.length); }
        }
        filter=new Loader().define();
    }
    private static PlacementModifier modifier(ResourceLocation... blacklist) throws Exception {
        var constructor=filter.getDeclaredConstructor(List.class);constructor.setAccessible(true);
        return (PlacementModifier)constructor.newInstance(java.util.Arrays.stream(blacklist)
                .map(id->ResourceKey.create(Registries.LEVEL_STEM,id)).toList());
    }
    private static FeatureStampLevel level(ResourceLocation dimension) {
        return new FeatureStampLevel(42,TerrainPredictionFixtures.access(),Blocks.GRASS_BLOCK.defaultBlockState()) {
            @Override public ResourceLocation predictionDimension() { return dimension; }
        };
    }
    private static long positions(PlacementModifier modifier,WorldGenLevel level) {
        var context=new PlacementContext(level,TerrainPredictionFixtures.noise(),Optional.empty());
        return modifier.getPositions(context,RandomSource.create(42),new BlockPos(0,64,0)).count();
    }
    @Test void virtualWorldsUseDimensionIdentityWithoutAccessingAServer() throws Exception {
        var overworld=level(Level.OVERWORLD.location());
        assertThrows(UnsupportedOperationException.class,overworld::getServer);
        assertEquals(1,positions(modifier(Level.NETHER.location(),Level.END.location()),overworld));
        assertEquals(0,positions(modifier(Level.OVERWORLD.location()),overworld));
        assertEquals(0,positions(modifier(Level.END.location()),level(Level.END.location())));
        assertEquals(1,positions(modifier(),overworld));
        assertNull(PredictionDimensionFilter.allows(new Object(),Level.OVERWORLD.location()));
    }
    @Test void actualServerWorldsKeepTheOriginalFtfPredicate() throws Exception {
        var world=mock(WorldGenLevel.class);
        var server=mock(net.minecraft.server.MinecraftServer.class);
        var end=mock(net.minecraft.server.level.ServerLevel.class);
        when(world.getServer()).thenReturn(server);when(world.getLevel()).thenReturn(end);
        when(server.getLevel(Level.END)).thenReturn(end);
        assertEquals(0,positions(modifier(Level.END.location()),world));
        verify(world).getServer();
        verify(server).getLevel(Level.END);
    }
}
