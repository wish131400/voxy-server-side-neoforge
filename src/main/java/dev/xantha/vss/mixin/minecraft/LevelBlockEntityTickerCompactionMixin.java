package dev.xantha.vss.mixin.minecraft;

import dev.xantha.vss.common.BlockEntityTickerCompactor;
import java.util.List;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Level.class)
public abstract class LevelBlockEntityTickerCompactionMixin {
    @Shadow
    protected List<TickingBlockEntity> blockEntityTickers;

    @Inject(method = "tickBlockEntities", at = @At("HEAD"))
    private void vss$compactRemovedBlockEntityTickers(CallbackInfo ci) {
        if (BlockEntityTickerCompactor.consume((Level) (Object) this)) {
            blockEntityTickers.removeIf(TickingBlockEntity::isRemoved);
        }
    }
}
