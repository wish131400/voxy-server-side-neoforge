package dev.xantha.vss.client.prediction;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL45;

/** Normal Voxy's source raster convention and Minecraft's destination projection are independent. */
public final class PredictionNormalDepthBridge {
    private PredictionNormalDepthBridge() { }

    public static boolean sourceZeroToOne() {
        return GL11.glGetInteger(GL45.GL_CLIP_DEPTH_MODE) == GL45.GL_ZERO_TO_ONE;
    }

    public static String patch(String path, String source) {
        if (!"voxy:post/blit_texture_depth_cutout.frag".equals(path) || source == null
                || source.contains("VssNormalDepthBridge")) return source;
        String unproject = "vec4(SCREEN2NDC(clip),1.0f)";
        String clamp = "depth = REDUCTION2(FAR+CLOSER_SIGN*(2.0f/((1<<24)-1)), depth);";
        String project = "depth = NDC2SCREEN_DEPTH(depth);";
        // Only the known normal final-blit contract is changed. Shader packs
        // and unknown layouts retain their own projection conventions.
        if (!source.contains("vec3 rev3d(vec3 clip)") || !source.contains(unproject)
                || !source.contains(clamp) || !source.contains(project)
                || !source.contains("depth = projDepth(point);")) return source;
        return source.replace("vec3 rev3d(vec3 clip)", """
                uniform bool VssNormalDepthBridge;
                uniform vec2 VssSourceDepthTransform;
                uniform bool VssPredictionBeforeWater;
                uniform sampler2D VssPredictionOpaqueDepth;
                uniform sampler2D VssPredictionSeedDepth;
                vec3 rev3d(vec3 clip)""")
                .replace(unproject, "vec4(VssNormalDepthBridge ? vec3(clip.xy * 2.0 - 1.0, "
                        + "clip.z * VssSourceDepthTransform.x + VssSourceDepthTransform.y) : SCREEN2NDC(clip), 1.0)")
                .replace("depth = projDepth(point);", """
                        if (VssPredictionBeforeWater) {
                            float predicted = texelFetch(VssPredictionOpaqueDepth, ivec2(gl_FragCoord.xy), 0).r;
                            float seed = texelFetch(VssPredictionSeedDepth, ivec2(gl_FragCoord.xy), 0).r;
                            float distance = (projMat * vec4(point, 1.0)).w;
                            // Reversed prediction depth retains precision past the vanilla far plane.
                            // Equal-depth real geometry wins; a closer predicted foreground is kept.
                            float realNear = 1.0 / max(distance - 0.02, 0.0001);
                            // Seeded vanilla depth is quantized; it is not a newly drawn prediction.
                            // Only pixels actually overwritten by prediction may reject this final blit.
                            if (predicted > seed && predicted > realNear + max(2e-7, realNear * 2e-4)) discard;
                        }
                        depth = projDepth(point);
                        """)
                .replace(clamp, "if (!VssNormalDepthBridge) { " + clamp + " }")
                .replace(project, """
                        // Normal Minecraft 1.21.1's destination projection is [-1,1],
                        // independently of the source Voxy projection or GL clip mode.
                        depth = VssNormalDepthBridge
                                ? clamp(depth * 0.5 + 0.5, 0.0, 1.0 - 1.0 / 16777215.0)
                                : NDC2SCREEN_DEPTH(depth);
                        """);
    }

    public static void bind(boolean sourceZeroToOne) {
        int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int enabled = GL20.glGetUniformLocation(program, "VssNormalDepthBridge");
        if (enabled < 0) return;
        GL20.glUniform1i(enabled, 1);
        GL20.glUniform2f(GL20.glGetUniformLocation(program, "VssSourceDepthTransform"),
                sourceZeroToOne ? 1 : 2, sourceZeroToOne ? 0 : -1);
    }
}
