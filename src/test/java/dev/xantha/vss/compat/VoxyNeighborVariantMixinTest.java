package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.testutil.VoxyMixinHarness;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

/** The newer six-acquireIfExists ABI applied to a released class, independent of a live game. */
class VoxyNeighborVariantMixinTest {
    @Test void nullableNeighborVariantStillRequiresAndTransformsAllSixDirections() throws Exception {
        var harness = new VoxyMixinHarness(System.getProperty("vss.voxyJar"), """
            {"required":true,"minVersion":"0.8","package":"dev.xantha.vss.mixin",
             "plugin":"dev.xantha.vss.mixin.voxy.VoxyPredictionPlugin","compatibilityLevel":"JAVA_17",
             "client":["voxy.PredictionVoxyNeighborSourceMixin"],"injectors":{"defaultRequire":1}}
            """);
        String target = "me.cortex.voxy.client.core.rendering.building.RenderDataFactory";
        var input = harness.getClassNode(target);
        int changed = 0;
        for (var method : input.methods) if (method.name.equals("acquireNeighborData"))
            for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("me/cortex/voxy/common/world/WorldEngine") && (call.name.equals("acquire") || call.name.equals("acquireIfExists"))
                    && call.desc.startsWith("(IIII)")) { call.name = "acquireIfExists"; changed++; }
        assertEquals(6, changed);
        var writer = new ClassWriter(0); input.accept(writer); harness.replaceClass(target, writer.toByteArray());
        var output = new ClassNode(); new ClassReader(harness.apply(target)).accept(output, 0);
        long redirected = output.methods.stream().filter(m -> m.name.equals("acquireNeighborData"))
                .flatMap(m -> java.util.stream.StreamSupport.stream(m.instructions.spliterator(), false))
                .filter(i -> i instanceof MethodInsnNode c && c.name.contains("vss$predictionNeighborIfExists")).count();
        assertEquals(6, redirected);
        for (var method : output.methods) new Analyzer<>(new BasicVerifier()).analyze(output.name, method);
    }
}
