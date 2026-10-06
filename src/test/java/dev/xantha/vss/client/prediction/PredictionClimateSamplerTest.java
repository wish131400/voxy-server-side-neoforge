package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PredictionClimateSamplerTest {
    @BeforeAll static void bootstrap() { DensityMemoBenchTest.bootstrap(); }

    static void awaitCompiled(PredictionClimateSampler sampler) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!sampler.diagnostics().startsWith("state=ready") && System.nanoTime() < deadline)
            Thread.sleep(5);
        assertTrue(sampler.diagnostics().startsWith("state=ready"), sampler.diagnostics());
    }

    static DensityFunction[] roots(Climate.Sampler source) {
        return new DensityFunction[]{source.temperature(), source.humidity(), source.continentalness(),
                source.erosion(), source.depth(), source.weirdness()};
    }

    @Test void preservesRawRootsAndQuantizedClimateBeforeAndAfterCompilation() throws Exception {
        try (var context = DensityMemoBenchTest.sampler(LithostitchedNativeTest.document(), 42);
             var optimized = new PredictionClimateSampler(context.randomStateContext().sampler())) {
            var source = context.randomStateContext().sampler();
            assertEquals("state=deferred", optimized.diagnostics());
            assertSame(source.spawnTarget(), optimized.sampler().spawnTarget());
            assertEquals(source.sample(-5, 17, 9), optimized.sampler().sample(-5, 17, 9));
            awaitCompiled(optimized);
            var expected = roots(source);
            var actual = roots(optimized.sampler());
            for (int i = 0; i < 2048; i++) {
                int x = i * 1877 % 16001 - 8000, y = i % 128 - 32, z = i * 547 % 32003 - 16000;
                assertEquals(source.sample(x, y, z), optimized.sampler().sample(x, y, z));
                var point = new DensityFunction.SinglePointContext(x * 4, y * 4, z * 4);
                for (int root = 0; root < expected.length; root++)
                    assertEquals(Double.doubleToRawLongBits(expected[root].compute(point)),
                            Double.doubleToRawLongBits(actual[root].compute(point)), "root=" + root + " point=" + point);
            }
        }
    }

    @Test void concurrentQueriesKeepIndependentExactMemoValues() throws Exception {
        try (var context = DensityMemoBenchTest.sampler(LithostitchedNativeTest.document(), 13459);
             var optimized = new PredictionClimateSampler(context.randomStateContext().sampler())) {
            var source = context.randomStateContext().sampler();
            optimized.sampler().sample(1, 2, 3);
            awaitCompiled(optimized);
            var executor = Executors.newFixedThreadPool(4);
            try {
                var tasks = new java.util.ArrayList<java.util.concurrent.Callable<Void>>();
                for (int worker = 0; worker < 4; worker++) {
                    int offset = worker * 65537;
                    tasks.add(() -> {
                        for (int i = 0; i < 1024; i++) {
                            int x = offset + i % 32 - 16, y = i % 96 - 16, z = -offset + i / 32;
                            assertEquals(source.sample(x, y, z), optimized.sampler().sample(x, y, z));
                        }
                        return null;
                    });
                }
                for (var future : executor.invokeAll(tasks)) future.get();
            } finally { executor.shutdownNow(); }
        }
    }

    @Test void statefulModRootsRemainDynamicAndRetirementCancelsCompilation() {
        var mutable = new Mutable();
        var root = DensityFunctions.add(DensityFunctions.constant(2), mutable);
        var source = new Climate.Sampler(root, root, root, root, root, root, List.of());
        var optimized = new PredictionClimateSampler(source);
        try {
            assertEquals(source.sample(1, 2, 3), optimized.sampler().sample(1, 2, 3));
            mutable.value = .75;
            assertEquals(source.sample(1, 2, 3), optimized.sampler().sample(1, 2, 3));
        } finally { optimized.close(); }
        assertEquals("state=retired", optimized.diagnostics());
        assertEquals(source.sample(4, 5, 6), optimized.sampler().sample(4, 5, 6));
        assertEquals("state=retired", optimized.diagnostics());
    }

    private static final class Mutable implements DensityFunction.SimpleFunction, DensityMemo.NonMemoizable {
        double value = .25;
        @Override public double compute(FunctionContext context) { return value; }
        @Override public double minValue() { return -100; }
        @Override public double maxValue() { return 100; }
        @Override public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() {
            throw new UnsupportedOperationException();
        }
    }
}
