package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.Lifecycle;
import dev.xantha.vss.testutil.TerrainPredictionFixtures;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * ReTerraForged's TerraBlender region rule in VSS's routing replay. With -PvssReTerraForgedJar the reproduced pieces are
 * compared with RTF's own compiled code (NoiseUtil.round, MixinParameterList's underground roll and override).
 */
class RtfTerraBlenderRegionsTest {
    @BeforeAll static void bootstrap() { TerrainPredictionFixtures.bootstrap(); }

    private static RtfTerraBlenderRegions.Rule rule(int maxIndex, boolean compat, int darker, int deeper, String mode, boolean surface) {
        boolean direct = "AUTO".equals(mode) && (darker >= 0 && darker <= maxIndex || deeper >= 0 && deeper <= maxIndex);
        return new RtfTerraBlenderRegions.Rule(maxIndex, compat, darker, deeper, mode, direct, surface);
    }

    @Test
    void surfaceUsesTheRegionValueUndergroundKeepsTerraBlenderAndBiolithBypassesTheSurfaceRedirect() {
        var plain = rule(7, false, -1, -1, "AUTO", false);
        assertEquals(RtfTerraBlenderRegions.round(7 * 0.43F), RtfTerraBlenderRegions.select(plain, 0.43, 0.0F, 3, 20, 5));
        assertEquals(RtfTerraBlenderRegions.KEEP, RtfTerraBlenderRegions.select(plain, 0.43, 0.2F, 3, 20, 5), "depth >= 0.2 is underground");
        assertEquals(RtfTerraBlenderRegions.KEEP, RtfTerraBlenderRegions.select(plain, Double.NaN, 0.0F, 3, 20, 5), "no region value");
        var compat = rule(7, true, 3, -1, "AUTO", false);
        // Quart 12 is block 48, the first block above RTF's cave compatibility band.
        assertEquals(RtfTerraBlenderRegions.round(7 * 0.43F), RtfTerraBlenderRegions.select(compat, 0.43, 0.0F, 3, 12, 5));
        assertTrue(RtfTerraBlenderRegions.needsTarget(compat, 12));
        assertFalse(RtfTerraBlenderRegions.needsTarget(compat, 11));
        var biolith = rule(7, false, -1, -1, "AUTO", true);
        assertEquals(RtfTerraBlenderRegions.KEEP, RtfTerraBlenderRegions.select(biolith, 0.43, 0.0F, 3, 20, 5));
        assertFalse(RtfTerraBlenderRegions.needsTarget(biolith, 20));
    }

    @Test
    void undergroundOverrideRollsIntoTheCompatibilityRegionsBelowY48Only() {
        var both = rule(9, true, 4, 6, "AUTO", false);
        int darker = 0, deeper = 0, kept = 0;
        for (int x = -400; x < 400; x += 16) for (int z = -400; z < 400; z += 16) {
            int roll = RtfTerraBlenderRegions.roll(x, 2, z);
            int index = RtfTerraBlenderRegions.override(both, x, 2, z);
            if (roll >= 4) { assertEquals(RtfTerraBlenderRegions.KEEP, index); kept++; }
            else if ((roll & 1) == 0) { assertEquals(4, index); darker++; }
            else { assertEquals(6, index); deeper++; }
            assertEquals(RtfTerraBlenderRegions.KEEP, RtfTerraBlenderRegions.override(both, x, 12, z));
        }
        assertTrue(darker > 0 && deeper > 0 && kept > darker + deeper);
        assertEquals(RtfTerraBlenderRegions.KEEP, RtfTerraBlenderRegions.override(rule(9, true, 4, 6, "VANILLA", false), 0, 2, 0),
                "only the AUTO cave compatibility mode overrides");
    }

    private static JsonObject region(String name, boolean base, String biome) {
        var entry = new JsonObject();
        entry.addProperty("name", name);
        entry.addProperty("weight", 10);
        entry.addProperty("base", base);
        entry.addProperty("weighted", true);
        if (biome != null) {
            var point = new JsonArray();
            for (float value : new float[]{-2, 2, -2, 2, -2, 2, -2, 2, -2, 2, -2, 2, 0}) point.add(value);
            var points = new JsonArray();
            points.add(point);
            var group = new JsonObject();
            group.addProperty("biome", biome);
            group.add("points", points);
            var groups = new JsonArray();
            groups.add(group);
            entry.add("groups", groups);
        }
        return entry;
    }

    @Test
    void boundRoutingSourceTakesTheCompatibilityRegionWhereTheRuleSelectsItAndVssRoutingElsewhere() throws Exception {
        var section = new JsonObject();
        section.addProperty("region_size", 3);
        var regions = new JsonArray();
        regions.add(region("minecraft:overworld", true, null));
        regions.add(region("darkerdepths:overworld", false, "minecraft:deep_dark"));
        regions.add(region("example:overworld", false, "minecraft:desert"));
        section.add("regions", regions);
        var access = TerrainPredictionFixtures.access();
        var worldgen = ClientWorldgenRegistries.decode(com.google.gson.JsonParser.parseString("{\"noises\":{},\"density_functions\":{}}").getAsJsonObject(), access, List.of());
        BiomeSource base = new FixedBiomeSource(access.registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS));
        BiomeSource unbound = TerraBlenderBackend.buildRoutingSource(section, base, 42L, worldgen);
        BiomeSource bound = TerraBlenderBackend.buildRoutingSource(section, base, 42L, worldgen);
        var rule = RtfTerraBlenderRegions.bind(section, access, id -> id.equals("darkerdepths"));
        assertEquals(new RtfTerraBlenderRegions.Rule(2, true, 1, -1, "AUTO", true, false), rule);
        Field field = bound.getClass().getDeclaredField("rtfRegions");
        field.setAccessible(true);
        field.set(bound, rule);
        var zero = DensityFunctions.zero();
        var sampler = new Climate.Sampler(zero, zero, zero, zero, zero, zero, List.of());
        int overridden = 0;
        for (int x = -64; x < 64; x += 4) for (int z = -64; z < 64; z += 4) {
            int roll = RtfTerraBlenderRegions.roll(x, 2, z);
            Holder<net.minecraft.world.level.biome.Biome> deep = bound.getNoiseBiome(x, 2, z, sampler);
            if (roll < 4) {
                assertTrue(deep.is(Biomes.DEEP_DARK), "roll " + roll + " at " + x + "," + z);
                overridden++;
            } else {
                assertEquals(unbound.getNoiseBiome(x, 2, z, sampler), deep);
            }
            assertEquals(unbound.getNoiseBiome(x, 20, z, sampler), bound.getNoiseBiome(x, 20, z, sampler),
                    "above Y 48 without a region value RTF keeps TerraBlender's index");
        }
        assertTrue(overridden > 0);
    }

    @Test
    void bindReadsTheCompatibilityModeOfTheSnapshotsRtfPresetAndTheSurfaceMarker() {
        var section = new JsonObject();
        var regions = new JsonArray();
        regions.add(region("minecraft:overworld", true, null));
        regions.add(region("deeperdarker:overworld", false, "minecraft:deep_dark"));
        section.add("regions", regions);
        section.addProperty("vss_surface_route", "terrablender");
        var rule = RtfTerraBlenderRegions.bind(section, RegistryAccess.EMPTY, id -> id.equals("deeperdarker"));
        assertEquals("AUTO", rule.mode(), "no RTF preset: AUTO");
        assertEquals(1, rule.deeperDarker());
        assertTrue(rule.direct());
        assertTrue(rule.terrablenderSurface());
        var none = RtfTerraBlenderRegions.bind(section, RegistryAccess.EMPTY, id -> false);
        assertEquals(-1, none.deeperDarker(), "the compatibility regions count only with their mod installed");
        assertFalse(none.direct());
        section.addProperty("vss_surface_route", "biolith");
        assertThrows(IllegalArgumentException.class, () -> RtfTerraBlenderRegions.bind(section, RegistryAccess.EMPTY, id -> false));
    }

    // ---- against ReTerraForged's compiled code ----

    @Test
    @EnabledIfSystemProperty(named = "vss.reTerraForgedJar", matches = ".+")
    @EnabledIfSystemProperty(named = "vss.terraBlenderJar", matches = ".+")
    void reproducedPiecesEqualReTerraForgedsOwnCode() throws Exception {
        try (var loader = new URLClassLoader(new URL[]{Path.of(System.getProperty("vss.reTerraForgedJar")).toUri().toURL(),
                Path.of(System.getProperty("vss.terraBlenderJar")).toUri().toURL()}, getClass().getClassLoader())) {
            Method round = loader.loadClass("raccoonman.reterraforged.world.worldgen.noise.NoiseUtil").getMethod("round", float.class);
            for (float value = -40.0F; value <= 40.0F; value += 0.125F)
                assertEquals((int) round.invoke(null, value), RtfTerraBlenderRegions.round(value), "round " + value);

            Class<?> list = loader.loadClass("raccoonman.reterraforged.mixin.terrablender.MixinParameterList");
            Method roll = list.getDeclaredMethod("undergroundCompatRoll", int.class, int.class, int.class);
            roll.setAccessible(true);
            var constructor = list.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object rtf = constructor.newInstance();
            Method select = list.getDeclaredMethod("selectUndergroundCompatRegion", int.class, int.class, int.class, int.class);
            select.setAccessible(true);
            Class<?> modes = loader.loadClass("raccoonman.reterraforged.data.worldgen.preset.settings.CaveSettings$CompatibilityMode");
            for (String mode : new String[]{"AUTO", "VANILLA"}) for (int[] indices : new int[][]{{4, 6}, {4, -1}, {-1, 6}, {-1, -1}}) {
                set(list, rtf, "maxIndex", 9);
                set(list, rtf, "darkerDepthsIndex", indices[0]);
                set(list, rtf, "deeperDarkerIndex", indices[1]);
                set(list, rtf, "caveCompatibilityMode", modes.getMethod("valueOf", String.class).invoke(null, mode));
                var port = rule(9, true, indices[0], indices[1], mode, false);
                for (int x = -300; x < 300; x += 23) for (int y = -16; y < 20; y += 3) for (int z = -300; z < 300; z += 29) {
                    assertEquals((int) roll.invoke(null, x, y, z), RtfTerraBlenderRegions.roll(x, y, z));
                    int original = 1;
                    int expected = (int) select.invoke(rtf, original, x, y, z);
                    int port_ = RtfTerraBlenderRegions.override(port, x, y, z);
                    assertEquals(expected, port_ == RtfTerraBlenderRegions.KEEP ? original : port_, mode + " " + x + "," + y + "," + z);
                }
            }

            Object preset = loader.loadClass("raccoonman.reterraforged.data.worldgen.preset.settings.Presets").getMethod("makeRTFDefault").invoke(null);
            Object caves = preset.getClass().getMethod("caves").invoke(preset);
            caves.getClass().getField("compatibilityMode").set(caves, modes.getMethod("valueOf", String.class).invoke(null, "VANILLA"));
            var presets = new MappedRegistry<Object>(LegacyRtfRuntime.PRESETS, Lifecycle.stable());
            presets.register(LegacyRtfRuntime.PRESET, preset, RegistrationInfo.BUILT_IN);
            presets.freeze();
            var section = new JsonObject();
            var regions = new JsonArray();
            regions.add(region("minecraft:overworld", true, null));
            regions.add(region("darkerdepths:overworld", false, "minecraft:deep_dark"));
            section.add("regions", regions);
            var bound = RtfTerraBlenderRegions.bind(section, new RegistryAccess.ImmutableRegistryAccess(List.of(presets)), id -> true);
            assertEquals("VANILLA", bound.mode(), "the preset's caves.compatibilityMode travels with the snapshot");
            assertFalse(bound.direct());
        }
    }

    private static void set(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
