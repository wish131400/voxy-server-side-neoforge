package dev.xantha.vss.mixin.minecraft;

import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(targets = "net.minecraft.world.level.chunk.LevelChunk$RebindableTickingBlockEntityWrapper")
public interface LevelChunkRebindableTickerAccessor {
    @Invoker("rebind")
    void vss$rebind(TickingBlockEntity ticker);
}
