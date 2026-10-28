package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.mixin.voxy.VoxyPredictionPlugin;
import dev.xantha.vss.testutil.VoxyMixinHarness;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

class VoxyUnsupportedBackendTest {
    @Test void incompleteSectionApiReceivesNoPartialRenderHooks() throws Exception {
        var harness = new VoxyMixinHarness(System.getProperty("vss.voxyJar"), """
            {"required":true,"minVersion":"0.8","package":"dev.xantha.vss.mixin",
             "plugin":"dev.xantha.vss.mixin.voxy.VoxyPredictionPlugin","compatibilityLevel":"JAVA_17",
             "client":["voxy.PredictionVoxyMeshSourceMixin","voxy.PredictionVoxyNeighborSourceMixin",
             "voxy.PredictionVoxyWorldEventMixin","voxy.PredictionVoxyDetailMixin"]}
            """);
        // Remove one required method from a real supported backend. A branch's source tree
        // can differ from its release, so this negative fixture must not label a release unsupported.
        String section = "me.cortex.voxy.common.world.WorldSection";
        var incomplete = harness.getClassNode(section);
        assertTrue(incomplete.methods.removeIf(m -> m.name.equals("_createRawUntrackedUnsafeSection")));
        var writer = new ClassWriter(0);
        incomplete.accept(writer);
        harness.replaceClass(section, writer.toByteArray());
        assertFalse(VoxyPredictionPlugin.supports(name -> harness.getClassNode(name)));
        for (String target : new String[]{"RenderGenerationService", "RenderDataFactory"}) {
            var output = new ClassNode();
            new ClassReader(harness.apply("me.cortex.voxy.client.core.rendering.building." + target)).accept(output, 0);
            assertFalse(output.methods.stream().anyMatch(m -> m.name.contains("vss$")));
        }
    }
}
