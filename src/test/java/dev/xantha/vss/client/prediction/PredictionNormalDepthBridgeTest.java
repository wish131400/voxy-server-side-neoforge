package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PredictionNormalDepthBridgeTest {
    private static final String PATH = "voxy:post/blit_texture_depth_cutout.frag";
    private static final String LEGACY = """
            vec3 rev3d(vec3 clip) {
                vec4 view = invProjMat * vec4(clip*2.0f-1.0f,1.0f);
                return view.xyz/view.w;
            }
            void main() {
                depth = projDepth(point);
                depth = min(1.0f-(2.0f/((1<<24)-1)), depth);
                depth = depth * 0.5f + 0.5f;
            }
            """;

    @Test void installedLegacyContractReceivesForegroundAndSourceConventionBridge() {
        String result = PredictionNormalDepthBridge.patch(PATH, LEGACY);
        assertTrue(result.contains("uniform bool VssNormalDepthBridge;"));
        assertTrue(result.contains("VssSourceDepthTransform.x"));
        assertFalse(result.contains("VssPredictionDepth"));
        assertTrue(result.contains("1.0 - 1.0 / 16777215.0"));
        assertFalse(result.contains("SCREEN2NDC"));
        assertFalse(result.contains("NDC2SCREEN_DEPTH"));
        assertSame(result, PredictionNormalDepthBridge.patch(PATH, result));
    }

    @Test void modernContractKeepsItsDisabledFallbackAndPatchesOnce() {
        String modern = LEGACY.replace("vec4(clip*2.0f-1.0f,1.0f)", "vec4(SCREEN2NDC(clip),1.0f)")
                .replace("depth = min(1.0f-(2.0f/((1<<24)-1)), depth);",
                        "depth = REDUCTION2(FAR+CLOSER_SIGN*(2.0f/((1<<24)-1)), depth);")
                .replace("depth = depth * 0.5f + 0.5f;", "depth = NDC2SCREEN_DEPTH(depth);");
        String result = PredictionNormalDepthBridge.patch(PATH, modern);
        assertTrue(result.contains("VssNormalDepthBridge"));
        assertTrue(result.contains("SCREEN2NDC(clip)"));
        assertTrue(result.contains("NDC2SCREEN_DEPTH(depth)"));
        assertSame(result, PredictionNormalDepthBridge.patch(PATH, result));
    }

    @Test void unrelatedAndIncompleteContractsRemainUnmodified() {
        assertNull(PredictionNormalDepthBridge.patch(PATH, null));
        assertSame(LEGACY, PredictionNormalDepthBridge.patch("shaderpack:water.frag", LEGACY));
        for (String marker : new String[]{"vec3 rev3d(vec3 clip)", "vec4(clip*2.0f-1.0f,1.0f)",
                "depth = min(1.0f-(2.0f/((1<<24)-1)), depth);", "depth = depth * 0.5f + 0.5f;",
                "depth = projDepth(point);"}) {
            String unknown = LEGACY.replace(marker, "custom_shader_expression");
            assertSame(unknown, PredictionNormalDepthBridge.patch(PATH, unknown));
        }
    }
}
