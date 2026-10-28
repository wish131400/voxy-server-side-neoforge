package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.mixin.voxy.VoxyPredictionPlugin;
import dev.xantha.vss.testutil.VoxyMixinHarness;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.MethodInsnNode;

class VoxyPredictionCapabilitiesTest {
    private VoxyMixinHarness jar() throws Exception {
        return new VoxyMixinHarness(System.getProperty("vss.voxyJar"), "{}");
    }
    @Test void missingRendererIsRejectedAsAWhole() throws Exception {
        var jar = jar();
        assertFalse(VoxyPredictionPlugin.supports(name -> {
            var node = jar.getClassNode(name);
            if (name.endsWith("RenderGenerationService")) node.methods.removeIf(m -> m.name.equals("acquireSection"));
            return node;
        }));
    }
    @Test void missingLiquidMesherHooksRejectsTheOverlayAsAWhole() throws Exception {
        for(String hook:new String[]{"prepareSectionData","packPartialQuadData","generateXFaces"}) {
            var jar=jar();
            assertFalse(VoxyPredictionPlugin.supports(name->{
                var node=jar.getClassNode(name);
                if(name.endsWith("/RenderDataFactory"))node.methods.removeIf(m->m.name.equals(hook));
                return node;
            }),hook);
        }
    }
    @Test void partialNeighborCoverageIsRejected() throws Exception {
        var jar = jar();
        assertFalse(VoxyPredictionPlugin.supports(name -> {
            var node = jar.getClassNode(name);
            if (name.endsWith("RenderDataFactory")) for (var method : node.methods) {
                if (!method.name.equals("acquireNeighborData")) continue;
                for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call
                        && call.owner.endsWith("/WorldEngine") && (call.name.equals("acquire") || call.name.equals("acquireIfExists"))) {
                    call.name = "unsupportedNeighbor";
                    break;
                }
            }
            return node;
        }));
    }
    @Test void missingWorldEventForwardingDoesNotLeaveAPartialOverlay() throws Exception {
        var jar = jar();
        assertFalse(VoxyPredictionPlugin.supports(name -> {
            var node = jar.getClassNode(name);
            if (name.endsWith("AsyncNodeManager")) node.methods.removeIf(m -> m.name.equals("worldEvent"));
            return node;
        }));
    }
    @Test void missingWaitLifecycleOrNodeUploadHooksRejectsTheOverlayAsAWhole() throws Exception {
        for(String required:new String[]{"writeNode","processChildChange","_recurseRemoveNode","submitRequestBatch"}) {
            var jar=jar();
            assertFalse(VoxyPredictionPlugin.supports(name->{
                var node=jar.getClassNode(name);
                if(name.endsWith(required.equals("submitRequestBatch")?"/AsyncNodeManager":"/NodeManager"))
                    node.methods.removeIf(m->m.name.equals(required));
                return node;
            }),required);
        }
    }
}
