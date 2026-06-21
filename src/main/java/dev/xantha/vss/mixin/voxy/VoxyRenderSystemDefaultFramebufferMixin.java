package dev.xantha.vss.mixin.voxy;

import dev.xantha.vss.common.VSSLogger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.VoxyRenderSystem", remap = false)
public abstract class VoxyRenderSystemDefaultFramebufferMixin {
    @Unique
    private static boolean vss$loggedDefaultFramebufferSkip;

    @Inject(method = "renderOpaque(Lme/cortex/voxy/client/core/rendering/Viewport;)V", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void vss$skipRenderWhenDefaultFramebufferBound(@Coerce Object viewport, CallbackInfo ci) {
        if (GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING) != 0) {
            return;
        }

        if (!vss$loggedDefaultFramebufferSkip) {
            vss$loggedDefaultFramebufferSkip = true;
            VSSLogger.warn("Skipped Voxy LOD rendering because the default framebuffer was bound");
        }
        ci.cancel();
    }
}
