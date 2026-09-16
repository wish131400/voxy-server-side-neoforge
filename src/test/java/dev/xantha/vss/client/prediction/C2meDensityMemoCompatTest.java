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
                    case "minValue", "maxValue", "compute" -> 7.0;
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            });
            var entryType = loader.loadClass("com.ishland.c2me.opts.dfc.common.gen.jvm.CompiledEntry");
            var evaluations = new java.util.concurrent.atomic.AtomicInteger();
            Object entry = Proxy.newProxyInstance(loader, new Class<?>[]{entryType}, (proxy, method, args) -> {
                return switch (method.getName()) {
                    case "getArgs" -> new Object[]{cache};
                    case "newInstance" -> proxy;
                    case "evalSingle" -> { evaluations.incrementAndGet(); yield 7.0; }
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            });
            var compiledType = loader.loadClass("com.ishland.c2me.opts.dfc.common.gen.jvm.CompiledDensityFunction");
            var compiled = (DensityFunction) compiledType.getConstructor(entryType, DensityFunction.class)
                    .newInstance(entry, delegate);
            var failure = assertThrows(UnsupportedOperationException.class,
                    () -> compiled.mapAll(function -> DensityFunctions.constant(0)));
            assertEquals("Unsupported transformation on Wrapping node", failure.getMessage());
            var roots = DensityMemo.wrapRoots(DensityFunctions.constant(1), compiled);
            assertSame(compiled, roots[1], "keep the already compiled evaluator");
            var point = new DensityFunction.SinglePointContext(-17, 80, 35);
            assertEquals(7.0, roots[1].compute(point));
            assertEquals(7.0, roots[1].compute(point));
            assertEquals(2, evaluations.get(), "VSS must not replace C2ME's runtime cache ownership");
        }
    }
}
