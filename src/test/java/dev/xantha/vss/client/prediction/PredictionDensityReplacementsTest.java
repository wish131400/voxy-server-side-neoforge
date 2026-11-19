package dev.xantha.vss.client.prediction;

import dev.xantha.vss.api.worldgen.DensityFunctionReplacements;
import dev.xantha.vss.api.worldgen.DensityFunctionTraits;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class PredictionDensityReplacementsTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    @AfterEach void cleanup() {
        DensityFunctionReplacements.unregister(Unknown.class);
        DensityFunctionTraits.unregister(Unknown.class);
    }
    @Test void exactReplacementReceivesContextAndLeavesUnknownSiblingInJava() {
        var context = new DensityFunctionReplacements.Context(42,null,null);
        Unknown node = new Unknown();
        var sibling = new UnknownChild();
        DensityFunctionReplacements.register(Unknown.class,"test:arithmetic",2,(original, actual) -> {
            assertSame(context, actual);
            return DensityFunctions.constant(3);
        });
        DensityFunctionTraits.register(Unknown.class,DensityFunctionTraits.Traits.coordinate(true));
        var result = PredictionDensityReplacements.apply(context,DensityFunctions.add(node,sibling))[0];
        assertEquals(8,result.compute(new DensityFunction.SinglePointContext(0,0,0)));
        assertEquals(1,sibling.calls);
        assertTrue(DensityFunctionReplacements.identity().contains("test:arithmetic:2"));
    }
    @Test void rejectedOptimizationRollsBackAllRootsAndRuntimeOwnerIsNotVisited() {
        Unknown node = new Unknown();
        DensityFunctionReplacements.register(Unknown.class,"test:failure",1,(original, context) -> { throw new UnsupportedOperationException(); });
        DensityFunctionTraits.register(Unknown.class,DensityFunctionTraits.Traits.coordinate(true));
        var roots = PredictionDensityReplacements.apply(new DensityFunctionReplacements.Context(0,null,null),node,node);
        assertSame(node,roots[0]); assertSame(node,roots[1]);
        DensityFunctionTraits.register(Unknown.class,DensityFunctionTraits.Traits.runtimeOwned());
        assertSame(node,PredictionDensityReplacements.apply(new DensityFunctionReplacements.Context(0,null,null),node)[0]);
    }
    static class Unknown implements DensityFunction.SimpleFunction {
        int calls;
        public double compute(FunctionContext context) { calls++; return 3; }
        public double minValue() { return -10; }
        public double maxValue() { return 10; }
        public KeyDispatchDataCodec<? extends DensityFunction> codec() { throw new UnsupportedOperationException(); }
    }
    static class UnknownChild extends Unknown { public double compute(FunctionContext context) { calls++; return 5; } }
}
