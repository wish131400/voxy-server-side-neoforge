package dev.xantha.vss.mixin.thebeyond;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.google.gson.JsonParser;
import dev.xantha.vss.client.prediction.feature.FeatureStampLevel;
import dev.xantha.vss.testutil.TerrainPredictionFixtures;
import dev.xantha.vss.testutil.VoxyMixinHarness;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipOutputStream;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * VSS's shipped vss.thebeyond.mixins.json woven into StructureStart with Sponge Mixin and MixinExtras. With
 * -PvssTheBeyondJar, The Beyond's own StructureStartSanctionMixin is woven beside it and the patched handler runs on
 * prediction levels and a real-level stand-in; without it only VSS's guard is woven.
 */
class TheBeyondStructureStartGuardTest {
    private static final String TARGET = TheBeyondMixinPlugin.TARGET;
    private static final String CONFIG = "src/main/resources/vss.thebeyond.mixins.json";
    @TempDir static Path directory;
    private static byte[] transformed;
    private static Class<?> woven;
    private static boolean withTheBeyond;

    @BeforeAll
    static void weave() throws Exception {
        TerrainPredictionFixtures.bootstrap();
        String beyond = System.getProperty("vss.theBeyondJar");
        withTheBeyond = beyond != null && !beyond.isBlank();
        Path jar;
        if (withTheBeyond) {
            jar = Path.of(beyond);
        } else {
            jar = directory.resolve("classpath.zip");
            try (var zip = new ZipOutputStream(Files.newOutputStream(jar))) { }
        }
        var harness = new VoxyMixinHarness(jar.toString(), Files.readString(Path.of(CONFIG)));
        if (withTheBeyond) harness.addConfiguration("the_beyond.test.mixins.json", """
                {"required":true,"minVersion":"0.8","package":"com.thebeyond.mixin",
                 "compatibilityLevel":"JAVA_21","mixins":["StructureStartSanctionMixin"]}
                """);
        harness.initialize();
        Class.forName("com.llamalad7.mixinextras.MixinExtrasBootstrap").getMethod("init").invoke(null);
        transformed = harness.apply(TARGET);
        URL[] urls = withTheBeyond ? new URL[]{jar.toUri().toURL()} : new URL[0];
        woven = new Loader(urls).define(TARGET, transformed);
    }

    private static final class Loader extends URLClassLoader {
        Loader(URL[] urls) { super(urls, TheBeyondStructureStartGuardTest.class.getClassLoader()); }
        Class<?> define(String name, byte[] bytes) { return defineClass(name, bytes, 0, bytes.length); }
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static FeatureStampLevel prediction(ResourceLocation dimension) {
        return new FeatureStampLevel(42, TerrainPredictionFixtures.access(), Blocks.GRASS_BLOCK.defaultBlockState()) {
            @Override public ResourceLocation predictionDimension() { return dimension; }
        };
    }

    private static int guard(Object level) throws Throwable {
        Method found = null;
        for (Method method : woven.getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers()) && method.getReturnType() == int.class
                    && method.getParameterCount() == 1 && method.getParameterTypes()[0] == WorldGenLevel.class
                    && method.getName().contains("predictionEnd")) {
                assertNull(found, "one merged guard");
                found = method;
            }
        }
        assertNotNull(found, "the guard is merged into StructureStart");
        found.setAccessible(true);
        try {
            return (int) found.invoke(null, level);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    @Test
    void optionalConfigurationIsShippedWithItsPlugin() throws Exception {
        var json = JsonParser.parseString(Files.readString(Path.of(CONFIG))).getAsJsonObject();
        assertFalse(json.get("required").getAsBoolean(), "a refused patch must never stop the game from starting");
        assertEquals(TheBeyondMixinPlugin.class.getName(), json.get("plugin").getAsString());
        assertEquals(List.of("StructureStartPredictionEndMixin"),
                json.getAsJsonArray("client").asList().stream().map(element -> element.getAsString()).toList());
        assertFalse(json.has("mixins") && !json.getAsJsonArray("mixins").isEmpty(), "client only");
        try (var input = getClass().getResourceAsStream("/vss.thebeyond.mixins.json")) {
            assertNotNull(input, "the optional configuration must survive the build resource filters");
            assertEquals(json, JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)));
        }
        String build = Files.readString(Path.of("build.gradle"));
        assertTrue(build.contains("include 'vss.thebeyond.mixins.json'"));
        assertTrue(build.contains("vss.lostcities.mixins.json,vss.thebeyond.mixins.json'"), "listed in MixinConfigs");
        var plugin = new TheBeyondMixinPlugin();
        assertEquals(withTheBeyond, TheBeyondMixinPlugin.beyondInstalled(), "The Beyond's handler mixin decides");
        assertEquals(withTheBeyond, plugin.shouldApplyMixin(TARGET, BeyondHandlerPatch.GUARD_MIXIN));
        assertFalse(plugin.shouldApplyMixin("net.minecraft.world.level.Level", BeyondHandlerPatch.GUARD_MIXIN));
    }

    @Test
    @DisabledIfSystemProperty(named = "vss.theBeyondJar", matches = ".+")
    void withoutTheBeyondStructureStartIsNotChanged() throws Exception {
        for (Method method : woven.getDeclaredMethods()) assertFalse(method.getName().contains("predictionEnd"), method.getName());
        try (var input = getClass().getResourceAsStream("/" + BeyondHandlerPatch.TARGET + ".class")) {
            ClassNode original = node(input.readAllBytes());
            assertEquals(original.methods.size(), node(transformed).methods.size());
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "vss.theBeyondJar", matches = ".+")
    void guardAnswersOnlyForPredictionLevelsWithAKnownDimension() throws Throwable {
        assertEquals(1, guard(prediction(Level.END.location())));
        assertEquals(0, guard(prediction(Level.OVERWORLD.location())));
        assertEquals(0, guard(prediction(Level.NETHER.location())));
        assertEquals(0, guard(prediction(ResourceLocation.parse("twilightforest:twilight_forest"))));
        assertEquals(-1, guard(prediction(null)), "no profile: The Beyond's own check");
        assertEquals(-1, guard(mock(WorldGenLevel.class)), "real levels keep The Beyond's own check");
        var broken = new FeatureStampLevel(42, TerrainPredictionFixtures.access(), Blocks.GRASS_BLOCK.defaultBlockState()) {
            @Override public ResourceLocation predictionDimension() { throw new IllegalStateException("no terrain"); }
        };
        assertEquals(-1, guard(broken), "a failing dimension read keeps The Beyond's own check");
    }

    @Test
    void handlerWithoutTheBeyondIsRefusedAsOneErrorAndLeftUntouched() throws Exception {
        ClassNode vanilla;
        try (var input = getClass().getResourceAsStream("/" + BeyondHandlerPatch.TARGET + ".class")) {
            vanilla = node(input.readAllBytes());
        }
        int before = vanilla.methods.stream().mapToInt(method -> method.instructions.size()).sum();
        var report = TheBeyondMixinPlugin.report(vanilla);
        assertTrue(report.error());
        assertTrue(report.message().startsWith("Left The Beyond's (the_beyond) StructureStart.placeInChunk handler untouched (HANDLER_MISSING)"),
                report.message());
        assertEquals(before, vanilla.methods.stream().mapToInt(method -> method.instructions.size()).sum());
    }

    // ---- with The Beyond's own handler ----

    private static MethodNode handler(ClassNode node) {
        List<MethodNode> merged = new ArrayList<>();
        for (MethodNode method : BeyondHandlerPatch.mergedFrom(node, BeyondHandlerPatch.BEYOND_MIXIN))
            if (BeyondHandlerPatch.HANDLER_DESC.equals(method.desc)) merged.add(method);
        assertEquals(1, merged.size(), "The Beyond's handler is merged once");
        return merged.get(0);
    }

    @Test
    @EnabledIfSystemProperty(named = "vss.theBeyondJar", matches = ".+")
    void theBeyondsHandlerAsksTheGuardOnceRightBeforeItsOwnEndCheck() {
        MethodNode handler = handler(node(transformed));
        int guards = 0, getLevels = 0;
        MethodInsnNode guard = null;
        for (AbstractInsnNode insn : handler.instructions) {
            if (!(insn instanceof MethodInsnNode call)) continue;
            if (call.owner.equals(BeyondHandlerPatch.TARGET) && call.desc.equals(BeyondHandlerPatch.GUARD_DESC)) { guards++; guard = call; }
            if (call.name.equals("getLevel")) getLevels++;
        }
        assertEquals(1, guards);
        assertEquals(1, getLevels, "The Beyond's own check stays for every other level");
        AbstractInsnNode next = guard.getNext();
        while (next.getOpcode() < 0) next = next.getNext();
        assertInstanceOf(LookupSwitchInsnNode.class, next);
        assertEquals(List.of(BeyondHandlerPatch.NOT_END, BeyondHandlerPatch.END), ((LookupSwitchInsnNode) next).keys);
    }

    @Test
    @EnabledIfSystemProperty(named = "vss.theBeyondJar", matches = ".+")
    void aChangedHandlerIsRefusedAsOneErrorAndLeftUntouched() {
        ClassNode patched = node(transformed);
        int before = handler(patched).instructions.size();
        var report = TheBeyondMixinPlugin.report(patched);
        assertTrue(report.error(), "an already guarded handler no longer has the verified shape");
        assertTrue(report.message().contains("(HANDLER_CHANGED)"), report.message());
        assertEquals(before, handler(patched).instructions.size());
    }

    private static Object start() throws Exception {
        return woven.getConstructor(Structure.class, ChunkPos.class, int.class, PiecesContainer.class)
                .newInstance(mock(Structure.class), new ChunkPos(0, 0), 0, new PiecesContainer(List.of()));
    }

    private static void place(Object level) throws Throwable {
        Method place = woven.getMethod("placeInChunk", WorldGenLevel.class, StructureManager.class, ChunkGenerator.class,
                RandomSource.class, BoundingBox.class, ChunkPos.class);
        try {
            place.invoke(start(), level, null, null, RandomSource.create(1), new BoundingBox(0, 0, 0, 15, 255, 15), new ChunkPos(0, 0));
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "vss.theBeyondJar", matches = ".+")
    void predictedStructuresPlaceWithoutAskingForAServerLevel() throws Throwable {
        assertDoesNotThrow(() -> place(prediction(Level.OVERWORLD.location())));
        assertDoesNotThrow(() -> place(prediction(Level.NETHER.location())));
        assertDoesNotThrow(() -> place(prediction(Level.END.location())));
        var unknown = assertThrows(UnsupportedOperationException.class, () -> place(prediction(null)),
                "a prediction level without a dimension still reaches The Beyond's own getLevel()");
        assertTrue(String.valueOf(unknown.getMessage()).contains("getLevel"), unknown.getMessage());

        var real = mock(WorldGenLevel.class);
        var server = mock(ServerLevel.class);
        when(real.getLevel()).thenReturn(server);
        when(server.dimension()).thenReturn(Level.OVERWORLD);
        place(real);
        verify(real, times(1)).getLevel();
    }
}
