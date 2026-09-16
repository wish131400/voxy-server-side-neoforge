package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class DensityMemoCompatTest {
    @BeforeAll static void bootstrap() {
        ClientTerrainSamplerTest.bootstrapMinecraft();
    }

    @Test void unsupportedTransformationKeepsAllOriginalRoots() {
        var compiled = new RestrictedFunction();
        DensityFunction ordinary = DensityFunctions.constant(3);
        DensityFunction[] roots = {ordinary, compiled, null};
        var result = DensityMemo.wrapRoots(roots);
        // The counting pass is accepted; rejection happens during rewriting,
        // after the preceding root has already been wrapped.
        assertSame(roots, result);
        assertSame(ordinary, result[0]);
        assertSame(compiled, result[1]);
        assertNull(result[2]);
        var point = new DensityFunction.SinglePointContext(-17, 70, 33);
        assertEquals(53, result[1].compute(point));
        compiled.offset = 9;
        assertEquals(62, result[1].compute(point), "fallback must retain external cache/state ownership");
    }

    @Test void opaqueNodeCanAlsoRejectTheCountingPass() {
        DensityFunction original = new RestrictedFunction() {
            @Override public DensityFunction mapAll(Visitor visitor) {
                throw new UnsupportedOperationException("opaque graph");
            }
        };
        assertSame(original, DensityMemo.wrapRoots(original)[0]);
    }

    @Test void unexpectedTransformationBugsAreNotSilentlySwallowed() {
        var failure = new IllegalStateException("broken graph");
        DensityFunction original = new RestrictedFunction() {
            @Override public DensityFunction mapAll(Visitor visitor) { throw failure; }
        };
        assertSame(failure, assertThrows(IllegalStateException.class, () -> DensityMemo.wrapRoots(original)));
    }

    private static class RestrictedFunction implements DensityFunction.SimpleFunction {
        double offset;
        @Override public DensityFunction mapAll(Visitor visitor) {
            if (visitor.apply(this) != this) {
                throw new UnsupportedOperationException("Unsupported transformation on Wrapping node");
            }
            return this;
        }
        @Override public double compute(FunctionContext context) { return context.blockX() + context.blockY() + offset; }
        @Override public double minValue() { return -Double.MAX_VALUE; }
        @Override public double maxValue() { return Double.MAX_VALUE; }
        @Override public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            throw new UnsupportedOperationException("runtime node");
        }
    }
}
