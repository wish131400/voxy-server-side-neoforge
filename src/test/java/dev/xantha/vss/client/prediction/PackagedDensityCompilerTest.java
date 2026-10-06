package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Loads the release JAR in isolation; Forge also uses the real SRG Minecraft binary. */
@EnabledIfSystemProperty(named = "vss.packagedJar", matches = ".+")
class PackagedDensityCompilerTest {
    @Test void releaseJarGeneratesAndRunsDensityBytecodeWithRuntimeMethodNames() throws Exception {
        Path artifact = Path.of(System.getProperty("vss.packagedJar"));
        assertTrue(Files.isRegularFile(artifact));
        String minecraft = System.getProperty("vss.srgMinecraft");
        Map<String, String> names = new HashMap<>();
        if (minecraft != null) {
            String current = "";
            for (String line : Files.readAllLines(Path.of(System.getProperty("vss.srgMappings")))) {
                if (!line.startsWith("\t")) current = line.split(" ")[0].replace('/', '.');
                else if (!line.startsWith("\t\t")) {
                    String[] columns = line.trim().split(" ");
                    if (columns.length == 3) names.put(current + "/" + columns[0] + columns[1], columns[2]);
                }
            }
        } else ClientTerrainSamplerTest.bootstrapMinecraft();
        URL[] urls = minecraft == null ? new URL[]{artifact.toUri().toURL()}
                : new URL[]{artifact.toUri().toURL(), Path.of(minecraft).toUri().toURL()};
        try (var loader = new URLClassLoader(urls, getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                boolean isolated = name.startsWith("dev.xantha.vss.client.prediction.Density")
                        || name.equals("dev.xantha.vss.client.prediction.PredictionRawDensity")
                        || name.startsWith("dev.xantha.vss.client.prediction.PredictionRawDensity$")
                        || name.equals("dev.xantha.vss.client.prediction.PredictionClimateSampler")
                        || minecraft != null && name.startsWith("net.minecraft.");
                if (!isolated) return super.loadClass(name, resolve);
                synchronized (getClassLoadingLock(name)) {
                    Class<?> found = findLoadedClass(name);
                    if (found == null) found = findClass(name);
                    if (resolve) resolveClass(found);
                    return found;
                }
            }
        }) {
            if (minecraft != null) {
                method(loader.loadClass("net.minecraft.SharedConstants"), names, "tryDetectVersion").invoke(null);
                method(loader.loadClass("net.minecraft.server.Bootstrap"), names, "bootStrap").invoke(null);
            }
            var function = loader.loadClass("net.minecraft.world.level.levelgen.DensityFunction");
            var functions = loader.loadClass("net.minecraft.world.level.levelgen.DensityFunctions");
            var context = loader.loadClass(function.getName() + "$FunctionContext");
            var point = loader.loadClass(function.getName() + "$SinglePointContext");
            var factory = point.getConstructor(int.class, int.class, int.class);
            Object gradient = method(functions, names, "yClampedGradient", int.class, int.class, double.class, double.class)
                    .invoke(null, -64, 320, -3.0, 3.0);
            Object constant = method(functions, names, "constant", double.class).invoke(null, 7.0);
            Object sum = method(functions, names, "add", function, function).invoke(null, gradient, constant);
            Object clamped = method(function, names, "clamp", double.class, double.class).invoke(sum, -1.0, 1.0);
            Object opaque = Proxy.newProxyInstance(loader, new Class<?>[]{function}, (proxy, call, args) -> {
                String name = call.getName();
                if (name.equals(mapped(names, function, "compute", "(L" + context.getName().replace('.', '/') + ";)D")))
                    return (double) (int) method(context, names, "blockY").invoke(args[0]) / 17;
                if (name.equals(mapped(names, function, "minValue", "()D"))) return -100.0;
                if (name.equals(mapped(names, function, "maxValue", "()D"))) return 100.0;
                var visitor = loader.loadClass(function.getName() + "$Visitor");
                if (name.equals(mapped(names, function, "mapAll", "(L" + visitor.getName().replace('.', '/') + ";)L"
                        + function.getName().replace('.', '/') + ";")))
                    return method(visitor, names, "apply", function).invoke(args[0], proxy);
                if (name.equals("toString")) return "opaque runtime density";
                if (name.equals("hashCode")) return System.identityHashCode(proxy);
                if (name.equals("equals")) return proxy == args[0];
                throw new UnsupportedOperationException(name);
            });
            Object custom = method(functions, names, "min", function, function).invoke(null, sum, opaque);
            Object range = method(functions, names, "rangeChoice", function, double.class, double.class, function, function)
                    .invoke(null, gradient, -1.0, 1.0, clamped, custom);
            Object roots = Array.newInstance(function, 2);
            Array.set(roots, 0, range); Array.set(roots, 1, gradient);
            var memoType = loader.loadClass("dev.xantha.vss.client.prediction.DensityMemo");
            var wrap = memoType.getDeclaredMethod("wrapRoots", roots.getClass()); wrap.setAccessible(true);
            Object memoRoots = wrap.invoke(null, roots);
            var compiler = loader.loadClass("dev.xantha.vss.client.prediction.DensityGraphCompiler");
            var compile = compiler.getDeclaredMethod("compile", roots.getClass());
            compile.setAccessible(true);
            Object generated = compile.invoke(null, memoRoots);
            var compute = generated.getClass().getDeclaredMethod("compute", int.class, context);
            compute.setAccessible(true);
            var original = method(function, names, "compute", context);
            for (int y = -96; y < 352; y++) {
                Object query = factory.newInstance(-37, y, 113);
                for (int root = 0; root < 2; root++) {
                    double expected = (double) original.invoke(Array.get(roots, root), query);
                    double actual = (double) compute.invoke(generated, root, query);
                    assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual));
                }
            }
            var compilationType = loader.loadClass("dev.xantha.vss.client.prediction.DensityCompilation");
            var constructor = compilationType.getDeclaredConstructor(roots.getClass());
            constructor.setAccessible(true);
            var compilation = (AutoCloseable) constructor.newInstance(memoRoots);
            try {
                var readRoots = compilationType.getDeclaredMethod("roots"); readRoots.setAccessible(true);
                var diagnostics = compilationType.getDeclaredMethod("diagnostics"); diagnostics.setAccessible(true);
                Object wrapped = readRoots.invoke(compilation);
                original.invoke(Array.get(wrapped, 0), factory.newInstance(1, 2, 3));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (!diagnostics.invoke(compilation).toString().startsWith("state=ready") && System.nanoTime() < deadline)
                    Thread.sleep(5);
                assertTrue(diagnostics.invoke(compilation).toString().startsWith("state=ready"), diagnostics.invoke(compilation).toString());
                Object query = factory.newInstance(-17, 91, 35);
                assertEquals(original.invoke(range, query), original.invoke(Array.get(wrapped, 0), query));
            } finally { compilation.close(); }
            var climateType = loader.loadClass("net.minecraft.world.level.biome.Climate$Sampler");
            Object climateSource = climateType.getConstructor(function,function,function,function,function,function,
                    java.util.List.class).newInstance(sum,sum,sum,sum,sum,sum,java.util.List.of());
            var climateOptimizerType = loader.loadClass("dev.xantha.vss.client.prediction.PredictionClimateSampler");
            var climateConstructor = climateOptimizerType.getDeclaredConstructor(climateType);
            climateConstructor.setAccessible(true);
            var optimizedClimate = (AutoCloseable)climateConstructor.newInstance(climateSource);
            try {
                var readSampler = climateOptimizerType.getDeclaredMethod("sampler"); readSampler.setAccessible(true);
                var diagnostics = climateOptimizerType.getDeclaredMethod("diagnostics"); diagnostics.setAccessible(true);
                Object climateSampler = readSampler.invoke(optimizedClimate);
                var sample = method(climateType,names,"sample",int.class,int.class,int.class);
                assertEquals(sample.invoke(climateSource,1,2,3),sample.invoke(climateSampler,1,2,3));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (!diagnostics.invoke(optimizedClimate).toString().startsWith("state=ready") && System.nanoTime() < deadline)
                    Thread.sleep(5);
                assertTrue(diagnostics.invoke(optimizedClimate).toString().startsWith("state=ready"),
                        diagnostics.invoke(optimizedClimate).toString());
                for (int y=-32;y<96;y++)
                    assertEquals(sample.invoke(climateSource,-19,y,37),sample.invoke(climateSampler,-19,y,37));
                System.out.println("PACKAGED_CLIMATE_OK identical=true asyncReady=true");
            } finally { optimizedClimate.close(); }
            assertEquals(artifact.toRealPath(), Path.of(compiler.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath());
            System.out.println("PACKAGED_DENSITY_OK artifact=" + artifact.getFileName() + " runtime="
                    + (minecraft == null ? "official" : "SRG") + " identical=true asyncReady=true");
        }
    }

    private static String mapped(Map<String, String> names, Class<?> owner, String name, String descriptor) {
        return names.getOrDefault(owner.getName() + "/" + name + descriptor, name);
    }

    private static Method method(Class<?> owner, Map<String, String> names, String name, Class<?>... parameters) throws Exception {
        for (Method method : owner.getMethods()) {
            if (!java.util.Arrays.equals(method.getParameterTypes(), parameters)) continue;
            String descriptor = org.objectweb.asm.Type.getMethodDescriptor(method);
            if (method.getName().equals(mapped(names, owner, name, descriptor))) return method;
        }
        throw new NoSuchMethodException(owner.getName() + "/" + name);
    }
}
