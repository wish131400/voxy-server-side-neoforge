package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.lang.reflect.Proxy;
import java.util.Random;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "vss.freeTerraForgedJar", matches = ".+")
class FreeTerraForgedNativeFiltersTest {
    @BeforeAll static void bootstrap() {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        String library = System.getProperty("vss.testNativeLibrary");
        if (library != null) RustWorldgenBackend.load(Path.of(library));
        else assertTrue(RustTerrainSampler.available());
    }

    @Test void rejectedBuffersAndSettingsDoNotPartiallyModifyTile() {
        assertFalse(FreeTerraForgedNativeFilters.apply(new Object(), null, true), "unregistered live generators must not enter JNI");
        var settings = com.google.gson.JsonParser.parseString("""
                {"size":16,"border":0,"block_x":0,"block_z":0,"seed":0,"world_height":256,"sea_level":63,
                 "droplets_per_chunk":0,"droplet_lifetime":0,"erosion_rate":0.5,"deposit_rate":0.3,
                 "droplet_velocity":1,"droplet_volume":1,"smoothing_iterations":0,"smoothing_radius":1,
                 "smoothing_rate":0.5,"beach_transition":0.25,"optional":false}
                """).getAsJsonObject();
        var data = java.nio.ByteBuffer.allocateDirect(16 * 16 * 36).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        data.putInt(0, 0x7fc00000);
        assertThrows(IllegalArgumentException.class, () -> RustWorldgenBackend.freeTerraForgedFilters(data, settings.toString()));
        assertEquals(0x7fc00000, data.getInt(0));
        assertThrows(IllegalArgumentException.class, () -> RustWorldgenBackend.freeTerraForgedFilters(data.asReadOnlyBuffer(), settings.toString()));
        assertThrows(IllegalArgumentException.class, () -> RustWorldgenBackend.freeTerraForgedFilters(java.nio.ByteBuffer.allocateDirect(16), settings.toString()));
        assertThrows(IllegalArgumentException.class, () -> RustWorldgenBackend.freeTerraForgedFilters(java.nio.ByteBuffer.allocate(data.capacity()), settings.toString()));
        settings.addProperty("size", -16);
        assertThrows(IllegalArgumentException.class, () -> RustWorldgenBackend.freeTerraForgedFilters(data, settings.toString()));
        assertEquals(0x7fc00000, data.getInt(0));
    }

    @Test void optionalMixinMatchesReleasedTileEntryPoint() throws Exception {
        var target = new org.objectweb.asm.tree.ClassNode();
        try (var jar = new java.util.jar.JarFile(System.getProperty("vss.freeTerraForgedJar"));
             var in = jar.getInputStream(jar.getJarEntry("raccoonman/reterraforged/world/worldgen/WorldFilters.class"))) {
            new org.objectweb.asm.ClassReader(in).accept(target, 0);
        }
        var mixin = new org.objectweb.asm.tree.ClassNode();
        try (var in = getClass().getResourceAsStream("/dev/xantha/vss/mixin/client/FreeTerraForgedPredictionFiltersMixin.class")) {
            new org.objectweb.asm.ClassReader(in).accept(mixin, 0);
        }
        int matches = 0;
        for (var method : mixin.methods) {
            if (method.visibleAnnotations == null) continue;
            for (var a : method.visibleAnnotations) {
                if (!a.desc.endsWith("/Inject;")) continue;
                var values = new java.util.HashMap<String, Object>();
                for (int i = 0; i < a.values.size(); i += 2) values.put((String) a.values.get(i), a.values.get(i + 1));
                var selectors = (java.util.List<?>) values.get("method");
                for (Object selector : selectors) {
                    assertEquals(1, target.methods.stream().filter(m -> selector.equals(m.name + m.desc)).count()); matches++;
                }
                assertEquals(true, values.get("cancellable"));
                assertEquals(false, values.get("remap"));
            }
        }
        assertEquals(1, matches);
    }

    @Test void completePipelineMatchesReleasedTileFilters() throws Throwable {
        {
            var loader = FreeTerraForgedCompatTest.releaseLoader();
            String prefix = "raccoonman.reterraforged.world.worldgen.";
            var cell = loader.loadClass(prefix + "cell.Cell");
            var filterable = loader.loadClass(prefix + "densityfunction.tile.filter.Filterable");
            var modifier = loader.loadClass(prefix + "densityfunction.tile.filter.Modifier");
            var erosionType = loader.loadClass("raccoonman.reterraforged.data.worldgen.preset.settings.FilterSettings$Erosion");
            var smoothingType = loader.loadClass("raccoonman.reterraforged.data.worldgen.preset.settings.FilterSettings$Smoothing");
            var filterSettingsType = loader.loadClass("raccoonman.reterraforged.data.worldgen.preset.settings.FilterSettings");
            var access = new FreeTerraForgedNativeFilters.Access(loader);
            long javaTime = 0, nativeTime = 0;
            for (int side : new int[]{32, 64, 96, 256}) for (int seed : new int[]{0, -918273, Integer.MAX_VALUE}) for (boolean optional : new boolean[]{false, true}) {
                int border = side >= 96 ? 16 : 0;
                var size = loader.loadClass(prefix + "densityfunction.tile.Size").getMethod("make", int.class, int.class).invoke(null, side - 2 * border, border);
                Object[] actual = (Object[]) java.lang.reflect.Array.newInstance(cell, side * side);
                Object[] expected = (Object[]) java.lang.reflect.Array.newInstance(cell, side * side);
                var random = new Random(seed);
                var terrains = loader.loadClass(prefix + "cell.terrain.TerrainType");
                Object[] kinds = {terrains.getField("FLATS").get(null), terrains.getField("MOUNTAINS_1").get(null), terrains.getField("BADLANDS").get(null),
                        terrains.getField("COAST").get(null), terrains.getField("BEACH").get(null), terrains.getField("SHALLOW_OCEAN").get(null), terrains.getField("WETLAND").get(null)};
                for (int i = 0; i < actual.length; i++) {
                    actual[i] = cell.getConstructor().newInstance(); expected[i] = cell.getConstructor().newInstance();
                    float height = 0.12f + random.nextFloat() * 0.6f;
                    float river = random.nextFloat(); float edge = random.nextFloat();
                    for (Object c : new Object[]{actual[i], expected[i]}) {
                        cell.getField("height").setFloat(c, height);
                        cell.getField("riverMask").setFloat(c, river);
                        cell.getField("terrainRegionEdge").setFloat(c, edge);
                        cell.getField("continentEdge").setFloat(c, edge);
                        cell.getField("erosionMask").setBoolean(c, i % 13 == 0);
                        cell.getField("terrain").set(c, kinds[i % kinds.length]);
                    }
                }
                Object actualMap = tile(loader, filterable, cell, actual, size, side);
                Object expectedMap = tile(loader, filterable, cell, expected, size, side);
                Object erosionSettings = erosionType.getConstructor(int.class, int.class, float.class, float.class, float.class, float.class)
                        .newInstance(5, 40, 0.7f, 1.1f, 0.5f, 0.35f);
                Object smoothingSettings = smoothingType.getConstructor(int.class, float.class, float.class).newInstance(2, 1.8f, 0.55f);
                Object settings = filterSettingsType.getConstructor(erosionType, smoothingType).newInstance(erosionSettings, smoothingSettings);
                Object erosionModifier = modifier.getMethod("range", float.class, float.class).invoke(null, 63f / 256, 78f / 256);
                Object smoothingModifier = modifier.getMethod("range", float.class, float.class).invoke(null, 64f / 256, 183f / 256);
                smoothingModifier = modifier.getMethod("invert").invoke(smoothingModifier);
                var erosionClass = loader.loadClass(prefix + "densityfunction.tile.filter.Erosion");
                var levelsClass = loader.loadClass(prefix + "cell.heightmap.Levels");
                Object levels = levelsClass.getConstructor(int.class, int.class).newInstance(256, 63);
                Class<?> controlClass = loader.loadClass("raccoonman.reterraforged.data.worldgen.preset.settings.WorldSettings$ControlPoints");
                Object preset = loader.loadClass("raccoonman.reterraforged.data.worldgen.preset.settings.Presets").getMethod("makeRTFDefault").invoke(null);
                Object worldSettings = preset.getClass().getMethod("world").invoke(preset);
                Object control = worldSettings.getClass().getField("controlPoints").get(worldSettings);
                long start = System.nanoTime();
                if (optional) {
                Object erosion = erosionClass.getConstructor(int.class, int.class, erosionType, modifier).newInstance(seed + 12768, side, erosionSettings, erosionModifier);
                erosionClass.getMethod("apply", filterable, int.class, int.class, int.class).invoke(erosion, expectedMap, 0, 0, 5);
                var smoothingClass = loader.loadClass(prefix + "densityfunction.tile.filter.Smoothing");
                Object smoothing = smoothingClass.getConstructor(float.class, float.class, modifier).newInstance(1.8f, 0.55f, smoothingModifier);
                smoothingClass.getMethod("apply", filterable, int.class, int.class, int.class).invoke(smoothing, expectedMap, 0, 0, 2);
                }
                var steepnessClass = loader.loadClass(prefix + "densityfunction.tile.filter.Steepness");
                Object steepness = steepnessClass.getConstructor(int.class, float.class, float.class).newInstance(1, 10f, 62f / 256);
                steepnessClass.getMethod("apply", filterable, int.class, int.class, int.class).invoke(steepness, expectedMap, 0, 0, 1);
                var beachClass = loader.loadClass(prefix + "densityfunction.tile.filter.BeachDetect");
                Object beach = beachClass.getConstructor(levelsClass, controlClass).newInstance(levels, control);
                beachClass.getMethod("apply", filterable, int.class, int.class, int.class).invoke(beach, expectedMap, 0, 0, 1);
                if (optional) {
                    var correctionClass = loader.loadClass(prefix + "densityfunction.tile.filter.NoiseCorrection");
                    Object correction = correctionClass.getConstructor(levelsClass).newInstance(levels);
                    correctionClass.getMethod("apply", filterable, int.class, int.class, int.class).invoke(correction, expectedMap, 0, 0, 1);
                }
                javaTime += System.nanoTime() - start;
                var options = FreeTerraForgedNativeFilters.options(settings, seed, 256, 63);
                options.addProperty("optional", optional);
                options.addProperty("beach_transition", controlClass.getField("beach").getFloat(control));
                start = System.nanoTime(); access.apply(actualMap, options); nativeTime += System.nanoTime() - start;
                for (int i = 0; i < actual.length; i++) for (String name : new String[]{"height", "sediment", "heightErosion", "gradient"}) {
                    var field = cell.getField(name);
                    assertEquals(field.getFloat(expected[i]), field.getFloat(actual[i]), 0.0f, "side=" + side + " seed=" + seed + " cell=" + i + " field=" + name);
                }
                for (int i = 0; i < actual.length; i++) assertSame(cell.getField("terrain").get(expected[i]), cell.getField("terrain").get(actual[i]), "terrain cell=" + i);
            }
            System.out.println("FTF filters incl bridge: Java ms=" + javaTime / 1e6 + ", Rust ms=" + nativeTime / 1e6);
        }
    }

    private static Object tile(ClassLoader loader, Class<?> filterable, Class<?> cell, Object[] cells, Object size, int side) throws Exception {
        String prefix = "raccoonman.reterraforged.";
        Class<?> tile = loader.loadClass(prefix + "world.worldgen.densityfunction.tile.Tile");
        Class<?> resource = loader.loadClass(prefix + "concurrent.Resource");
        Object chunks = java.lang.reflect.Array.newInstance(loader.loadClass(tile.getName() + "$Chunk"), 1);
        Object chunkSize = size.getClass().getMethod("make", int.class, int.class).invoke(null, 1, 0);
        java.util.function.Function<Object, Object> wrap = data -> Proxy.newProxyInstance(loader, new Class<?>[]{resource}, (p, m, a) -> switch (m.getName()) {
            case "get" -> data;
            case "isOpen" -> true;
            case "close" -> null;
            default -> throw new UnsupportedOperationException(m.toString());
        });
        return tile.getConstructor(int.class, int.class, int.class, int.class, size.getClass(), size.getClass(), resource, resource)
                .newInstance(-128, 64, 0, 0, size, chunkSize, wrap.apply(cells), wrap.apply(chunks));
    }
}
