package dev.xantha.vss.api.column;

import static org.junit.jupiter.api.Assertions.*;

import java.io.StringWriter;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import javax.tools.ToolProvider;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ColumnFilterApiCompatibilityTest {
    @TempDir Path directory;

    @Test void oldCompiledCallerLinksToCurrentConstructorAndAccessors() throws Exception {
        Path sources = Files.createDirectories(directory.resolve("old-api/dev/xantha/vss/api/column"));
        Path oldApi = sources.resolve("ColumnInput.java");
        Files.writeString(oldApi, """
                package dev.xantha.vss.api.column;
                import java.util.List;
                import net.minecraft.resources.ResourceKey;
                import net.minecraft.world.level.Level;
                public record ColumnInput(ResourceKey<Level> dimension, int chunkX, int chunkZ,
                        int minSectionY, int maxSectionY, List<Section> sections) {
                    public record Section(int sectionY, boolean onlyAir, byte[] blockLight, byte[] skyLight) {
                        public boolean hasBlockLight() { return blockLight != null; }
                        public boolean hasSkyLight() { return skyLight != null; }
                    }
                }
                """);
        // Compile against the old descriptors without relying on the current API
        // or a platform-specific Minecraft classpath. The real types win at runtime.
        Path key = directory.resolve("old-api/net/minecraft/resources/ResourceKey.java");
        Path level = directory.resolve("old-api/net/minecraft/world/level/Level.java");
        Files.createDirectories(key.getParent()); Files.createDirectories(level.getParent());
        Files.writeString(key, "package net.minecraft.resources; public final class ResourceKey<T> { }");
        Files.writeString(level, "package net.minecraft.world.level; public abstract class Level { }");
        Path oldCaller = directory.resolve("old-api/LegacyColumnFilter.java");
        Files.writeString(oldCaller, """
                import dev.xantha.vss.api.column.ColumnInput;
                public final class LegacyColumnFilter {
                    public static ColumnInput createColumn(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
                            ColumnInput.Section section) {
                        return new ColumnInput(dimension, 3, -5, -2, -1, java.util.List.of(section));
                    }
                    public static boolean readColumn(ColumnInput column) {
                        return column.dimension() != null && column.chunkX() == 3 && column.chunkZ() == -5
                                && column.minSectionY() == -2 && column.maxSectionY() == -1
                                && column.sections().size() == 1 && read(column.sections().get(0));
                    }
                    public static ColumnInput.Section create(byte[] light) {
                        return new ColumnInput.Section(-2, false, light, null);
                    }
                    public static boolean read(ColumnInput.Section section) {
                        return section.sectionY() == -2 && !section.onlyAir()
                                && section.blockLight()[0] == 7 && section.skyLight() == null
                                && section.hasBlockLight() && !section.hasSkyLight();
                    }
                }
                """);
        Path classes = Files.createDirectories(directory.resolve("classes"));
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "The build's JDK must provide javac for the binary compatibility fixture");
        var diagnostics = new StringWriter();
        try (var files = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            assertTrue(compiler.getTask(diagnostics, files, null,
                    List.of("--release", "17", "-proc:none", "-classpath", directory.resolve("old-api").toString(),
                            "-d", classes.toString()), null,
                    files.getJavaFileObjects(oldApi.toFile(), oldCaller.toFile(), key.toFile(), level.toFile())).call(), diagnostics.toString());
        }
        // Parent-first loading resolves the old caller's descriptors to today's API,
        // rather than loading the old API classes used only to compile the fixture.
        try (var loader = new URLClassLoader(new java.net.URL[] {classes.toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> caller = loader.loadClass("LegacyColumnFilter");
            byte[] light = new byte[2048]; light[0] = 7;
            var section = (ColumnInput.Section) caller.getMethod("create", byte[].class).invoke(null, (Object) light);
            light[0] = 42;
            assertSame(ColumnInput.Section.class, section.getClass());
            assertEquals(Integer.MIN_VALUE, section.maxBlockY());
            assertEquals(Boolean.TRUE, caller.getMethod("read", ColumnInput.Section.class).invoke(null, section));
            dev.xantha.vss.testutil.MinecraftTestBootstrap.initialize();
            var column = (ColumnInput) caller.getMethod("createColumn", net.minecraft.resources.ResourceKey.class,
                    ColumnInput.Section.class).invoke(null, Level.OVERWORLD, section);
            assertSame(ColumnInput.class, column.getClass());
            assertEquals(Boolean.TRUE, caller.getMethod("readColumn", ColumnInput.class).invoke(null, column));
            assertFalse(column.hasTopSolidY());
            assertTrue(Arrays.stream(column.topSolidY()).allMatch(y -> y == Integer.MIN_VALUE));
        }
    }

    @Test void newHeightMetadataPreservesLightOwnershipAndLegacyUnknownState() {
        byte[] blockLight = new byte[2048]; blockLight[0] = 7;
        byte[] skyLight = new byte[2048]; skyLight[0] = 12;
        var section = new ColumnInput.Section(-2, false, -17, blockLight, skyLight);
        blockLight[0] = 42; skyLight[0] = 42;
        section.blockLight()[0] = 99; section.skyLight()[0] = 99;
        assertEquals(-17, section.maxBlockY());
        assertEquals(7, section.blockLight()[0]); assertEquals(12, section.skyLight()[0]);
        assertFalse(section.onlyAir()); assertTrue(section.hasBlockLight()); assertTrue(section.hasSkyLight());
        var unknown = new ColumnInput.Section(-2, false, null, null);
        var air = new ColumnInput.Section(-2, true, null, null);
        assertEquals(Integer.MIN_VALUE, unknown.maxBlockY()); assertFalse(unknown.onlyAir());
        assertEquals(Integer.MIN_VALUE, air.maxBlockY()); assertTrue(air.onlyAir());
        assertThrows(IllegalArgumentException.class, () -> new ColumnInput.Section(0, false, 15, new byte[1], null));
    }

    @Test void horizontalHeightMetadataIsImmutableAndHasAllocationFreeAccess() {
        dev.xantha.vss.testutil.MinecraftTestBootstrap.initialize();
        int[] heights = new int[256]; Arrays.fill(heights, Integer.MIN_VALUE);
        heights[(9 << 4) | 3] = -17;
        var column = new ColumnInput(Level.OVERWORLD, 0, 0, -2, -1, heights,
                List.of(new ColumnInput.Section(-2, false, -17, null, null)));
        heights[(9 << 4) | 3] = -32;
        column.topSolidY()[(9 << 4) | 3] = -31;
        assertTrue(column.hasTopSolidY());
        assertEquals(-17, column.topSolidY(3, 9));
        assertEquals(-17, column.topSolidY()[(9 << 4) | 3]);
        assertEquals(Integer.MIN_VALUE, column.topSolidY(9, 3));
        assertThrows(UnsupportedOperationException.class, () -> column.sections().clear());
        for (int coordinate : new int[] {-1, 16, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            assertThrows(IndexOutOfBoundsException.class, () -> column.topSolidY(coordinate, 0));
            assertThrows(IndexOutOfBoundsException.class, () -> column.topSolidY(0, coordinate));
        }
    }

    @Test void availableEmptyHeightsDifferFromUnknownLegacyMetadata() {
        dev.xantha.vss.testutil.MinecraftTestBootstrap.initialize();
        var sections = List.of(new ColumnInput.Section(0, true, null, null));
        int[] air = new int[256]; Arrays.fill(air, Integer.MIN_VALUE);
        var known = new ColumnInput(Level.OVERWORLD, 0, 0, 0, 1, air, sections);
        var legacy = new ColumnInput(Level.OVERWORLD, 0, 0, 0, 1, sections);
        assertTrue(known.hasTopSolidY()); assertFalse(legacy.hasTopSolidY());
        assertArrayEquals(known.topSolidY(), legacy.topSolidY());
        assertEquals(Integer.MIN_VALUE, legacy.topSolidY(0, 0));
    }

    @Test void horizontalHeightsRejectWrongLengthAndOutOfRangeValues() {
        dev.xantha.vss.testutil.MinecraftTestBootstrap.initialize();
        var sections = List.of(new ColumnInput.Section(-1, false, -1, null, null));
        for (int length : new int[] {0, 255, 257}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new ColumnInput(Level.OVERWORLD, 0, 0, -1, 0, new int[length], sections));
        }
        for (int height : new int[] {-17, 0, Integer.MAX_VALUE}) {
            int[] heights = new int[256]; Arrays.fill(heights, Integer.MIN_VALUE);
            heights[0] = height;
            assertThrows(IllegalArgumentException.class,
                    () -> new ColumnInput(Level.OVERWORLD, 0, 0, -1, 0, heights, sections));
        }
    }
}
