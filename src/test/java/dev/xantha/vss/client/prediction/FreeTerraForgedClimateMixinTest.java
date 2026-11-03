package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import dev.xantha.vss.testutil.VoxyMixinHarness;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.ClassNode;

/** Applies the released Sponge mixin before executing the copied climate API. */
@EnabledIfSystemProperty(named = "vss.freeTerraForgedJar", matches = ".+")
class FreeTerraForgedClimateMixinTest {
    private static final String API = "etcodehome.freeterraforged.world.worldgen.biome.FTFClimateSampler";
    private static Class<?> sampler;

    @BeforeAll static void bootstrap() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        var harness = new VoxyMixinHarness(System.getProperty("vss.freeTerraForgedJar"), """
                {"required":true,"minVersion":"0.8","package":"etcodehome.freeterraforged.mixin",
                 "compatibilityLevel":"JAVA_21","mixins":["MixinClimateSampler"]}
                """);
        harness.initialize();
        Class.forName("com.llamalad7.mixinextras.MixinExtrasBootstrap").getMethod("init").invoke(null);
        // Rename only the target after real Mixin application so the production
        // Climate.Sampler already loaded by the test JVM remains untouched.
        byte[] transformed = harness.apply("net.minecraft.world.level.biome.Climate$Sampler");
        String evidence = System.getProperty("vss.ftfEvidenceDirectory");
        if (evidence != null) {
            var directory = java.nio.file.Path.of(evidence);
            java.nio.file.Files.createDirectories(directory);
            java.nio.file.Files.write(directory.resolve("Climate$Sampler.class"), transformed);
        }
        String fixture = "dev/xantha/vss/client/prediction/ReleasedClimateSamplerFixture";
        ClassNode node = new ClassNode();
        new ClassReader(transformed).accept(new ClassRemapper(node, new SimpleRemapper(
                "net/minecraft/world/level/biome/Climate$Sampler", fixture)), 0);
        node.nestHostClass = null;
        node.innerClasses.clear();
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        byte[] bytes = writer.toByteArray();
        class Loader extends java.net.URLClassLoader {
            Loader() throws Exception {
                super(new java.net.URL[]{java.nio.file.Path.of(System.getProperty("vss.freeTerraForgedJar")).toUri().toURL()},
                        FreeTerraForgedClimateMixinTest.class.getClassLoader());
            }
            Class<?> define() { return defineClass(fixture.replace('/', '.'), bytes, 0, bytes.length); }
        }
        sampler = new Loader().define();
    }

    private static Object create() throws Exception {
        var zero = DensityFunctions.zero();
        return sampler.getConstructor(DensityFunction.class, DensityFunction.class, DensityFunction.class,
                DensityFunction.class, DensityFunction.class, DensityFunction.class, List.class)
                .newInstance(zero, zero, zero, zero, zero, zero, List.of());
    }

    @Test void copiesContextWithTheReleasedNonNullSpawnContract() throws Exception {
        Object source = create(), target = create();
        Class<?> api = Class.forName(API, true, sampler.getClassLoader());
        var get = api.getMethod("getSpawnSearch");
        var set = api.getMethod("setSpawnSearch", get.getReturnType());
        var rejected = assertThrows(InvocationTargetException.class, () -> set.invoke(target, new Object[]{null}));
        assertInstanceOf(NullPointerException.class, rejected.getCause());
        var components = get.getReturnType().getRecordComponents();
        Object user = components[0].getType().getField("USER_SELECTED").get(null);
        Object search = get.getReturnType().getConstructor(components[0].getType(), net.minecraft.core.BlockPos.class)
                .newInstance(user, new net.minecraft.core.BlockPos(-173, 97, 4097));
        set.invoke(source, search);
        assertDoesNotThrow(() -> FreeTerraForgedCompat.copyClimateContext(source, target));
        assertEquals(get.invoke(source), get.invoke(target));
    }

    @Test void absentSourceSpawnSearchKeepsUsableDestinationAndCopiesClimate() throws Exception {
        Object source = create(), target = create();
        Class<?> api = Class.forName(API, true, sampler.getClassLoader());
        var get = api.getMethod("getSpawnSearch");
        var field = sampler.getDeclaredField("spawnSearch");
        field.setAccessible(true);
        field.set(source, null); // The state recorded by the failing client session.
        assertDoesNotThrow(() -> FreeTerraForgedCompat.copyClimateContext(source, target));
        assertNotNull(get.invoke(target), "prediction must retain an initialized spawn-search state");
        Object search = get.invoke(target);
        assertEquals("WORLD_ORIGIN", get.getReturnType().getMethod("type").invoke(search).toString());
        assertEquals(net.minecraft.core.BlockPos.ZERO, get.getReturnType().getMethod("center").invoke(search));
        var semantics = api.getMethod("climateQuerySemantics");
        assertNotNull(semantics.invoke(target));
        assertEquals(semantics.invoke(source).getClass().getMethod("policy").invoke(semantics.invoke(source)),
                semantics.invoke(target).getClass().getMethod("policy").invoke(semantics.invoke(target)));
    }

    @Test void missingSourceSearchPreservesExistingDestinationSearch() throws Exception {
        Object source = create(), target = create();
        Class<?> api = Class.forName(API, true, sampler.getClassLoader());
        var get = api.getMethod("getSpawnSearch");
        var field = sampler.getDeclaredField("spawnSearch");
        field.setAccessible(true);
        field.set(source, null);
        var type = get.getReturnType().getRecordComponents()[0].getType();
        Object search = get.getReturnType().getConstructor(type, net.minecraft.core.BlockPos.class)
                .newInstance(type.getField("USER_SELECTED").get(null), new net.minecraft.core.BlockPos(300, 80, -900));
        api.getMethod("setSpawnSearch", get.getReturnType()).invoke(target, search);
        FreeTerraForgedCompat.copyClimateContext(source, target);
        assertSame(search, get.invoke(target));
    }

    @Test void missingSpawnSearchPreservesClimatePolicyFullSeedAndIndependentCaches() throws Exception {
        Object source = create(), target = create();
        Class<?> api = Class.forName(API, true, sampler.getClassLoader());
        var set = java.util.Arrays.stream(api.getMethods()).filter(m -> m.getName().equals("setClimateQuerySemantics"))
                .findFirst().orElseThrow();
        Object policy = set.getParameterTypes()[0].getField("WORLDGEN").get(null);
        set.invoke(source, policy, null, 918273645123L, null);
        var field = sampler.getDeclaredField("spawnSearch");
        field.setAccessible(true);
        field.set(source, null);
        FreeTerraForgedCompat.copyClimateContext(source, target);
        var read = api.getMethod("climateQuerySemantics");
        Object original = read.invoke(source), copied = read.invoke(target);
        for (String key : List.of("policy", "preset", "seed", "surfaceContext"))
            assertEquals(original.getClass().getMethod(key).invoke(original), copied.getClass().getMethod(key).invoke(copied), key);
        assertNotSame(original.getClass().getMethod("cacheOwner").invoke(original),
                copied.getClass().getMethod("cacheOwner").invoke(copied));
    }
}
