package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;
import dev.xantha.vss.mixin.client.NbtTreePredictionTemplatesMixin;
import java.util.jar.JarFile;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

class NbtTreeHeightLimitTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void virtualLevelUsesPredictionGeneratorAndOrdinaryWorldIsForwarded() throws Exception {
        var profile = new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                net.minecraft.resources.ResourceLocation.withDefaultNamespace("overworld"),42,-64,384,"noise","minecraft:overworld",1);
        var sampler = spy(new ClientTerrainSampler(42,profile));
        var generator = mock(NoiseBasedChunkGenerator.class);
        doReturn(generator).when(sampler).generatorContext();
        var level = new PredictionDecorationLevel(sampler,sampler,RegistryAccess.EMPTY,0,0);
        var mixin = NbtTreePredictionTemplatesMixin.class;
        var first = mixin.getDeclaredMethod("vss$heightLevel",WorldGenLevel.class);
        var second = mixin.getDeclaredMethod("vss$heightChunks",ServerLevel.class);
        var third = mixin.getDeclaredMethod("vss$heightGenerator",ServerChunkCache.class,Object.class,
                WorldGenLevel.class,java.util.Map.class,java.util.Map.class,java.util.Map.class);
        first.setAccessible(true);second.setAccessible(true);third.setAccessible(true);
        assertNull(first.invoke(null,level));
        assertNull(second.invoke(null,new Object[]{null}));
        assertSame(generator,third.invoke(null,null,null,level,null,null,null));
        assertThrows(UnsupportedOperationException.class,level::getLevel);
        var normal = mock(WorldGenLevel.class);
        var realLevel = mock(ServerLevel.class);
        var chunks = mock(ServerChunkCache.class);
        var realGenerator = mock(NoiseBasedChunkGenerator.class);
        when(normal.getLevel()).thenReturn(realLevel);
        when(realLevel.getChunkSource()).thenReturn(chunks);
        when(chunks.getGenerator()).thenReturn(realGenerator);
        assertSame(realLevel,first.invoke(null,normal));
        assertSame(chunks,second.invoke(null,realLevel));
        assertSame(realGenerator,third.invoke(null,chunks,null,normal,null,null,null));
    }

    @Test void released535HeightHelpersContainExactlyTheRedirectedLookup() throws Exception {
        String path = System.getProperty("vss.nbtTreeJar");
        assumeTrue(path != null);
        try (var jar = new JarFile(path)) {
            for (String name : new String[] {"TreeFromStructureNBTFeature","TreeFromStructureNBTFeatureV2"}) {
                var node = new ClassNode();
                var entry = jar.getJarEntry("dev/corgitaco/ohthetreesyoullgrow/world/level/levelgen/feature/"+name+".class");
                assertNotNull(entry);
                try (var in = jar.getInputStream(entry)) { new ClassReader(in).accept(node,ClassReader.SKIP_DEBUG); }
                var helpers = node.methods.stream().filter(m -> m.name.equals("testHeightLimit")).toList();
                assertEquals(1,helpers.size(),name);
                var calls = new java.util.ArrayList<String>();
                for (var instruction : helpers.getFirst().instructions)
                    if (instruction instanceof MethodInsnNode call) calls.add(call.owner+"."+call.name+call.desc);
                assertEquals(1,calls.stream().filter(s -> s.contains("WorldGenLevel.getLevel()")).count(),name);
                assertEquals(1,calls.stream().filter(s -> s.contains("ServerLevel.getChunkSource()")).count(),name);
                assertEquals(1,calls.stream().filter(s -> s.contains("ServerChunkCache.getGenerator()")).count(),name);
            }
        }
    }
}
