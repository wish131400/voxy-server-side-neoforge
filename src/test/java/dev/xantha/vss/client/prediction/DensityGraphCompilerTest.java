package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class DensityGraphCompilerTest {
    @BeforeAll static void bootstrap() { DensityMemoBenchTest.bootstrap(); }
    @AfterEach void clear() { System.clearProperty("vss.javaDensityCompiler"); }

    @Test void arithmeticAndRawCacheMarkersKeepEveryFloatingPointBit() throws Exception {
        var y = DensityFunctions.yClampedGradient(-64, 320, -3, 3);
        for (DensityFunction root : List.of(y.abs(), y.square(), y.cube(), y.halfNegative(), y.quarterNegative(), y.squeeze(),
                y.clamp(-.7, .8), DensityFunctions.add(y, y), DensityFunctions.mul(y, y), DensityFunctions.min(y, y),
                DensityFunctions.max(y, y), DensityFunctions.rangeChoice(y, -1, 1, DensityFunctions.constant(7), y),
                DensityFunctions.cache2d(y), DensityFunctions.flatCache(y), DensityFunctions.cacheOnce(y),
                DensityFunctions.interpolated(y), DensityFunctions.cacheAllInCell(y))) {
            var compiled = DensityGraphCompiler.compile(DensityMemo.wrapRoots(root));
            for (int height = -80; height < 340; height++) {
                var context = point(-19, height, 31);
                bits(root.compute(context), compiled.compute(0, context));
            }
        }
        for (double value : new double[]{0.0, -0.0, Double.MIN_VALUE, -Double.MIN_VALUE,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.longBitsToDouble(0x7ff8000000000001L)}) {
            var constructor = DensityFunctions.constant(1).getClass().getDeclaredConstructor(double.class);
            constructor.setAccessible(true);
            var constant = (DensityFunction) constructor.newInstance(value);
            for (DensityFunction root : List.of(constant, constant.abs(), constant.square(), constant.cube(), constant.squeeze(),
                    constant.halfNegative(), constant.quarterNegative(), constant.clamp(-1, 1))) {
                var compiled = DensityGraphCompiler.compile(DensityMemo.wrapRoots(root));
                bits(root.compute(point(1, 2, 3)), compiled.compute(0, point(1, 2, 3)));
            }
        }
    }

    @Test void inactiveBranchesAreNeverEvaluatedAndUnknownNodesRemainOpaque() throws Exception {
        var zero = DensityFunctions.yClampedGradient(-1, 1, -1, 1);
        DensityFunction fails = new Opaque() {
            @Override public double compute(FunctionContext c) { throw new AssertionError("evaluated inactive branch"); }
        };
        for (var root : List.of(DensityFunctions.mul(zero, fails),
                DensityFunctions.rangeChoice(zero, -1, 1, DensityFunctions.constant(7), fails),
                DensityFunctions.min(DensityFunctions.constant(-2), fails),
                DensityFunctions.max(DensityFunctions.constant(2), fails))) {
            var compiled = DensityGraphCompiler.compile(DensityMemo.wrapRoots(root));
            bits(root.compute(point(0, 0, 0)), compiled.compute(0, point(0, 0, 0)));
        }
        var mutable = new Stateful();
        var root = DensityFunctions.add(DensityFunctions.constant(2), DensityFunctions.cacheOnce(mutable));
        var compiled = DensityGraphCompiler.compile(DensityMemo.wrapRoots(root));
        assertEquals(3, compiled.compute(0, point(0, 0, 0)));
        mutable.value = 7;
        assertEquals(9, compiled.compute(0, point(0, 0, 0)));
        assertEquals(0, compiled.metrics().specialized(), "stateful parents keep their entire original evaluator");
    }

    @Test void customBoundsAreReadAtEvaluationTime() throws Exception {
        var mutable = new Opaque();
        var root = DensityFunctions.min(DensityFunctions.yClampedGradient(0, 10, -2, 2), mutable);
        var compiled = DensityGraphCompiler.compile(root);
        var context = point(0, 0, 0);
        bits(root.compute(context), compiled.compute(0, context));
        mutable.value = -5;
        bits(root.compute(context), compiled.compute(0, context));
        assertEquals(-5, compiled.compute(0, context));
    }

    @Test void compilationNeverCallsUnprovenCustomBounds() throws Exception {
        var custom = new Opaque() {
            @Override public double minValue() { throw new AssertionError("unexpected compilation-time minValue"); }
            @Override public double maxValue() { throw new AssertionError("unexpected compilation-time maxValue"); }
        };
        var compiled = DensityGraphCompiler.compile(custom);
        assertEquals(1, compiled.compute(0, point(1, 2, 3)));
    }

    @Test void remappedRecordWithMissingAccessorReadsItsExactBackingField() throws Throwable {
        var writer = new org.objectweb.asm.ClassWriter(0);
        String name = "dev/xantha/vss/client/prediction/RemappedDensityRecord";
        writer.visit(org.objectweb.asm.Opcodes.V17, org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_FINAL
                | org.objectweb.asm.Opcodes.ACC_RECORD, name, null, "java/lang/Record", null);
        writer.visitRecordComponent("f_value_", "D", null).visitEnd();
        writer.visitField(org.objectweb.asm.Opcodes.ACC_PRIVATE | org.objectweb.asm.Opcodes.ACC_FINAL,
                "f_value_", "D", null, null).visitEnd();
        var ctor = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "<init>", "(D)V", null, null);
        ctor.visitCode(); ctor.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "java/lang/Record", "<init>", "()V", false);
        ctor.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0); ctor.visitVarInsn(org.objectweb.asm.Opcodes.DLOAD, 1);
        ctor.visitFieldInsn(org.objectweb.asm.Opcodes.PUTFIELD, name, "f_value_", "D");
        ctor.visitInsn(org.objectweb.asm.Opcodes.RETURN); ctor.visitMaxs(3, 3); ctor.visitEnd();
        writer.visitEnd();
        var type = java.lang.invoke.MethodHandles.lookup().defineHiddenClass(writer.toByteArray(), true).lookupClass();
        var part = type.getRecordComponents()[0];
        assertNull(part.getAccessor());
        Object record = type.getConstructor(double.class).newInstance(.375);
        assertEquals(.375, PredictionRawDensity.recordValue(record, part));
    }

    @Test void codeReuseHasIndependentNodesAndThreadScratch() throws Exception {
        var y = DensityFunctions.yClampedGradient(-64, 320, -3, 3);
        var root = DensityFunctions.add(y, DensityFunctions.constant(7));
        var first = DensityGraphCompiler.compile(DensityMemo.wrapRoots(root));
        var second = DensityGraphCompiler.compile(DensityMemo.wrapRoots(root));
        assertTrue(first.sharesCodeWith(second));
        var different = DensityGraphCompiler.compile(DensityMemo.wrapRoots(DensityFunctions.add(y, DensityFunctions.constant(8))));
        assertFalse(first.sharesCodeWith(different));
        var pool = Executors.newFixedThreadPool(4);
        try {
            var jobs = new ArrayList<java.util.concurrent.Future<?>>();
            for (int worker = 0; worker < 4; worker++) {
                int offset = worker * 1024;
                jobs.add(pool.submit(() -> {
                    for (int i = 0; i < 10000; i++) {
                        var context = point(offset + i / 128, i % 384 - 64, -offset - i / 64);
                        bits(root.compute(context), (i % 2 == 0 ? first : second).compute(0, context));
                    }
                }));
            }
            for (var job : jobs) job.get();
        } finally { pool.shutdownNow(); }
    }

    @Test void templateCacheCannotGrowWithTravelOrNewWorlds() throws Exception {
        for (int i = 0; i < 40; i++) {
            var root = DensityFunctions.add(DensityFunctions.yClampedGradient(-64, 320, -3, 3),
                    DensityFunctions.constant(i + .5));
            DensityGraphCompiler.compile(DensityMemo.wrapRoots(root));
            assertTrue(DensityGraphCompiler.cachedTemplates() <= DensityGraphCompiler.MAX_TEMPLATES);
            assertTrue(DensityGraphCompiler.cachedBytecodeBytes() <= DensityGraphCompiler.MAX_TEMPLATE_BYTES);
        }
    }

    @Test void lazyCompilationAndRetirementKeepBaselineAvailable() throws Exception {
        var root = DensityFunctions.add(DensityFunctions.yClampedGradient(-64, 320, -3, 3), DensityFunctions.constant(7));
        try (var compilation = new DensityCompilation(DensityMemo.wrapRoots(root))) {
            assertEquals("state=deferred", compilation.diagnostics());
            var wrapped = compilation.roots()[0];
            assertTrue(new PredictionRawDensity().inspect(wrapped).pure());
            bits(root.compute(point(3, 71, 7)), wrapped.compute(point(3, 71, 7)));
            awaitReady(compilation);
            bits(root.compute(point(-113, -45, 217)), wrapped.compute(point(-113, -45, 217)));
            compilation.close();
            assertEquals("state=retired", compilation.diagnostics());
            assertNull(compilation.metrics());
            bits(root.compute(point(17, 92, 35)), wrapped.compute(point(17, 92, 35)));
        }
        System.setProperty("vss.javaDensityCompiler", "off");
        try (var disabled = new DensityCompilation(root)) {
            assertSame(root, disabled.roots()[0]);
            assertEquals("state=disabled", disabled.diagnostics());
        }
    }

    @Test void retiringAQueuedWorldCancelsPublication() throws Exception {
        var entered = new CountDownLatch(1);
        var unblock = new CountDownLatch(1);
        var field = DensityCompilation.class.getDeclaredField("COMPILER"); field.setAccessible(true);
        var executor = (java.util.concurrent.ThreadPoolExecutor) field.get(null);
        var busy = executor.submit(() -> {
            entered.countDown();
            try { assertTrue(unblock.await(10, TimeUnit.SECONDS)); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            var root = DensityFunctions.add(DensityFunctions.yClampedGradient(-64, 320, -3, 3), DensityFunctions.constant(7));
            try (var queued = new DensityCompilation(DensityMemo.wrapRoots(root))) {
                queued.roots()[0].compute(point(1, 2, 3));
                assertEquals("state=queued", queued.diagnostics());
                queued.close();
                unblock.countDown();
                assertEquals("state=retired", queued.diagnostics());
                assertNull(queued.metrics());
            }
            busy.get(10, TimeUnit.SECONDS);
        } finally { unblock.countDown(); }
    }

    @Test void packagedBridgeHasTypedMinecraftCallsAndCompilerHasNoEmbeddedMappedMethodNames() throws Exception {
        var loader = DensityCompilerSupport.class.getClassLoader();
        try (var stream = loader.getResourceAsStream(DensityCompilerSupport.class.getName().replace('.', '/') + ".class")) {
            assertNotNull(stream);
            var methods = new ArrayList<String>();
            new org.objectweb.asm.ClassReader(stream).accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                @Override public org.objectweb.asm.MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode, String owner, String method, String desc, boolean isInterface) {
                            if (owner.startsWith("net/minecraft/")) methods.add(owner + "/" + method + desc);
                        }
                    };
                }
            }, 0);
            assertEquals(6, methods.size());
        }
        assertEquals(2, DensityCompilerSupport.compute(DensityFunctions.constant(2), point(1, 2, 3)));
        assertEquals(-2, DensityCompilerSupport.gradient(point(0, -64, 0), -64, 320, -2, 2));
        assertEquals(1, DensityCompilerSupport.clamp(3, -1, 1));
    }

    @Test void productionSamplerCompilesWithoutChangingSurfaceOrExactHeightEligibility() throws Exception {
        var doc = LithostitchedNativeTest.document();
        System.setProperty("vss.javaDensityCompiler", "off");
        try (var original = DensityMemoBenchTest.sampler(doc, 42)) {
            System.clearProperty("vss.javaDensityCompiler");
            try (var candidate = DensityMemoBenchTest.sampler(doc, 42)) {
                candidate.surfaceY(1, 2);
                var compilation = compilation(candidate);
                awaitReady(compilation);
                var cache = ClientTerrainSampler.class.getDeclaredField("cacheExactHeights");
                cache.setAccessible(true);
                assertTrue(cache.getBoolean(candidate));
                for (int i = 0; i < 256; i++) {
                    int x = -2048 + i % 16 * 8, z = 1972 + i / 16 * 8;
                    assertEquals(original.sampleSurface(x, z), candidate.sampleSurface(x, z));
                }
            }
        }
    }

    static DensityCompilation compilation(ClientTerrainSampler sampler) throws ReflectiveOperationException {
        Field field = ClientTerrainSampler.class.getDeclaredField("densityCompilation");
        field.setAccessible(true);
        return (DensityCompilation) field.get(sampler);
    }

    static void awaitReady(DensityCompilation compilation) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (compilation.metrics() == null && System.nanoTime() < deadline) Thread.sleep(5);
        assertNotNull(compilation.metrics(), compilation.diagnostics());
        assertTrue(compilation.diagnostics().startsWith("state=ready"), compilation.diagnostics());
    }

    private static DensityFunction.SinglePointContext point(int x, int y, int z) {
        return new DensityFunction.SinglePointContext(x, y, z);
    }
    private static void bits(double expected, double actual) {
        assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual));
    }
    private static class Opaque implements DensityFunction.SimpleFunction {
        double value = 1;
        @Override public double compute(FunctionContext context) { return value; }
        @Override public double minValue() { return value; }
        @Override public double maxValue() { return value; }
        @Override public KeyDispatchDataCodec<? extends DensityFunction> codec() { throw new UnsupportedOperationException(); }
    }
    private static final class Stateful extends Opaque implements DensityMemo.NonMemoizable { }
}
