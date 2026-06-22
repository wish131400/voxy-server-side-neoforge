package dev.xantha.vss.mixin.minecraft;

import dev.xantha.vss.common.VSSLogger;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelChunk.class)
public abstract class LevelChunkClearBlockEntitiesMixin {
    @Unique
    private static final TickingBlockEntity vss$nullTicker = new TickingBlockEntity() {
        @Override
        public void tick() {
        }

        @Override
        public boolean isRemoved() {
            return true;
        }

        @Override
        public BlockPos getPos() {
            return BlockPos.ZERO;
        }

        @Override
        public String getType() {
            return "<vss:null>";
        }
    };

    @Unique
    private static volatile Method vss$rebindMethod;

    @Shadow
    @Final
    private Map<BlockPos, ?> tickersInLevel;

    @Shadow
    public abstract Map<BlockPos, BlockEntity> getBlockEntities();

    @Inject(method = "clearAllBlockEntities", at = @At("HEAD"), cancellable = true)
    private void vss$clearAllBlockEntitiesWithoutConcurrentModification(CallbackInfo ci) {
        Map<BlockPos, BlockEntity> blockEntities = getBlockEntities();
        List<BlockEntity> blockEntitySnapshot = new ArrayList<>(blockEntities.values());
        for (BlockEntity blockEntity : blockEntitySnapshot) {
            if (blockEntity != null) {
                blockEntity.onChunkUnloaded();
            }
        }
        for (BlockEntity blockEntity : blockEntitySnapshot) {
            if (blockEntity != null) {
                blockEntity.setRemoved();
            }
        }
        blockEntities.clear();

        List<?> tickerSnapshot = new ArrayList<>(tickersInLevel.values());
        for (Object ticker : tickerSnapshot) {
            vss$rebindTicker(ticker);
        }
        tickersInLevel.clear();
        ci.cancel();
    }

    @Unique
    private static void vss$rebindTicker(Object ticker) {
        if (ticker == null) {
            return;
        }
        if (ticker instanceof LevelChunkRebindableTickerAccessor accessor) {
            accessor.vss$rebind(vss$nullTicker);
            return;
        }

        Method method = vss$findRebindMethod(ticker.getClass());
        if (method == null) {
            return;
        }
        try {
            method.invoke(ticker, vss$nullTicker);
        } catch (ReflectiveOperationException | RuntimeException e) {
            VSSLogger.warn("Failed to rebind unloaded block entity ticker", e);
        }
    }

    @Unique
    private static Method vss$findRebindMethod(Class<?> type) {
        Method cached = vss$rebindMethod;
        if (cached != null && cached.getDeclaringClass() == type) {
            return cached;
        }

        Method method = vss$getDeclaredRebindMethod(type, "rebind");
        if (method == null) {
            method = vss$getDeclaredRebindMethod(type, "m_156449_");
        }
        if (method != null) {
            method.setAccessible(true);
            vss$rebindMethod = method;
        }
        return method;
    }

    @Unique
    private static Method vss$getDeclaredRebindMethod(Class<?> type, String name) {
        try {
            return type.getDeclaredMethod(name, TickingBlockEntity.class);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}
