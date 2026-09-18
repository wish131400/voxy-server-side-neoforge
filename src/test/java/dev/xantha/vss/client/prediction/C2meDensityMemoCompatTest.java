package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.file.Path;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Exercises mapAll from the installed C2ME DFC binary, without a production dependency. */
@EnabledIfSystemProperty(named = "vss.c2meDfcJar", matches = ".+")
class C2meDensityMemoCompatTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void compiledCacheRestrictionDoesNotPreventPredictionDensityInitialization() throws Exception {
        var jar = Path.of(System.getProperty("vss.c2meDfcJar"));
        try (var loader = new URLClassLoader(new java.net.URL[]{jar.toUri().toURL()}, getClass().getClassLoader())) {
            var cacheType = loader.loadClass("com.ishland.c2me.opts.dfc.common.ducks.IFastCacheLike");
            DensityFunction delegate = DensityFunctions.constant(7);
            Object cache = Proxy.newProxyInstance(loader, new Class<?>[]{cacheType}, (proxy, method, args) -> {
                return switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "test runtime cache";
                    case "c2me$getDelegate" -> delegate;
                    case "mapAll" -> ((DensityFunction.Visitor) args[0]).apply((DensityFunction) proxy);
                    case "minValue", "maxValue", "compute" -> 7.0;
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            });
            var entryType = loader.loadClass("com.ishland.c2me.opts.dfc.common.gen.jvm.CompiledEntry");
            var evaluations = new java.util.concurrent.atomic.AtomicInteger();
            var compiledType = loader.loadClass("com.ishland.c2me.opts.dfc.common.gen.jvm.CompiledDensityFunction");
            boolean modern = java.util.Arrays.stream(entryType.getMethods())
                    .anyMatch(method -> method.getName().equals("getRootsUnsafe"));
            Object subRoots;
            if (modern) {
                String api = "com.ishland.c2me.opts.dfc.common.gen.jvm.internalapi.";
                var singleType = loader.loadClass(api + "ISingleMethod");
                var multiType = loader.loadClass(api + "IMultiMethod");
                Object single = Proxy.newProxyInstance(loader, new Class<?>[]{singleType}, (proxy, method, args) -> {
                    evaluations.incrementAndGet(); return 7.0;
                });
                Object multi = Proxy.newProxyInstance(loader, new Class<?>[]{multiType}, (proxy, method, args) -> {
                    java.util.Arrays.fill((double[]) args[0], 7.0); return null;
                });
                var subType = loader.loadClass("com.ishland.c2me.opts.dfc.common.gen.jvm.SubCompiledDensityFunction");
                Object sub = subType.getConstructor(singleType, multiType, DensityFunction.class).newInstance(single, multi, delegate);
                subRoots = java.lang.reflect.Array.newInstance(subType, 1);
                java.lang.reflect.Array.set(subRoots, 0, sub);
            } else subRoots = null;
            Object entry = Proxy.newProxyInstance(loader, new Class<?>[]{entryType}, (proxy, method, args) -> {
                return switch (method.getName()) {
                    case "getArgs" -> new Object[]{cache};
                    case "getRootsUnsafe" -> subRoots;
                    case "newInstance" -> {
                        if (modern) {
                            var argumentVisitor = loader.loadClass("com.ishland.c2me.opts.dfc.common.gen.jvm.internalapi.ArgumentVisitor");
                            Object transformed = argumentVisitor.getMethod("apply", Object.class)
                                    .invoke(args[1], ((Object[]) args[0])[0]);
                            // Generated DfcCompiled constructors CHECKCAST every
                            // cache field after the real argument visitor runs.
                            cacheType.cast(transformed);
                        }
                        yield proxy;
                    }
                    case "evalSingle" -> { evaluations.incrementAndGet(); yield 7.0; }
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            });
            DensityFunction compiled;
            if (modern) {
                var constructor = compiledType.getDeclaredConstructor(int.class, DensityFunction.class);
                constructor.setAccessible(true);
                compiled = (DensityFunction) constructor.newInstance(0, delegate);
                compiledType.getMethod("initFrom", entryType).invoke(compiled, entry);
            } else compiled = (DensityFunction) compiledType.getConstructor(entryType, DensityFunction.class)
                    .newInstance(entry, delegate);
            if (modern) assertThrows(ClassCastException.class, () -> compiled.mapAll(function -> DensityFunctions.constant(0)));
            else assertThrows(UnsupportedOperationException.class, () -> compiled.mapAll(function -> DensityFunctions.constant(0)));
            var roots = DensityMemo.wrapRoots(DensityFunctions.constant(1), compiled);
            assertSame(compiled, roots[1], "keep the already compiled evaluator");
            var point = new DensityFunction.SinglePointContext(-17, 80, 35);
            assertEquals(7.0, roots[1].compute(point));
            assertEquals(7.0, roots[1].compute(point));
            assertEquals(2, evaluations.get(), "VSS must not replace C2ME's runtime cache ownership");
        }
    }
}
