package dev.xantha.vss.networking.server.session;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.MapCodec;
import dev.xantha.vss.testutil.TerrainPredictionFixtures;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** The TerraBlender region snapshot is taken from the root of Lithostitched's injector layers, and only from there. */
class TerraBlenderRegionSourceTest {
    @BeforeAll static void bootstrap() { TerrainPredictionFixtures.bootstrap(); }

    private static final String LITHOSTITCHED_INJECTOR = "dev.worldgen.lithostitched.impl.worldgen.biomeinjector.internal.InjectorBiomeSource";
    private static final String STAND_IN = InjectorBiomeSource.class.getName();

    /**
     * Stand-in with the two accessors of Lithostitched 1.8.1's InjectorBiomeSource that VSS reads by name. It lives under
     * this test's name so that it never shadows the released class that -PvssLithostitchedJar tests load.
     */
    public static final class InjectorBiomeSource extends BiomeSource {
        private final BiomeSource direct;
        private final BiomeSource root;

        public InjectorBiomeSource(BiomeSource direct, BiomeSource root) {
            this.direct = direct;
            this.root = root;
        }

        public BiomeSource directDelegate() { return direct; }

        public BiomeSource rootDelegate() { return root; }

        @Override protected MapCodec<? extends BiomeSource> codec() { throw new UnsupportedOperationException(); }

        @Override protected Stream<Holder<Biome>> collectPossibleBiomes() { return direct.possibleBiomes().stream(); }

        @Override public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler sampler) { return direct.getNoiseBiome(x, y, z, sampler); }
    }

    private static MultiNoiseBiomeSource overworld() {
        var lists = TerrainPredictionFixtures.access().registryOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST);
        return MultiNoiseBiomeSource.createFromPreset(lists.getHolderOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD));
    }

    @Test
    void injectorLayersOverAMultiNoiseRootAreUnwrappedAndAnythingElseIsLeftAsItIs() {
        var root = overworld();
        assertSame(root, TerraBlenderRegionSource.regionSource(root, STAND_IN));
        var inner = new InjectorBiomeSource(root, root);
        var outer = new InjectorBiomeSource(inner, root);
        assertSame(root, TerraBlenderRegionSource.underInjectors(outer, STAND_IN));
        assertSame(root, TerraBlenderRegionSource.regionSource(outer, STAND_IN));
        assertSame(outer, TerraBlenderRegionSource.regionSource(outer), "only Lithostitched's own class is unwrapped in production");
        BiomeSource fixed = new FixedBiomeSource(TerrainPredictionFixtures.access().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS));
        assertSame(fixed, TerraBlenderRegionSource.regionSource(fixed, STAND_IN));
        assertNull(TerraBlenderRegionSource.underInjectors(fixed, STAND_IN));
        var foreignLayer = new InjectorBiomeSource(fixed, root);
        assertNull(TerraBlenderRegionSource.underInjectors(foreignLayer, STAND_IN), "a non-injector layer between the injectors and the root");
        assertSame(foreignLayer, TerraBlenderRegionSource.regionSource(foreignLayer, STAND_IN));
        var fixedRoot = new InjectorBiomeSource(fixed, fixed);
        assertSame(fixedRoot, TerraBlenderRegionSource.regionSource(fixedRoot, STAND_IN), "only a MultiNoiseBiomeSource root carries TerraBlender regions");
    }

    @Test
    @EnabledIfSystemProperty(named = "vss.lithostitchedJar", matches = ".+")
    void lithostitchedsReleasedInjectorIsUnwrappedToItsRoot() throws Exception {
        var root = overworld();
        try (var loader = new URLClassLoader(new URL[]{Path.of(System.getProperty("vss.lithostitchedJar")).toUri().toURL()},
                getClass().getClassLoader())) {
            var type = loader.loadClass(LITHOSTITCHED_INJECTOR);
            var injector = (BiomeSource) type.getConstructor(BiomeSource.class).newInstance(root);
            assertSame(root, TerraBlenderRegionSource.underInjectors(injector));
            assertSame(root, TerraBlenderRegionSource.regionSource(injector));
            var outer = (BiomeSource) type.getConstructor(BiomeSource.class).newInstance(injector);
            assertSame(root, TerraBlenderRegionSource.regionSource(outer));
        }
    }

    @Test
    void unwrappedSnapshotsAreMarkedAndNoSurfaceMarkerWithoutBiolith() {
        var section = new JsonObject();
        var regions = new JsonArray();
        var base = new JsonObject();
        base.addProperty("name", "minecraft:overworld");
        regions.add(base);
        section.add("regions", regions);
        var root = overworld();
        TerraBlenderRegionSource.markUnwrapped(section, root);
        assertFalse(section.has(TerraBlenderRegionSource.SOURCE_MARKER), "a plain MultiNoiseBiomeSource is not unwrapped");
        TerraBlenderRegionSource.markUnwrapped(section, new InjectorBiomeSource(root, root));
        assertEquals(TerraBlenderRegionSource.UNWRAPPED, section.get(TerraBlenderRegionSource.SOURCE_MARKER).getAsString());
        TerraBlenderRegionSource.markSurfaceRoute(section, root);
        assertFalse(section.has(TerraBlenderRegionSource.SURFACE_MARKER), "Biolith is not installed");
    }
}
