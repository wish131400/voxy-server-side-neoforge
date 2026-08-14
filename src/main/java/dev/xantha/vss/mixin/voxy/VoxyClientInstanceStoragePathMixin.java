package dev.xantha.vss.mixin.voxy;

import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.networking.client.ClientConnectionIdentity;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "me.cortex.voxy.client.VoxyClientInstance", remap = false)
public abstract class VoxyClientInstanceStoragePathMixin {
    @Unique
    private static String vss$lastLoggedStorageDirectory;

    @Inject(method = "getBasePath()Ljava/nio/file/Path;", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void vss$useCapturedMultiplayerStoragePath(CallbackInfoReturnable<Path> cir) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getSingleplayerServer() != null) {
            return;
        }

        Path path = ClientConnectionIdentity.currentVoxyStoragePath(minecraft.gameDirectory.toPath());
        if (path == null) {
            return;
        }

        String directory = path.getFileName().toString();
        if (!directory.equals(vss$lastLoggedStorageDirectory)) {
            vss$lastLoggedStorageDirectory = directory;
            VSSLogger.info("Using multiplayer Voxy storage folder: " + directory);
        }
        cir.setReturnValue(path);
    }
}
