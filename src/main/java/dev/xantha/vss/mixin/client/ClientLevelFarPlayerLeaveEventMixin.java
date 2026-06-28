package dev.xantha.vss.mixin.client;

import dev.xantha.vss.networking.client.FarPlayerClientRenderer;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "net.minecraft.client.multiplayer.ClientLevel$EntityCallbacks")
public abstract class ClientLevelFarPlayerLeaveEventMixin {
    @Redirect(
            method = "onTrackingEnd(Lnet/minecraft/world/entity/Entity;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/bus/api/IEventBus;post(Lnet/neoforged/bus/api/Event;)Lnet/neoforged/bus/api/Event;",
                    remap = false
            ),
            require = 0
    )
    private Event vss$skipSyntheticFarPlayerLeaveEvent(IEventBus eventBus, Event event) {
        if (event instanceof EntityLeaveLevelEvent leaveEvent
                && FarPlayerClientRenderer.isSyntheticFarPlayer(leaveEvent.getEntity())) {
            return event;
        }
        return eventBus.post(event);
    }
}
