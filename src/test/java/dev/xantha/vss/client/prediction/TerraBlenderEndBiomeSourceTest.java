package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.xantha.vss.testutil.TerrainPredictionFixtures;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TerraBlenderEndBiomeSourceTest {
    @BeforeAll static void bootstrap() { TerrainPredictionFixtures.bootstrap(); }
    static JsonObject snapshot() {
        var root = new JsonObject(); root.addProperty("revision", 1); root.addProperty("seed", 42);
        root.addProperty("density_coordinates", "block");
        String[] names = {"highlands", "midlands", "edge", "islands"};
        String[] ids = {"minecraft:end_highlands", "minecraft:end_midlands", "minecraft:end_barrens", "minecraft:small_end_islands"};
        for (int i = 0; i < names.length; i++) {
            var area = new JsonObject(); area.addProperty("size", 2); area.addProperty("default_biome", "minecraft:ocean");
            var entry = new JsonObject(); entry.addProperty("biome", ids[i]); entry.addProperty("weight", 7);
            var entries = new JsonArray(); entries.add(entry); area.add("biomes", entries); root.add(names[i], area);
        }
        return root;
    }
    @SuppressWarnings("unchecked")
    static Holder<Biome> sample(BiomeSource source, int x, int y, int z, Climate.Sampler sampler) throws Exception {
        try {
            Method method = BiomeSource.class.getMethod("getNoiseBiome", int.class, int.class, int.class, Climate.Sampler.class);
            return (Holder<Biome>) method.invoke(source, x, y, z, sampler);
        } catch (NoSuchMethodException modernResolver) {
            Object resolver = BiomeSource.class.getMethod("createResolver", Climate.Sampler.class).invoke(source, sampler);
            return (Holder<Biome>) Class.forName("net.minecraft.world.level.biome.BiomeResolver")
                    .getMethod("getNoiseBiome", int.class, int.class, int.class).invoke(resolver, x, y, z);
        }
    }
    @Test void adapterSamplesExactBlockCoordinatesAndPreservesCenter() throws Exception {
        var registries = ClientWorldgenRegistries.decode(TerrainPredictionFixtures.emptyRegistries(), TerrainPredictionFixtures.access());
        var source = new TerraBlenderEndBiomeSource(snapshot(), 42, registries);
        AtomicReference<int[]> point = new AtomicReference<>();
        Object erosion = mock(Climate.Sampler.class.getMethod("erosion").getReturnType(), invocation -> {
            if (invocation.getMethod().getName().equals("compute")) {
                Object context = invocation.getArgument(0);
                Class<?> contract = invocation.getMethod().getParameterTypes()[0];
                point.set(new int[]{(Integer) contract.getMethod("blockX").invoke(context),
                        (Integer) contract.getMethod("blockY").invoke(context), (Integer) contract.getMethod("blockZ").invoke(context)});
                return .5;
            }
            if (invocation.getMethod().getName().equals("sampleValue")) {
                point.set(new int[]{invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)});
                if (invocation.getMethod().getReturnType() == float.class) return .5f;
                return .5;
            }
            return RETURNS_DEFAULTS.answer(invocation);
        });
        var sampler = mock(Climate.Sampler.class, invocation -> invocation.getMethod().getName().equals("erosion")
                ? erosion : RETURNS_DEFAULTS.answer(invocation));
        assertEquals("minecraft:the_end", sample(source, 0, 10, 0, sampler).unwrapKey().orElseThrow().location().toString());
        assertNull(point.get(), "center must not evaluate erosion");
        assertEquals("minecraft:end_highlands", sample(source, 301, -17, -47, sampler).unwrapKey().orElseThrow().location().toString());
        assertArrayEquals(new int[]{1204, -68, -188}, point.get(), "TerraBlender uses exact block coordinates instead of chunk centers");
    }
    @Test void missingBiomeHolderRejectsTheRequiredAdapter() {
        var registries = ClientWorldgenRegistries.decode(TerrainPredictionFixtures.emptyRegistries(), TerrainPredictionFixtures.access());
        var root = snapshot(); root.getAsJsonObject("edge").getAsJsonArray("biomes").get(0).getAsJsonObject().addProperty("biome", "test:missing");
        assertThrows(RuntimeException.class, () -> new TerraBlenderEndBiomeSource(root, 42, registries));
    }
}
