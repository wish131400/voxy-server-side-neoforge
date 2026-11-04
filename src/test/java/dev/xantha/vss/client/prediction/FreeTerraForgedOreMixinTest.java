package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import dev.xantha.vss.client.prediction.feature.FeatureStampLevel;
import dev.xantha.vss.testutil.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.placement.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named="vss.freeTerraForgedJar",matches=".+")
class FreeTerraForgedOreMixinTest {
    @Test void virtualNonOrePlacementSkipsOnlyTheIrrelevantServerOrePlan() throws Exception {
        TerrainPredictionFixtures.bootstrap();
        String target="etcodehome.freeterraforged.world.worldgen.feature.ore.DynamicOrePlacement";
        var harness=new VoxyMixinHarness(System.getProperty("vss.freeTerraForgedJar"),"""
                {"required":true,"minVersion":"0.8","package":"dev.xantha.vss.mixin.client",
                 "compatibilityLevel":"JAVA_21","mixins":["FreeTerraForgedPredictionOreMixin"]}
                """);
        byte[] transformed=harness.apply(target);
        class Loader extends java.net.URLClassLoader {
            Loader()throws Exception {super(new java.net.URL[]{java.nio.file.Path.of(
                    System.getProperty("vss.freeTerraForgedJar")).toUri().toURL()},FreeTerraForgedOreMixinTest.class.getClassLoader());}
            Class<?> define(){return defineClass(target,transformed,0,transformed.length);}
            Class<?> defineOriginal(byte[] original){return defineClass(target,original,0,original.length);}
        }
        try(var loader=new Loader()) {
            var type=loader.define();
            var activation=type.getDeclaredMethod("activation",PlacementModifier.class,PlacementContext.class);
            activation.setAccessible(true);
            var virtual=new FeatureStampLevel(42,TerrainPredictionFixtures.access(),Blocks.GRASS_BLOCK.defaultBlockState());
            assertThrows(UnsupportedOperationException.class,virtual::getLevel);
            var features=TerrainPredictionFixtures.access().registryOrThrow(net.minecraft.core.registries.Registries.PLACED_FEATURE);
            var flower=features.get(ResourceLocation.withDefaultNamespace("flower_default"));
            assertNotNull(flower);
            for(var feature:List.of(Optional.<PlacedFeature>empty(),Optional.of(flower))) {
                var context=new PlacementContext(virtual,TerrainPredictionFixtures.noise(),feature);
                assertEquals(Optional.empty(),activation.invoke(null,CountPlacement.of(7),context));
            }
            var ore=features.get(ResourceLocation.withDefaultNamespace("ore_coal_upper"));assertNotNull(ore);
            assertSame(net.minecraft.world.level.levelgen.feature.Feature.ORE,ore.feature().value().feature());
            var oreContext=new PlacementContext(virtual,TerrainPredictionFixtures.noise(),Optional.of(ore));
            var hook=dev.xantha.vss.mixin.client.FreeTerraForgedPredictionOreMixin.class.getDeclaredMethod(
                    "vss$nonOrePrediction",PlacementModifier.class,PlacementContext.class,
                    org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable.class);
            hook.setAccessible(true);
            var oreCallback=new org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Optional<?>>(
                    "activation",true);
            hook.invoke(null,CountPlacement.of(7),oreContext,oreCallback);
            assertFalse(oreCallback.isCancelled(),"Ore placement must retain the original FTF activation logic");
            var live=mock(WorldGenLevel.class);var end=mock(net.minecraft.server.level.ServerLevel.class);
            when(live.getLevel()).thenReturn(end);when(end.dimension()).thenReturn(Level.END);
            var liveContext=new PlacementContext(live,TerrainPredictionFixtures.noise(),Optional.of(flower));
            var liveCallback=new org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Optional<?>>(
                    "activation",true);
            hook.invoke(null,CountPlacement.of(7),liveContext,liveCallback);
            assertFalse(liveCallback.isCancelled(),"Live-world placement must retain the original FTF activation logic");
            try(var archive=new java.util.jar.JarFile(System.getProperty("vss.freeTerraForgedJar"));
                    var originalLoader=new Loader()) {
                byte[] original=archive.getInputStream(archive.getJarEntry(target.replace('.','/')+".class")).readAllBytes();
                var originalType=originalLoader.defineOriginal(original);
                var originalActivation=originalType.getDeclaredMethod("activation",PlacementModifier.class,PlacementContext.class);
                originalActivation.setAccessible(true);
                for(var context:List.of(oreContext,liveContext)) {
                    var modifier=CountPlacement.of(7);
                    Object expectedResult=null;
                    Throwable expectedFailure=null;
                    try {
                        expectedResult=originalActivation.invoke(null,modifier,context);
                    } catch(java.lang.reflect.InvocationTargetException expected) {
                        expectedFailure=expected.getCause();
                    }
                    if(expectedFailure==null) {
                        assertEquals(expectedResult,activation.invoke(null,modifier,context));
                    } else {
                        var actual=assertThrows(java.lang.reflect.InvocationTargetException.class,
                                ()->activation.invoke(null,modifier,context));
                        assertEquals(expectedFailure.getClass(),actual.getCause().getClass());
                    }
                }
            }
        }
    }
}
