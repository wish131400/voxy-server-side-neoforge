package dev.xantha.vss.client.prediction;

import dev.xantha.vss.compat.ModCompat;
import dev.xantha.vss.config.VSSClientConfig;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Hides Voxy's outward skirt only where resident prediction terrain can replace it. */
public final class PredictionVoxyBoundaryBridge {
    private static final String VERTEX = "voxy:lod/gl46/quads3.vert";
    private static final String FRAGMENT = "voxy:lod/gl46/quads.frag";
    private static final int[] EMPTY = new int[4];
    private static final Pattern INTER_DATA_DECLARATION = Pattern.compile(
            "layout\\s*\\(\\s*location\\s*=\\s*0\\s*\\)\\s*out\\s+flat\\s+uvec4\\s+interData\\s*;");
    private static final Pattern FRAGMENT_DATA_DECLARATION = Pattern.compile(
            "layout\\s*\\(\\s*location\\s*=\\s*0\\s*\\)\\s*in\\s+flat\\s+uvec4\\s+interData\\s*;");
    private static final Pattern MAIN_BODY = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*\\)\\s*\\{");
    private static final Pattern SETUP_QUAD = Pattern.compile(
            "setupQuad\\s*\\(\\s*quad\\s*,\\s*quadData\\s*\\[\\s*uint\\s*\\(\\s*gl_VertexID\\s*\\)\\s*>>\\s*2\\s*\\]\\s*,\\s*"
                    + "(?:pos|positionBuffer\\s*\\[\\s*gl_BaseInstance\\s*\\])\\s*,\\s*"
                    + "\\(\\s*gl_VertexID\\s*&\\s*3\\s*\\)\\s*==\\s*1\\s*\\)\\s*;");

    private PredictionVoxyBoundaryBridge() { }

    public static String patch(String path, String source) {
        if (source == null) return null;
        if (VERTEX.equals(path)) return patchVertex(source);
        if (FRAGMENT.equals(path)) return patchFragment(source);
        return source;
    }

    private static String patchVertex(String source) {
        if (source.contains("vssBoundaryCandidate")) return source;
        Matcher declaration = INTER_DATA_DECLARATION.matcher(source);
        if (!declaration.find()) return source;
        String patched = source.substring(0, declaration.end()) + "\n" + """
                layout(location = 8) out vec2 vssCameraRelativeXZ;
                layout(location = 9) out flat uint vssBoundaryCandidate;
                uniform bool VssBoundaryEnabled;
                uniform float VssBoundaryRadius;
                """ + source.substring(declaration.end());
        Matcher setup = SETUP_QUAD.matcher(patched);
        if (!setup.find()) {
            Matcher body = MAIN_BODY.matcher(patched);
            if (!body.find()) return source;
            return patched.substring(0, body.end())
                    + "\nvssCameraRelativeXZ = vec2(0.0);\nvssBoundaryCandidate = 0u;\n"
                    + patched.substring(body.end());
        }
        patched = patched.substring(0, setup.end()) + "\n" + """
                uint vssCornerId = uint(gl_VertexID) & 3u;
                vec2 vssCorner = vec2((vssCornerId >> 1u) & 1u, vssCornerId & 1u) * quad.lodScale;
                vssCameraRelativeXZ = (quad.basePoint +
                        swizzelDataAxis(quad.axis, vec3(quad.quadSizeAddin * vssCorner, 0.0))).xz - cameraSubPos.xz;
                // Bit 1 validates coordinates; bit 0 marks an outward skirt. Unknown layouts set neither.
                vssBoundaryCandidate = 2u;
                if (VssBoundaryEnabled) {
                    uint vssFace = extractFace(quadData[uint(gl_VertexID)>>2]);
                    if (vssFace >= 2u && vssFace <= 5u) {
                        vec3 vssCenter3 = quad.basePoint +
                                swizzelDataAxis(quad.axis, vec3(quad.quadSizeAddin * quad.lodScale * 0.5, 0.0));
                        vec2 vssRelative = vssCenter3.xz - cameraSubPos.xz;
                        float vssDistance = length(vssRelative);
                        float vssHalfExtent = max(192.0,
                                max(quad.quadSizeAddin.x, quad.quadSizeAddin.y) * quad.lodScale * 0.75);
                        if (abs(vssDistance - VssBoundaryRadius) <= vssHalfExtent) {
                            vec2 vssNormal = vssFace == 2u ? vec2(0.0, -1.0) :
                                    vssFace == 3u ? vec2(0.0, 1.0) :
                                    vssFace == 4u ? vec2(-1.0, 0.0) : vec2(1.0, 0.0);
                            if (dot(vssRelative, vssNormal) > VssBoundaryRadius * 0.35) {
                                vssBoundaryCandidate = 3u;
                            }
                        }
                    }
                }
                """ + patched.substring(setup.end());
        return patched;
    }

    private static String patchFragment(String source) {
        if (source.contains("VssBoundaryCoverage")) return source;
        Matcher declaration = FRAGMENT_DATA_DECLARATION.matcher(source);
        if (!declaration.find() || !MAIN_BODY.matcher(source).find()) return source;
        String patched = source.substring(0, declaration.end()) + "\n" + """
                layout(location = 8) in vec2 vssCameraRelativeXZ;
                layout(location = 9) in flat uint vssBoundaryCandidate;
                uniform uvec4 VssBoundaryCoverage;
                uniform float VssBoundaryRadius;
                """ + source.substring(declaration.end());
        Matcher body = MAIN_BODY.matcher(patched);
        if (!body.find()) return source;
        return patched.substring(0, body.end()) + "\n" + """
                vec2 vssRelative = vssCameraRelativeXZ;
                if ((vssBoundaryCandidate & 1u) != 0u) {
                    // A merged wall may span the handoff: its inner pixels still belong to Voxy.
                    float vssInnerRadius = max(0.0, VssBoundaryRadius - 16.0);
                    if (dot(vssRelative, vssRelative) >= vssInnerRadius * vssInnerRadius) {
                        uint vssSector = min(127u, uint(floor((atan(vssRelative.y, vssRelative.x)
                                + 3.141592653589793) * (128.0 / 6.283185307179586))));
                        if ((VssBoundaryCoverage[int(vssSector >> 5u)] &
                             (1u << (vssSector & 31u))) != 0u) discard;
                    }
                }
                """ + patched.substring(body.end());
    }

    /** Called with Voxy's opaque terrain program bound, before its indirect draw. */
    public static void bind() {
        int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        if (program <= 0) return;
        Minecraft minecraft = Minecraft.getInstance();
        int enabledLocation = GL20.glGetUniformLocation(program, "VssBoundaryEnabled");
        if (enabledLocation < 0) return;
        int chunks = ModCompat.getVoxyViewDistanceChunks().orElse(0);
        boolean enabled = VSSClientConfig.CONFIG.enablePrediction && minecraft.level != null
                && minecraft.player != null && chunks > 0;
        int[] coverage = enabled
                ? PredictionRenderer.voxyBoundaryCoverage(minecraft.level.dimension(),
                    minecraft.gameRenderer.getMainCamera().getPosition(), chunks * 16)
                : EMPTY;
        enabled = enabled && (coverage[0] | coverage[1] | coverage[2] | coverage[3]) != 0;
        GL20.glUniform1i(enabledLocation, enabled ? 1 : 0);
        if (!enabled) return;
        GL20.glUniform1f(GL20.glGetUniformLocation(program, "VssBoundaryRadius"), chunks * 16.0f);
        int maskLocation = GL20.glGetUniformLocation(program, "VssBoundaryCoverage");
        if (maskLocation >= 0) GL30.glUniform4ui(maskLocation, coverage[0], coverage[1], coverage[2], coverage[3]);
    }

}
