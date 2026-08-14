package dev.xantha.vss.mixin.client;

import dev.xantha.vss.networking.client.ClientConnectionIdentity;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerServerIdentityMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void vss$captureServerIdentity(CallbackInfo ci) {
        ClientPacketListener listener = (ClientPacketListener) (Object) this;
        ClientConnectionIdentity.beginSession(listener.getServerData());
    }
}
