package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import dev.xantha.vss.mixin.client.NbtTreePredictionTemplatesMixin;
import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.jar.JarFile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

class NbtTreePredictionTemplatesTest {
    private static final class MixinInstance extends NbtTreePredictionTemplatesMixin { }
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void virtualTreeLookupUsesVerifiedManagerAndNormalWorldGetLevelIsForwarded() throws Exception {
        var methods = NbtTreePredictionTemplatesMixin.class.getDeclaredMethods();
        var world = java.util.Arrays.stream(methods).filter(method -> method.getName().equals("vss$avoidLiveWorld")).findFirst().orElseThrow();
        var templates = java.util.Arrays.stream(methods).filter(method -> method.getName().equals("vss$verifiedTreeTemplates")).findFirst().orElseThrow();
        world.setAccessible(true); templates.setAccessible(true);
        var profile = new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                net.minecraft.resources.ResourceLocation.withDefaultNamespace("overworld"), 42, -64, 384, "noise", "minecraft:overworld", 1);
        var sampler = new ClientTerrainSampler(42, profile);
        var level = new PredictionDecorationLevel(sampler, sampler, RegistryAccess.EMPTY, 0, 0);
        var context = new FeaturePlaceContext<>(Optional.empty(), level, null, RandomSource.create(42), BlockPos.ZERO,
                NoneFeatureConfiguration.INSTANCE);
        var mixin = new MixinInstance();
        assertNull(world.invoke(mixin, level), "No access to a live ServerLevel during prediction");
        assertSame(level.templateManager(), templates.invoke(mixin, null, context));
        var forwarded = new java.util.concurrent.atomic.AtomicInteger();
        var normal = (WorldGenLevel) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {WorldGenLevel.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getLevel")) { forwarded.incrementAndGet(); return null; }
                    throw new AssertionError("Unexpected real-world method: " + method);
                });
        assertFalse(PredictionTemplateAccess.virtual(normal));
        assertNull(world.invoke(mixin, normal));
        assertEquals(1, forwarded.get());
    }

    @Test void distantFlatCaptureUsesTheVerifiedManagerWithoutExposingLiveServer() throws Exception {
        var profile=new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                net.minecraft.resources.ResourceLocation.withDefaultNamespace("overworld"),42,-64,384,"noise","minecraft:overworld",1);
        var sampler=new ClientTerrainSampler(42,profile);
        var level=new PredictionTemplateStampLevel(42,sampler,net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState());
        var world=NbtTreePredictionTemplatesMixin.class.getDeclaredMethod("vss$avoidLiveWorld",WorldGenLevel.class);
        var templates=NbtTreePredictionTemplatesMixin.class.getDeclaredMethod("vss$verifiedTreeTemplates",ServerLevel.class,FeaturePlaceContext.class);
        world.setAccessible(true);templates.setAccessible(true);
        var mixin=new MixinInstance();
        var context=new FeaturePlaceContext<>(Optional.empty(),level,null,RandomSource.create(42),BlockPos.ZERO,NoneFeatureConfiguration.INSTANCE);
        assertTrue(PredictionTemplateAccess.virtual(level));
        assertNull(world.invoke(mixin,level));
        assertSame(level.templateManager(),templates.invoke(mixin,null,context));
        assertEquals(profile.dimension(),level.predictionDimension());
        assertThrows(UnsupportedOperationException.class,level::getLevel,"Template support must not expose a real world");
    }

    @Test void bothReleasedTreeVersionsHaveExactlyTheRedirectedManagerChain() throws Exception {
        String path = System.getProperty("vss.nbtTreeJar");
        assumeTrue(path != null, "Supply the released OTTYG JAR with -PvssNbtTreeJar");
        var mixin = new ClassNode();
        try (var input = getClass().getResourceAsStream("/dev/xantha/vss/mixin/client/NbtTreePredictionTemplatesMixin.class")) {
            new ClassReader(input).accept(mixin, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        try (var jar = new JarFile(path)) {
            for (String name : new String[] {"TreeFromStructureNBTFeature", "TreeFromStructureNBTFeatureV2"}) {
                var node = new ClassNode();
                try (var stream = jar.getInputStream(jar.getJarEntry(
                        "dev/corgitaco/ohthetreesyoullgrow/world/level/levelgen/feature/" + name + ".class"))) {
                    new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                }
                int levels = 0, managers = 0;
                for (var method : node.methods) if (method.name.equals("place") || method.name.equals("m_142674_")) {
                    for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call) {
                        if (call.owner.equals("net/minecraft/world/level/WorldGenLevel")
                                && (call.name.equals("getLevel") || call.name.equals("m_6018_"))) {
                            levels++;
                            validateRedirect(mixin, "vss$avoidLiveWorld", call, method.desc, false);
                            assertTrue(call.getNext() instanceof MethodInsnNode, "Only the manager consumes the redirected receiver");
                            var next = (MethodInsnNode) call.getNext();
                            assertEquals("net/minecraft/server/level/ServerLevel", next.owner);
                            assertTrue(next.name.equals("getStructureManager") || next.name.equals("m_215082_"));
                        }
                        if (call.owner.equals("net/minecraft/server/level/ServerLevel")
                                && (call.name.equals("getStructureManager") || call.name.equals("m_215082_"))) {
                            managers++;
                            validateRedirect(mixin, "vss$verifiedTreeTemplates", call, method.desc, true);
                        }
                    }
                }
                assertEquals(1, levels, name);
                assertEquals(1, managers, name);
            }
        }
    }

    private static void validateRedirect(ClassNode mixin, String name, MethodInsnNode invocation,
                                         String targetMethod, boolean captureContext) {
        var handler = mixin.methods.stream().filter(method -> method.name.equals(name)).findFirst().orElseThrow();
        var args = new java.util.ArrayList<Type>();
        args.add(Type.getObjectType(invocation.owner));
        args.addAll(java.util.List.of(Type.getArgumentTypes(invocation.desc)));
        // Mixin RedirectInjector.validateParams permits trailing arguments from the enclosing method.
        if (captureContext) args.addAll(java.util.List.of(Type.getArgumentTypes(targetMethod)));
        assertEquals(Type.getMethodDescriptor(Type.getReturnType(invocation.desc), args.toArray(Type[]::new)), handler.desc);
    }
}
