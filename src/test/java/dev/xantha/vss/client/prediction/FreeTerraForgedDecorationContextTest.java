package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.google.gson.JsonObject;
import dev.xantha.vss.common.worldgen.WorldgenTemplateResources;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import dev.xantha.vss.testutil.TerrainPredictionFixtures;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarFile;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.RandomState;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

class FreeTerraForgedDecorationContextTest {
    @BeforeAll static void bootstrap() {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        FarVanillaTags.bootstrap();
    }

    @Test void predictionStateDoesNotReadServerWorldAndOrdinaryWorldRetainsItsServer() {
        var sampler = sampler(new JsonObject());
        var state = mock(RandomState.class);when(sampler.randomStateContext()).thenReturn(state);
        var level = new PredictionDecorationLevel(sampler, sampler, TerrainPredictionFixtures.access(), 0, 0);
        assertSame(state, FreeTerraForgedDecorationContext.randomState(level));
        assertThrows(UnsupportedOperationException.class, level::getLevel);
        assertThrows(UnsupportedOperationException.class, level::getServer);
        var world = mock(WorldGenLevel.class);var server = mock(MinecraftServer.class);
        when(world.getServer()).thenReturn(server);
        assertSame(server, FreeTerraForgedDecorationContext.templateServer(world, Runnable.class));
        assertThrows(IllegalArgumentException.class, () -> FreeTerraForgedDecorationContext.templateServer(level, Runnable.class));
    }

    @Test
    @EnabledIfSystemProperty(named="vss.freeTerraForgedJar",matches=".+")
    void originalReleasedTemplateManagerLoadsAuthoritativeNbtThroughReadOnlyInterface() throws Exception {
        Path path = Path.of(System.getProperty("vss.freeTerraForgedJar"));
        var encoded = new JsonObject();var changes = new HashMap<String,byte[]>();
        ResourceLocation resource = ResourceLocation.parse("freeterraforged:structures/trees/oak/small/oak_bush_1.nbt");
        String apiName = "etcodehome.freeterraforged.server.FTFMinecraftServer";
        try (var jar = new JarFile(path.toFile())) {
            if (jar.getJarEntry("data/" + resource.getNamespace() + "/" + resource.getPath()) == null) {
                resource = ResourceLocation.parse("reterraforged:structures/trees/oak/small/oak_bush_1.nbt");
                apiName = "raccoonman.reterraforged.server.RTFMinecraftServer";
            }
            var entry = jar.getJarEntry("data/" + resource.getNamespace() + "/" + resource.getPath());
            assertNotNull(entry);
            encoded.addProperty(WorldgenTemplateResources.key(resource).toString(), Base64.getEncoder().encodeToString(
                    jar.getInputStream(entry).readAllBytes()));
            for (var clazz : jar.stream().filter(e -> e.getName().endsWith(".class")).toList()) {
                var node = new ClassNode();new ClassReader(jar.getInputStream(clazz)).accept(node, 0);
                if (dev.xantha.vss.compat.transform.FreeTerraForgedDecorationTransformer.instrument(node) == 0) continue;
                var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(writer);
                changes.put(node.name.replace('/','.'),writer.toByteArray());
            }
        }
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{path.toUri().toURL()},getClass().getClassLoader()) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] replacement=changes.get(name);
                return replacement==null?super.findClass(name):defineClass(name,replacement,0,replacement.length);
            }
        }) {
            var sampler = sampler(encoded);
            var level = new PredictionDecorationLevel(sampler, sampler, TerrainPredictionFixtures.access(), 0, 0);
            Class<?> api = loader.loadClass(apiName);
            Object proxy = FreeTerraForgedDecorationContext.templateServer(level, api);
            assertTrue(api.isInstance(proxy));assertFalse(proxy instanceof MinecraftServer);
            assertSame(proxy, FreeTerraForgedDecorationContext.templateServer(level, api));
            var neighbor = new PredictionDecorationLevel(sampler, sampler, TerrainPredictionFixtures.access(), 1, 0);
            assertSame(proxy, FreeTerraForgedDecorationContext.templateServer(neighbor, api), "one sampler shares parsed templates across chunks");
            var stamp = new dev.xantha.vss.client.prediction.feature.FeatureStampLevel(1,TerrainPredictionFixtures.access(),
                    net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState()) {
                @Override public ClientTerrainSampler predictionContext() { return sampler; }
                @Override public net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> getBiome(net.minecraft.core.BlockPos pos) {
                    return TerrainPredictionFixtures.access().registryOrThrow(net.minecraft.core.registries.Registries.BIOME)
                            .getHolderOrThrow(net.minecraft.world.level.biome.Biomes.FOREST);
                }
            };
            assertSame(proxy,FreeTerraForgedDecorationContext.templateServer(stamp,api),"far model stamps share their explicit immutable context");
            Object manager = api.getMethod("getFeatureTemplateManager").invoke(proxy);
            Object template = manager.getClass().getMethod("load", ResourceLocation.class).invoke(manager, resource);
            assertNotNull(template, "the released parser must load the exact server template bytes");
            assertSame(template, manager.getClass().getMethod("load", ResourceLocation.class).invoke(manager, resource));
            String featureName = apiName.substring(0, apiName.indexOf("server.")) + "world.worldgen.feature.template.TemplateFeature";
            var feature = loader.loadClass(featureName);
            String templatePackage = featureName.substring(0,featureName.lastIndexOf('.')+1);
            Class<?> placementApi = loader.loadClass(templatePackage+"placement.TemplatePlacement");
            var treeConstructor = loader.loadClass(templatePackage+"placement.TreePlacement").getDeclaredConstructor();
            treeConstructor.setAccessible(true);
            Object treePlacement = treeConstructor.newInstance();
            Class<?> pasteConfig = loader.loadClass(templatePackage+"paste.PasteConfig");
            Class<?> decorators = loader.loadClass(templatePackage+"decorator.DecoratorConfig");
            Object decoratorConfig = decorators.getConstructor(List.class,Map.class).newInstance(List.of(),Map.of());
            Class<?> configType = loader.loadClass(featureName+"$Config");
            Object config = configType.getConstructor(List.class,placementApi,pasteConfig,decorators)
                    .newInstance(List.of(resource),treePlacement,pasteConfig.getField("DEFAULT").get(null),decoratorConfig);
            Class<?> pasteType = loader.loadClass(templatePackage+"paste.PasteType");
            Object worldGen = template.getClass().getField("WORLD_GEN").get(null);
            assertEquals(true,feature.getMethod("paste",WorldGenLevel.class,net.minecraft.util.RandomSource.class,
                    net.minecraft.core.BlockPos.class,net.minecraft.world.level.block.Mirror.class,
                    net.minecraft.world.level.block.Rotation.class,configType,pasteType)
                    .invoke(null,stamp,net.minecraft.util.RandomSource.create(1),new net.minecraft.core.BlockPos(0,64,0),
                            net.minecraft.world.level.block.Mirror.NONE,net.minecraft.world.level.block.Rotation.NONE,config,worldGen),
                    "released TemplateFeature must pass its original soil/clearance checks and paste into the isolated far stamp");
            assertTrue(stamp.placed().values().stream().anyMatch(state -> !state.isAir()),"original template writes must reach prediction");
            var mirror = feature.getDeclaredMethod("nextMirror", net.minecraft.util.RandomSource.class);mirror.setAccessible(true);
            assertNotNull(mirror.invoke(null, net.minecraft.util.RandomSource.create(1)));
            Class<?> poissonType = loader.loadClass(featureName.substring(0, featureName.indexOf("feature.template."))
                    + "feature.placement.poisson.FastPoissonModifier");
            Object poisson = poissonType.getConstructor(int.class,float.class,float.class,float.class,int.class,float.class)
                    .newInstance(7,1.0f,.8f,0.0f,32,.25f);
            var origin = new net.minecraft.core.BlockPos(-17,0,31);
            var placement = new net.minecraft.world.level.levelgen.placement.PlacementContext(level,
                    mock(net.minecraft.world.level.chunk.ChunkGenerator.class),Optional.empty());
            var original = Arrays.stream(poissonType.getDeclaredMethods()).filter(m -> m.getName().equals("getPositions")
                    && m.getParameterCount()==5 && m.getParameterTypes()[0]==int.class).findFirst().orElseThrow();
            original.setAccessible(true);
            try (var expected = (java.util.stream.Stream<?>) original.invoke(poisson,(int)level.getSeed()+234523,
                    level.getSeed(),Math.floorDiv(origin.getX(),16),Math.floorDiv(origin.getZ(),16),null);
                 var actual = (java.util.stream.Stream<?>) poissonType.getMethod("getPositions",
                         net.minecraft.world.level.levelgen.placement.PlacementContext.class,
                         net.minecraft.util.RandomSource.class,net.minecraft.core.BlockPos.class)
                         .invoke(poisson,placement,net.minecraft.util.RandomSource.create(1),origin)) {
                assertEquals(expected.toList(),actual.toList(),"virtual placement must execute the released Poisson algorithm unchanged");
            }
            assertThrows(UnsupportedOperationException.class, () -> level.featureTemplateBytes(ResourceLocation.parse("other:structures/tree.nbt")));
        }
    }

    private static ClientTerrainSampler sampler(JsonObject templates) {
        var sampler = mock(ClientTerrainSampler.class);
        when(sampler.profile()).thenReturn(new DimensionProfile(ResourceLocation.parse("minecraft:overworld"),
                1,-64,384,"noise","minecraft:overworld",1));
        when(sampler.structureTemplates()).thenReturn(templates);
        return sampler;
    }
}
