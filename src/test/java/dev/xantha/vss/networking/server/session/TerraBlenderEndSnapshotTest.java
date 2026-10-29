package dev.xantha.vss.networking.server.session;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import com.google.gson.JsonObject;
import dev.xantha.vss.common.worldgen.TerraBlenderEndRouting;
import dev.xantha.vss.testutil.TerrainPredictionFixtures;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biomes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TerraBlenderEndSnapshotTest {
    @BeforeAll static void bootstrap() { TerrainPredictionFixtures.bootstrap(); }
    private static <T> String biomeName(Registry<T> registry, int id) {
        return registry.getKey(registry.byId(id)).toString();
    }
    @Test void actualInitializedAreaChainMatchesItsCapturedReplay(@TempDir Path temporaryDirectory) throws Exception {
        String jar = System.getProperty("vss.terrablenderJar", "");
        assumeTrue(!jar.isBlank(), "Pass a version-matching Mojmap TerraBlender JAR");
        try (var loader = new URLClassLoader(new java.net.URL[]{Path.of(jar).toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> endRegistry;
            try { endRegistry = loader.loadClass("terrablender.api.EndBiomeRegistry"); }
            catch (ClassNotFoundException absentOn120) { assumeTrue(false, "This release has no End biome API"); return; }
            // Use the real released config parser and defaults. EndBiomeRegistry's
            // static initializer consumes CONFIG, exactly after loader setup in-game.
            Class<?> configType = loader.loadClass("terrablender.config.TerraBlenderConfig");
            Path configFile = temporaryDirectory.resolve("terrablender.toml");
            Object config = configType.getConstructor(Path.class).newInstance(configFile);
            loader.loadClass("terrablender.core.TerraBlender").getMethod("setConfig", configType)
                    .invoke(null, config);
            assertTrue(java.nio.file.Files.isRegularFile(configFile), "real config must stay in the isolated fixture");
            var register = endRegistry.getMethod("registerHighlandsBiome", ResourceKey.class, int.class);
            register.invoke(null, Biomes.PLAINS, 7); register.invoke(null, Biomes.DESERT, 3);
            register.invoke(null, Biomes.PLAINS, 5); // repeated biome IDs participate in zoom mode equality.
            List<?> entries = (List<?>) endRegistry.getMethod("getHighlandsBiomes").invoke(null);
            var areaFactory = loader.loadClass("terrablender.worldgen.noise.LayeredNoiseUtil")
                    .getMethod("biomeArea", RegistryAccess.class, long.class, int.class, List.class);
            var access = TerrainPredictionFixtures.access();
            Registry<?> biomes = access.registries().filter(entry -> entry.key().equals(Registries.BIOME))
                    .map(entry -> entry.value()).findFirst().orElseThrow();
            for (long seed : new long[]{0, 42, -9325173, Long.MAX_VALUE}) for (int size : new int[]{0, 2, 4}) {
                Object server = areaFactory.invoke(null, access, seed, size, entries);
                var captured = TerraBlenderEndSnapshot.captureArea(server, seed, size + 1);
                assertEquals(size, captured.get("size").getAsInt(), "actual stack wins over changed config");
                JsonObject root = new JsonObject(); root.addProperty("revision", 1); root.addProperty("seed", seed);
                root.addProperty("density_coordinates", "block");
                for (String area : new String[]{"highlands", "midlands", "edge", "islands"}) root.add(area, captured.deepCopy());
                var replay = new TerraBlenderEndRouting(root, seed);
                var get = server.getClass().getMethod("get", int.class, int.class);
                for (int i = 0; i < 2048; i++) {
                    int x = i * 1877 % 16384 - 8192, z = i * 547 % 32768 - 16384;
                    if (TerraBlenderEndRouting.central(x, z)) continue;
                    int id = (Integer) get.invoke(server, x, z);
                    String expected = biomeName(biomes, id);
                    assertEquals(expected, replay.sample(x, z, 1), "seed=" + seed + " size=" + size + " @" + x + "," + z);
                }
                assertThrows(IllegalStateException.class, () -> TerraBlenderEndSnapshot.captureArea(server, seed + 1, size));
            }
        }
    }
    private static net.minecraft.world.level.biome.BiomeSource source() {
        return new net.minecraft.world.level.biome.FixedBiomeSource(TerrainPredictionFixtures.access().registryOrThrow(Registries.BIOME).getHolderOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS));
    }
    @Test void absentTerraBlenderEndContractLeavesOrdinarySnapshotUntouched() {
        assertNull(TerraBlenderEndSnapshot.capture(source(), 42));
        var root = new JsonObject(); root.add(dev.xantha.vss.common.worldgen.TerrainPredictionPlan.FIELD, dev.xantha.vss.common.worldgen.TerrainPredictionPlan.noise("vss:noise_graph", 1, true).encode());
        byte[] bytes = root.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var original = new WorldgenCodecSnapshot.Encoded(0, bytes.length, bytes);
        assertSame(original, WorldgenCodecSnapshot.withRuntimeBiomeSource(original, source(), 42));
    }
    @Test void explicitProviderContractKeepsItsOwnBiomeProjection() {
        var root = new JsonObject(); root.add(dev.xantha.vss.common.worldgen.TerrainPredictionPlan.FIELD, dev.xantha.vss.common.worldgen.TerrainPredictionPlan.noise("example:projection", 2, true).encode());
        byte[] bytes = root.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var original = new WorldgenCodecSnapshot.Encoded(0, bytes.length, bytes);
        assertSame(original, WorldgenCodecSnapshot.withRuntimeBiomeSource(original, source(), 42));
    }
}
