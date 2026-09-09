package dev.xantha.vss.mixin.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the sprite's original CPU-side image so sprite average colours can
 * be computed without reading the whole block atlas back from the GPU.  The
 * stitched atlas texture is mipped and padded, so its level-0 readback also
 * costs a full-texture transfer on every table rebuild; the original image is
 * exact, stable, and already resident in memory.
 */
@Mixin(SpriteContents.class)
public interface SpriteContentsImageAccessor {
    @Accessor("originalImage")
    NativeImage vss$originalImage();
}
