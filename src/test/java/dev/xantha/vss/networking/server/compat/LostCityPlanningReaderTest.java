package dev.xantha.vss.networking.server.compat;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.common.worldgen.LostCityPreview;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.*;
import org.objectweb.asm.*;

class LostCityPlanningReaderTest {
    @BeforeAll static void bootstrap() {
        if (net.neoforged.fml.loading.FMLPaths.GAMEDIR.get() == null)
            net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(Path.of("build", "tmp", "prediction-tests"));
        net.neoforged.fml.loading.LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        for (Block block : BuiltInRegistries.BLOCK) for (var state : block.getStateDefinition().getPossibleStates())
            if (Block.BLOCK_STATE_REGISTRY.getId(state) == -1) Block.BLOCK_STATE_REGISTRY.add(state);
    }

    public interface Templates {
        Palette getCompiledPalette();
        Part getFloor(int floor);
        Part getFloorPart2(int floor);
    }
    public static final class Palette {
        private final Map<Character, BlockState> states;
        public Palette() { this(Map.of()); }
        public Palette(Map<Character, BlockState> states) { this.states = states; }
        public BlockState get(char c, Random random) { return states.getOrDefault(c, Blocks.STONE.defaultBlockState()); }
    }

    public record SurfacePart(String[] getSlices, int getXSize, int getZSize) { }
    public static class SurfaceChunk extends LostCityStreetPlanTest.Chunk {
        public boolean active;
        public Palette palette = new Palette();
        public boolean isCity() { return active; }
        @Override public ResourceLocation getBuildingId() { return null; }
        public int getCityGroundLevel() { return 64; }
        public Palette getCompiledPalette() { return palette; }
    }

    public static class InfrastructureChunk extends SurfaceChunk {
        public int groundLevel = 64, waterLevel = 63;
        public final Object provider = new Object();
        @Override public boolean isCity() { return false; }
        @Override public Object hasZBridge(Object ignored) {
            return active ? new SurfacePart(new String[]{"ls"}, 2, 1) : null;
        }
    }

    @TestFactory Stream<DynamicTest> actualApiNonCityBridgeSurvivesEmptyCityFilterAndPaletteSnapshot() throws Exception {
        String directory = System.getProperty("vss.lostCitiesMatrix");
        Assumptions.assumeTrue(directory != null);
        List<Path> paths;
        try (var files = Files.list(Path.of(directory))) {
            paths = files.filter(p -> p.getFileName().toString().matches("lostcities-.*\\.jar"))
                    .filter(p -> Runtime.version().feature() >= 21 || !p.getFileName().toString().startsWith("lostcities-1.21-"))
                    .sorted().toList();
        }
        return paths.stream().map(path -> DynamicTest.dynamicTest(path.getFileName().toString(), () -> {
            LostCityPlanningReader.clear();
            try (var loader = new URLClassLoader(new java.net.URL[]{path.toUri().toURL()}, getClass().getClassLoader())) {
                var chunkApi = loader.loadClass("mcjty.lostcities.api.ILostChunkInfo");
                var infoApi = loader.loadClass("mcjty.lostcities.api.ILostCityInformation");
                Class<?> fixture = surfaceFixture(loader, chunkApi, InfrastructureChunk.class);
                Object info = Proxy.newProxyInstance(loader, new Class<?>[]{infoApi}, (p, method, args) -> {
                    if (method.getName().equals("getRealHeight")) return 64;
                    assertEquals("getChunkInfo", method.getName());
                    var chunk = (InfrastructureChunk) fixture.getConstructor().newInstance();
                    chunk.active = (int)args[0] == -8 && (int)args[1] == -16;
                    chunk.palette = new Palette(Map.of('l', Blocks.GLASS.defaultBlockState(), 's', Blocks.STONE.defaultBlockState()));
                    return chunk;
                });
                var chunks = LostCityPlanningReader.query(info, null, -1, -2);
                var bridge = chunks.get(0);
                assertEquals(0, bridge.kind()); assertFalse(bridge.flatten());
                assertEquals(1, bridge.overlays().size());
                var overlay = bridge.overlays().get(0); assertEquals(65, overlay.y());
                assertEquals(Block.getId(Blocks.STONE.defaultBlockState()), topState(overlay.model(), 0, 1, 1));
                assertTrue(java.util.stream.IntStream.range(0, overlay.model().quadCount()).anyMatch(q -> overlay.model().direction(q) == 5));
                assertTrue(chunks.subList(1, 64).stream().allMatch(c -> c.overlays().isEmpty()));
            }
        }));
    }

    @Test void surfaceOverlaysPreserveAirClearHardAirAndRotateStatesAndPositions() throws Exception {
        LostCityPlanningReader.clear();
        int stone = Block.getId(Blocks.STONE.defaultBlockState()), leaves = Block.getId(Blocks.OAK_LEAVES.defaultBlockState());
        var base = Collections.nCopies(256, stone);
        var palette = new Palette(Map.of('l', Blocks.OAK_LEAVES.defaultBlockState(), 'a', Blocks.AIR.defaultBlockState(),
                'v', Blocks.STRUCTURE_VOID.defaultBlockState(), 's', Blocks.OAK_STAIRS.defaultBlockState()));
        Object hedge = surfaceInput(new SurfacePart(new String[]{"ll"}, 2, 1), palette, 1, 0, true);
        Object clear = surfaceInput(new SurfacePart(new String[]{"va"}, 2, 1), palette, 1, 0, true);
        var clipped = surfaceModel(base, List.of(hedge, clear));
        assertEquals(stone, topState(clipped, 0, 1, 0));
        assertEquals(-1, topState(clipped, 0, 2, 0), "hard air removes the old hedge");
        assertEquals(leaves, topState(clipped, 1, 2, 0), "ordinary air preserves the old hedge");
        var retained = surfaceModel(base, List.of(hedge,
                surfaceInput(new SurfacePart(new String[]{"va"}, 2, 1), palette, 1, 0, false)));
        assertEquals(leaves, topState(retained, 0, 2, 0), "road parts ignore hard air");
        assertSame(clipped, surfaceModel(base, List.of(hedge, clear)), "identical snapshots reuse the model");
        var differentPalette = new Palette(Map.of('l', Blocks.GLASS.defaultBlockState()));
        var glass = surfaceModel(base, List.of(surfaceInput(new SurfacePart(new String[]{"ll"}, 2, 1), differentPalette, 1, 0, true)));
        assertEquals(Block.getId(Blocks.GLASS.defaultBlockState()), topState(glass, 0, 2, 0));
        int[][] positions = {{0, 0}, {15, 0}, {15, 15}, {0, 15}};
        var rotations = net.minecraft.world.level.block.Rotation.values();
        for (int turn = 0; turn < 4; turn++) {
            var rotated = surfaceModel(base, List.of(surfaceInput(new SurfacePart(new String[]{"s"}, 1, 1), palette, 1, turn, true)));
            assertEquals(Block.getId(Blocks.OAK_STAIRS.defaultBlockState().rotate(rotations[turn])),
                    topState(rotated, positions[turn][0], 2, positions[turn][1]), "rotate state and footprint together");
        }
    }

    private static int topState(LostCityPreview.Model model, int x, int y, int z) {
        for (int q = 0; q < model.quadCount(); q++) if (model.direction(q) == 0 && model.y(q) == y
                && model.x(q) <= x && x < model.x(q) + model.dx(q) && model.z(q) <= z && z < model.z(q) + model.dz(q)) return model.state(q);
        return -1;
    }
    private static Class<?> nested(String name) throws Exception { return Class.forName(LostCityPlanningReader.class.getName() + "$" + name); }
    private static Object construct(String name, Object... args) throws Exception {
        Constructor<?> constructor = nested(name).getDeclaredConstructors()[0]; constructor.setAccessible(true); return constructor.newInstance(args);
    }
    private static Object surfaceInput(SurfacePart part, Palette palette, int y, int turns, boolean clearAir) throws Exception {
        Method method = LostCityPlanningReader.class.getDeclaredMethod("templateInput", Object.class, Object.class,
                net.minecraft.world.level.CommonLevelAccessor.class, LostCityLegacyPalettes.class);
        method.setAccessible(true);
        return construct("SurfaceInput", method.invoke(null, part, palette, null, null), y, turns, clearAir);
    }
    private static LostCityPreview.Model surfaceModel(List<Integer> base, List<Object> parts) throws Exception {
        Object snapshot = construct("Snapshot", null, List.of(), List.of(), base, parts);
        Method surface = LostCityPlanningReader.class.getDeclaredMethod("surface", nested("Snapshot")); surface.setAccessible(true);
        Object models = surface.invoke(null, snapshot); Method detailed = models.getClass().getDeclaredMethod("detailed"); detailed.setAccessible(true);
        return (LostCityPreview.Model) detailed.invoke(models);
    }

    @TestFactory Stream<DynamicTest> actualApiSelectedParksProduceTemplateHedgesAtPlannedHeight() throws Exception {
        String directory = System.getProperty("vss.lostCitiesMatrix");
        Assumptions.assumeTrue(directory != null, "Supply release jars with -PvssLostCitiesMatrix");
        List<Path> paths;
        try (var files = Files.list(Path.of(directory))) {
            paths = files.filter(p -> p.getFileName().toString().matches("lostcities-.*\\.jar"))
                    .filter(p -> Runtime.version().feature() >= 21 || !p.getFileName().toString().startsWith("lostcities-1.21-"))
                    .sorted().toList();
        }
        return paths.stream().map(path -> DynamicTest.dynamicTest(path.getFileName().toString(), () -> {
            LostCityPlanningReader.clear();
            try (var loader = new URLClassLoader(new java.net.URL[]{path.toUri().toURL()}, getClass().getClassLoader())) {
                var chunkApi = loader.loadClass("mcjty.lostcities.api.ILostChunkInfo");
                var infoApi = loader.loadClass("mcjty.lostcities.api.ILostCityInformation");
                Class<?> fixture = surfaceFixture(loader, chunkApi);
                Object info = Proxy.newProxyInstance(loader, new Class<?>[]{infoApi}, (p, method, args) -> {
                    if (method.getName().equals("getRealHeight")) return 64;
                    assertEquals("getChunkInfo", method.getName());
                    var chunk = (SurfaceChunk) fixture.getConstructor().newInstance();
                    chunk.active = (int)args[0] == -8 && (int)args[1] == -16;
                    chunk.elevated = chunk.active;
                    chunk.parkType = new SurfacePart(new String[]{"l" + " ".repeat(255), "l" + " ".repeat(255)}, 16, 16);
                    chunk.palette = new Palette(Map.of('l', Blocks.OAK_LEAVES.defaultBlockState(), ' ', Blocks.AIR.defaultBlockState()));
                    return chunk;
                });
                var chunks = LostCityPlanningReader.query(info, null, -1, -2);
                var park = chunks.getFirst();
                assertEquals(LostCityPreview.PARK, park.kind()); assertEquals(65, park.ground());
                assertEquals(1, park.floors().size()); assertEquals(0, park.floors().getFirst().y());
                assertEquals(Block.getId(Blocks.OAK_LEAVES.defaultBlockState()), topState(park.floors().getFirst().model(), 0, 3, 0));
                assertEquals(68, park.top());
                assertNotNull(park.silhouette());
                assertTrue(park.silhouette().quadCount() <= 512);
                for (int i = 1; i < 64; i++) assertEquals(0, chunks.get(i).kind());
            }
        }));
    }

    private static Class<?> surfaceFixture(ClassLoader parent, Class<?> api) throws Exception {
        return surfaceFixture(parent, api, SurfaceChunk.class);
    }
    private static Class<?> surfaceFixture(ClassLoader parent, Class<?> api, Class<?> baseClass) throws Exception {
        String name = "dev/xantha/vss/networking/server/compat/ActualSurfaceFixture";
        String base = Type.getInternalName(baseClass);
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, base, new String[]{Type.getInternalName(api)});
        var init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode(); init.visitVarInsn(Opcodes.ALOAD, 0); init.visitMethodInsn(Opcodes.INVOKESPECIAL, base, "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN); init.visitMaxs(1, 1); init.visitEnd();
        String styleDescriptor = "()Ljava/lang/Object;";
        try { styleDescriptor = Type.getMethodDescriptor(api.getMethod("getCityStyle")); }
        catch (NoSuchMethodException legacy) { /* Older APIs expose style only on BuildingInfo. */ }
        var style = writer.visitMethod(Opcodes.ACC_PUBLIC, "getCityStyle", styleDescriptor, null, null);
        style.visitCode(); style.visitInsn(Opcodes.ACONST_NULL); style.visitInsn(Opcodes.ARETURN); style.visitMaxs(1, 1); style.visitEnd();
        writer.visitEnd(); byte[] bytes = writer.toByteArray();
        return new ClassLoader(parent) { Class<?> define() { return defineClass(name.replace('/', '.'), bytes, 0, bytes.length); } }.define();
    }
    public static final class Part {
        private final boolean empty;
        public Part() { this(false); }
        public Part(boolean empty) { this.empty = empty; }
        public String[] getSlices() { String[] result = new String[empty ? 0 : 6]; Arrays.fill(result, "s".repeat(256)); return result; }
        public int getXSize() { return 16; }
        public int getZSize() { return 16; }
    }

    @TestFactory Stream<DynamicTest> actualApiTemplatesProduceBuildingWallsAndRoof() throws Exception {
        String directory = System.getProperty("vss.lostCitiesMatrix");
        Assumptions.assumeTrue(directory != null, "Supply release jars with -PvssLostCitiesMatrix");
        List<Path> paths;
        try (var files = Files.list(Path.of(directory))) {
            paths = files.filter(p -> p.getFileName().toString().matches("lostcities-.*\\.jar"))
                    // Forge 1.20.1 uses Java 17. Bytecode contracts for all seven
                    // releases are still checked by LostCityReleaseMatrixTest.
                    .filter(p -> Runtime.version().feature() >= 21 || !p.getFileName().toString().startsWith("lostcities-1.21-"))
                    .sorted().toList();
        }
        assertFalse(paths.isEmpty());
        return paths.stream().map(path -> DynamicTest.dynamicTest(path.getFileName().toString(), () -> {
            LostCityPlanningReader.clear();
            try (var loader = new URLClassLoader(new java.net.URL[] {path.toUri().toURL()}, getClass().getClassLoader())) {
                var chunkApi = loader.loadClass("mcjty.lostcities.api.ILostChunkInfo");
                var infoApi = loader.loadClass("mcjty.lostcities.api.ILostCityInformation");
                for (boolean emptyRoof : new boolean[]{false, true}) {
                Palette palette = new Palette(); Part part = new Part(); AtomicInteger count = new AtomicInteger();
                Object info = Proxy.newProxyInstance(loader, new Class<?>[] {infoApi}, (p, m, args) -> {
                    if (m.getName().equals("getRealHeight")) return 64;
                    assertEquals("getChunkInfo", m.getName());
                    int x = (int) args[0], z = (int) args[1];
                    assertTrue(x >= -8 && x < 0 && z >= -16 && z < -8);
                    count.incrementAndGet();
                    boolean building = x == -8 && z == -16;
                    return Proxy.newProxyInstance(loader, new Class<?>[] {chunkApi, Templates.class}, (c, method, a) -> switch (method.getName()) {
                        case "getBuildingId" -> building ? ResourceLocation.parse("lostcities:test") : null;
                        case "isCity" -> building;
                        case "getCityLevel" -> 0;
                        case "getNumFloors" -> 2;
                        case "getCityStyle" -> null;
                        case "getCompiledPalette" -> palette;
                        case "getFloor" -> emptyRoof && (int) a[0] == 2 ? new Part(true) : part;
                        case "getFloorPart2" -> null;
                        default -> throw new AssertionError(method.getName());
                    });
                });
                var chunks = LostCityPlanningReader.query(info, null, -1, -2);
                assertEquals(64, count.get()); assertEquals(64, chunks.size());
                var building = chunks.getFirst();
                assertTrue(building.building()); assertEquals(64, building.ground());
                assertEquals(emptyRoof ? 2 : 3, building.floors().size()); assertEquals(emptyRoof ? 76 : 82, building.top());
                assertNotNull(building.silhouette()); assertTrue(building.silhouette().quadCount() > 0);
                int stone = Block.getId(Blocks.STONE.defaultBlockState());
                for (int f = 0; f < building.floors().size(); f++) {
                    var placement = building.floors().get(f);
                    assertEquals(f * 6, placement.y()); assertEquals(5, placement.model().quadCount());
                    for (int q = 0; q < placement.model().quadCount(); q++) assertEquals(stone, placement.model().state(q));
                }
                assertSame(building.floors().get(0).model(), building.floors().get(1).model(), "identical floor templates share the cached mesh");
                for (int i = 1; i < 64; i++) assertEquals(0, chunks.get(i).kind());
                }
            }
        }));
    }
}
