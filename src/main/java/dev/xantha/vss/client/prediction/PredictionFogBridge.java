package dev.xantha.vss.client.prediction;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.xantha.vss.config.VSSClientConfig;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

/** One distance/fog rule on both sides of the normal Voxy/prediction handoff. */
public final class PredictionFogBridge {
    private PredictionFogBridge() { }

    static final String GLSL = """
            uniform bool VssPredictionFogEnabled;
            uniform vec4 VssPredictionFog; // start, end, haze start, density
            uniform vec3 VssPredictionFogColor;
            vec3 vssPredictionFog(vec3 color, vec2 relativeXZ) {
                float distance = length(relativeXZ);
                vec3 hazed = mix(color, VssPredictionFogColor,
                        1.0 - exp(-max(distance - VssPredictionFog.z, 0.0) * VssPredictionFog.w));
                float fog = clamp((distance - VssPredictionFog.x)
                        / max(0.0001, VssPredictionFog.y - VssPredictionFog.x), 0.0, 1.0);
                return mix(hazed, VssPredictionFogColor, fog);
            }
            """;

    /** Only the ordinary final colour blit is patched; unknown shader layouts stay intact. */
    public static String patch(String path, String source) {
        if (!"voxy:post/blit_texture_depth_cutout.frag".equals(path) || source == null) return source;
        String color = "colour = texture(colourTex, UV.xy);";
        if (!source.contains(color) || !source.contains("vec3 point = rev3d(")) return source;
        return source.replace("void main()", GLSL + "\nvoid main()")
                .replace(color, color + "\n    if (VssPredictionFogEnabled) {\n"
                        + "        colour.rgb = vssPredictionFog(colour.rgb, point.xz);\n"
                        + "        if (colour.a == 0.0) discard;\n        return;\n    }");
    }

    /** Called immediately before Voxy's final blit while its program is bound. */
    public static void bindVoxy() {
        int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int enabled = GL20.glGetUniformLocation(program, "VssPredictionFogEnabled");
        if (enabled < 0) return;
        Minecraft minecraft = Minecraft.getInstance();
        boolean active = VSSClientConfig.CONFIG.enablePrediction && minecraft.level != null
                && PredictionRenderer.normalFogActive()
                && minecraft.gameRenderer.getMainCamera().getFluidInCamera() == net.minecraft.world.level.material.FogType.NONE
                && (minecraft.player == null || !minecraft.player.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS)
                    && !minecraft.player.hasEffect(net.minecraft.world.effect.MobEffects.DARKNESS));
        GL20.glUniform1i(enabled, active ? 1 : 0);
        if (active) {
            var fog = PredictionRenderer.normalFog(minecraft);
            bind(program, fog.start(), fog.end(), fog.hazeStart(), fog.density(), RenderSystem.getShaderFogColor());
        }
    }

    static void bind(int program, float start, float end, float hazeStart, float density, float[] color) {
        GL20.glUniform4f(GL20.glGetUniformLocation(program, "VssPredictionFog"), start, end, hazeStart, density);
        GL20.glUniform3f(GL20.glGetUniformLocation(program, "VssPredictionFogColor"), color[0], color[1], color[2]);
    }
}
