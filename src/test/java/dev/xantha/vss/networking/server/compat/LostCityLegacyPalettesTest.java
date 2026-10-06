package dev.xantha.vss.networking.server.compat;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Array;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.*;

class LostCityLegacyPalettesTest {
    @BeforeAll static void bootstrap() {
        if (net.neoforged.fml.loading.FMLPaths.GAMEDIR.get() == null)
            net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(Path.of("build", "tmp", "prediction-tests"));
        net.neoforged.fml.loading.LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
    }
    public record Definition(List<Entry> getPaletteEntries) { }
    public record Entry(String getChr, String getBlock, String getVariant, String getFrompalette, List<Weighted> getBlocks) { }
    public record Weighted(int random, String block) { }

    @TestFactory Stream<DynamicTest> actualLegacyPaletteCompilesBlockAliasAndWeightedMappings() throws Exception {
        String directory = System.getProperty("vss.lostCitiesMatrix");
        Assumptions.assumeTrue(directory != null, "Supply release jars with -PvssLostCitiesMatrix");
        // These NeoForge releases have the legacy cache implementation and use
        // Mojmap names, so their actual material code runs in the NeoForge test
        // runtime. Forge release bytecode uses SRG names: covered by contracts.
        if (Runtime.version().feature() < 21) return Stream.empty();
        List<Path> paths;
        try (var files = Files.list(Path.of(directory))) {
            paths = files.filter(p -> p.getFileName().toString().matches("lostcities-1.21-8\\.[23]\\..*\\.jar")).sorted().toList();
        }
        assertEquals(2, paths.size());
        return paths.stream().map(path -> DynamicTest.dynamicTest(path.getFileName().toString(), () -> {
            try (var loader = new URLClassLoader(new java.net.URL[] {path.toUri().toURL()}, getClass().getClassLoader())) {
                var reader = new LostCityLegacyPalettes(loader, null);
                var read = LostCityLegacyPalettes.class.getDeclaredMethod("previewPalette", Object.class);
                read.setAccessible(true);
                Object palette = read.invoke(reader, new Definition(List.of(
                        new Entry("s", "minecraft:stone", null, null, null),
                        new Entry("a", null, null, "s", null),
                        new Entry("g", null, null, null, List.of(new Weighted(128, "minecraft:glass"))))));
                Class<?> compiled = loader.loadClass("mcjty.lostcities.worldgen.lost.cityassets.CompiledPalette");
                Object palettes = Array.newInstance(palette.getClass(), 1); Array.set(palettes, 0, palette);
                Object result = compiled.getConstructor(palettes.getClass()).newInstance(palettes);
                var get = compiled.getMethod("get", char.class, Random.class);
                assertEquals(Blocks.STONE.defaultBlockState(), get.invoke(result, 's', new Random(0)));
                assertEquals(Blocks.STONE.defaultBlockState(), get.invoke(result, 'a', new Random(0)));
                assertEquals(Blocks.GLASS.defaultBlockState(), get.invoke(result, 'g', new Random(0)));

                // Convert the shipped raw registry part, whose inline palette
                // is a PaletteRE rather than a list or a compiled Palette.
                Class<?> paletteRE = loader.loadClass("mcjty.lostcities.worldgen.lost.regassets.PaletteRE");
                Class<?> entryRE = loader.loadClass("mcjty.lostcities.worldgen.lost.regassets.data.PaletteEntry");
                Object entry = entryRE.getMethod("block", String.class).invoke(null, "minecraft:oak_leaves");
                var chr = entryRE.getDeclaredField("chr"); chr.setAccessible(true); chr.set(entry, "l");
                Object inline = paletteRE.getConstructor(List.class).newInstance(List.of(entry));
                Class<?> partRE = loader.loadClass("mcjty.lostcities.worldgen.lost.regassets.BuildingPartRE");
                var constructor = partRE.getConstructor(int.class, int.class, List.class, Optional.class, Optional.class, Optional.class);
                Object rawPart = constructor.newInstance(2, 1, List.of(List.of("ll")), Optional.empty(), Optional.of(inline), Optional.empty());
                var convert = LostCityLegacyPalettes.class.getDeclaredMethod("previewPart", Object.class); convert.setAccessible(true);
                var preview = (LostCityLegacyPalettes.PreviewPart) convert.invoke(reader, rawPart);
                assertEquals(2, preview.getXSize()); assertEquals(1, preview.getZSize());
                assertArrayEquals(new String[]{"ll"}, preview.getSlices());
                assertSame(preview.local(), reader.local(preview));
                Object localArray = Array.newInstance(palette.getClass(), 1); Array.set(localArray, 0, preview.local());
                Object localCompiled = compiled.getConstructor(localArray.getClass()).newInstance(localArray);
                assertEquals(Blocks.OAK_LEAVES.defaultBlockState(), get.invoke(localCompiled, 'l', new Random(0)));
                String[] original = (String[]) partRE.getMethod("getSlices").invoke(rawPart);
                original[0] = "aa";
                assertEquals("ll", preview.getSlices()[0], "the query owns its copied slices");
                assertSame(inline, partRE.getMethod("getLocalPalette").invoke(rawPart), "conversion leaves the registry part unchanged");
                Object referenced = constructor.newInstance(2, 1, List.of(List.of("ll")), Optional.of("test:park"), Optional.empty(), Optional.empty());
                var reference = (LostCityLegacyPalettes.PreviewPart) convert.invoke(reader, referenced);
                assertNull(reference.local()); assertEquals("test:park", reference.reference());
                var cache = LostCityLegacyPalettes.class.getDeclaredField("palettes"); cache.setAccessible(true);
                @SuppressWarnings("unchecked") Map<String, Object> privatePalettes = (Map<String, Object>) cache.get(reader);
                privatePalettes.put("test:park", preview.local());
                assertSame(preview.local(), reader.local(reference));
                assertNull(reference.local(), "reference resolution never publishes a shared lazy value");
            }
        }));
    }
}
