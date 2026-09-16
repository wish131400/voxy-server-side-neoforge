package dev.xantha.vss.client.prediction;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BlueprintBiomeCompatTest {
    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="vss.liveSnapshot", matches=".+")
    void capturedWrappedRegionsResolveTheReportedStoneShore() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        var dir = java.nio.file.Path.of(System.getProperty("vss.liveSnapshot"));
        var doc = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(dir.resolve("stone-after.json"))).getAsJsonObject();
        long seed = Long.parseLong(java.nio.file.Files.readString(dir.resolve("stone-seed.txt")).trim());
        var key = net.minecraft.core.registries.Registries.BIOME;
        var registry = new net.minecraft.core.MappedRegistry<net.minecraft.world.level.biome.Biome>(key, com.mojang.serialization.Lifecycle.stable());
        var plains = net.minecraft.data.registries.VanillaRegistries.createLookup().lookupOrThrow(key)
                .getOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS).value();
        // Routing depends on holder identities and climate ranges, not biome
        // features. Stub values let the captured modded ids run without mods.
        for (String id : doc.getAsJsonObject("biomes").keySet()) registry.register(
                net.minecraft.resources.ResourceKey.create(key, net.minecraft.resources.ResourceLocation.parse(id)),
                plains, net.minecraft.core.RegistrationInfo.BUILT_IN);
        var access = new net.minecraft.core.RegistryAccess.ImmutableRegistryAccess(java.util.List.of(registry.freeze()));
        var empty = com.google.gson.JsonParser.parseString("{\"noises\":{},\"density_functions\":{}}").getAsJsonObject();
        var registries = ClientWorldgenRegistries.decode(empty, access, java.util.List.of());
        var section = doc.getAsJsonObject("vss_blueprint");
        var base = net.minecraft.world.level.biome.BiomeSource.CODEC.parse(registries.ops(), section.get("original_biome_source")).getOrThrow();
        var routed = TerraBlenderBackend.buildRoutingSource(section.getAsJsonObject("original_terrablender"), base, seed, registries);
        int[][] points = {{832,100,-624,4400,-1436,-1441,-5000,-4,-10000},
                {880,70,-560,4485,-1016,-1573,-5000,75,-10000},
                {800,94,-592,4385,-1255,-1490,-5000,110,-10000},
                {864,87,-592,4528,-1030,-1509,-5000,191,-10000}};
        for (int[] p : points) {
            var f = new net.minecraft.world.level.levelgen.DensityFunction[6];
            for (int i=0;i<6;i++) f[i] = net.minecraft.world.level.levelgen.DensityFunctions.constant(p[i+3] / 10000.0);
            var climate = new net.minecraft.world.level.biome.Climate.Sampler(f[0],f[1],f[2],f[3],f[4],f[5],java.util.List.of());
            assertEquals("terralith:basalt_cliffs", base.getNoiseBiome(p[0]>>2,p[1]>>2,p[2]>>2,climate).unwrapKey().orElseThrow().location().toString());
            assertEquals("minecraft:stony_shore", routed.getNoiseBiome(p[0]>>2,p[1]>>2,p[2]>>2,climate).unwrapKey().orElseThrow().location().toString());
        }
    }

    @Test void preservesCapturedDimensionSeedsIncludingLongOverflow() {
        for (long seed : new long[]{0, 42, Long.MIN_VALUE, Long.MAX_VALUE}) {
            for (long modifier : new long[]{0, -123456789L, Long.MAX_VALUE}) {
                assertEquals(modifier, BlueprintBiomeCompat.dimensionModifier(seed,
                        seed + 1791510900L + modifier, seed - 771160217L + modifier));
            }
        }
        assertDoesNotThrow(() -> BlueprintBiomeCompat.dimensionModifier(0,
                2519338626306849043L, 2519338623744177926L));
    }

    @Test void rejectsInconsistentRoutingSeedsInsteadOfChangingSliceLocations() {
        assertThrows(IllegalArgumentException.class,
                () -> BlueprintBiomeCompat.dimensionModifier(42, 1791510942L, 0));
    }
}
