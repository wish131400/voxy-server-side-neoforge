package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.testutil.*;
import java.io.*;
import java.net.*;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Reproduces the released FTF count wrapper, then runs the production density sampler through it. */
@EnabledIfSystemProperty(named="vss.freeTerraForgedJar",matches=".+")
class FreeTerraForgedFarPlacementTest {
    @Test void releasedDynamicOreMixinCannotBreakTemplateCountInitialization() throws Exception {
        TerrainPredictionFixtures.bootstrap();
        String jar=System.getProperty("vss.freeTerraForgedJar");
        var harness=new VoxyMixinHarness(jar,"""
                {"required":true,"minVersion":"0.8","package":"etcodehome.freeterraforged.mixin",
                 "compatibilityLevel":"JAVA_21","mixins":["MixinRepeatingPlacement"]}
                """);
        String placement="net.minecraft.world.level.levelgen.placement.";
        String helper="dev.xantha.vss.client.prediction.PredictionFarPlacementCount";
        byte[] transformed=harness.apply(placement+"RepeatingPlacement");
        class Loader extends URLClassLoader {
            Loader() throws Exception { super(new URL[]{Path.of(jar).toUri().toURL()},getClassLoader()); }
            @Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException {
                boolean isolated=name.startsWith(helper) || name.startsWith("etcodehome.freeterraforged.")
                    || Set.of(placement+"RepeatingPlacement",placement+"CountPlacement",
                              placement+"NoiseBasedCountPlacement",placement+"NoiseThresholdCountPlacement").contains(name);
                if(!isolated)return super.loadClass(name,resolve);
                synchronized(getClassLoadingLock(name)) {
                    Class<?> loaded=findLoadedClass(name);
                    if(loaded==null) {
                        if(name.startsWith("etcodehome.freeterraforged."))loaded=findClass(name);
                        else try(InputStream input=getClassLoader().getResourceAsStream(name.replace('.','/')+".class")) {
                            byte[] bytes=name.equals(placement+"RepeatingPlacement")?transformed:input.readAllBytes();
                            loaded=defineClass(name,bytes,0,bytes.length);
                        } catch(IOException failure) { throw new ClassNotFoundException(name,failure); }
                    }
                    if(resolve)resolveClass(loaded);
                    return loaded;
                }
            }
        }
        try(var loader=new Loader()) {
            var type=loader.loadClass(placement+"CountPlacement");
            var modifier=(PlacementModifier)type.getMethod("of",int.class).invoke(null,7);
            assertThrows(NullPointerException.class,()->modifier.getPositions(null,RandomSource.create(42),BlockPos.ZERO).count(),
                    "The released FTF wrapper must reproduce the live null-context failure");
            var sample=loader.loadClass(helper).getDeclaredMethod("sample",PlacementModifier.class,RandomSource.class,BlockPos.class);
            sample.setAccessible(true);
            assertEquals(7,sample.invoke(null,modifier,RandomSource.create(42),BlockPos.ZERO),
                    "Template density must bypass the live-world wrapper and retain its nominal count");
        }
    }
    private static ClassLoader getClassLoader() { return FreeTerraForgedFarPlacementTest.class.getClassLoader(); }
}
