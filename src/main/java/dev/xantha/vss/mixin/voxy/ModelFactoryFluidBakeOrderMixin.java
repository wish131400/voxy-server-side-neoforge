package dev.xantha.vss.mixin.voxy;

import dev.xantha.vss.common.VSSLogger;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.locks.Lock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.model.ModelFactory", remap = false)
public abstract class ModelFactoryFluidBakeOrderMixin {
    @Unique
    private static final int vss$AIR_BLOCK_STATE_ID = 0;

    @Unique
    private static final Set<Integer> vss$loggedFluidDefers = ConcurrentHashMap.newKeySet();

    @Unique
    private static final Set<Integer> vss$loggedSelfFluidFallbacks = ConcurrentHashMap.newKeySet();

    @Unique
    private static volatile boolean vss$loggedCompatReflectionFailure;

    @Unique
    private boolean vss$addingFluidDependency;

    @Unique
    private Field vss$bakeQueueField;

    @Unique
    private Field vss$idMappingsField;

    @Unique
    private Field vss$blockStatesInFlightField;

    @Unique
    private Field vss$blockStatesInFlightLockField;

    @Unique
    private Field vss$mapperField;

    @Unique
    private Method vss$blockBakeBlockIdMethod;

    @Unique
    private Method vss$blockBakeStateMethod;

    @Unique
    private Method vss$mapperGetIdForBlockStateMethod;

    @Unique
    private Method vss$addEntryMethod;

    @Inject(method = "processModelResult", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void vss$deferWaterloggedBakeUntilFluidModelExists(CallbackInfoReturnable<Boolean> cir) {
        if (this.vss$addingFluidDependency) {
            return;
        }
        try {
            ConcurrentLinkedDeque bakeQueue = vss$getBakeQueue();
            Object bake = bakeQueue.peek();
            if (bake == null) {
                return;
            }

            BlockState state = vss$getBakeState(bake);
            if (state == null || state.getBlock() instanceof LiquidBlock || state.getFluidState().isEmpty()) {
                return;
            }

            int blockId = vss$getBakeBlockId(bake);
            int fluidStateId = vss$getBlockStateId(state.getFluidState().createLegacyBlock());
            int[] idMappings = vss$getIdMappings();
            if (fluidStateId < 0 || fluidStateId >= idMappings.length || idMappings[fluidStateId] != -1) {
                return;
            }

            if (fluidStateId == blockId) {
                if (vss$mapSelfDependentFluidToFallback(bakeQueue, bake, blockId, state, idMappings)) {
                    cir.setReturnValue(!bakeQueue.isEmpty());
                } else {
                    cir.setReturnValue(false);
                }
                return;
            }

            this.vss$addingFluidDependency = true;
            try {
                vss$addEntry(fluidStateId);
            } finally {
                this.vss$addingFluidDependency = false;
            }

            Object deferred = bakeQueue.peek() == bake ? bakeQueue.poll() : null;
            if (deferred != null) {
                bakeQueue.add(deferred);
            } else {
                return;
            }

            if (vss$loggedFluidDefers.add(blockId)) {
                VSSLogger.debug("Deferred Voxy LOD model bake until its fluid model is ready: " + state);
            }
            cir.setReturnValue(false);
        } catch (Throwable t) {
            if (!vss$loggedCompatReflectionFailure) {
                vss$loggedCompatReflectionFailure = true;
                VSSLogger.warn("VSS Voxy fluid bake-order compat failed; falling back to Voxy default behavior", t);
            }
        }
    }

    @Unique
    private boolean vss$mapSelfDependentFluidToFallback(ConcurrentLinkedDeque bakeQueue, Object bake, int blockId, BlockState state, int[] idMappings)
            throws ReflectiveOperationException {
        if (vss$AIR_BLOCK_STATE_ID >= idMappings.length || idMappings[vss$AIR_BLOCK_STATE_ID] == -1) {
            Object deferred = bakeQueue.peek() == bake ? bakeQueue.poll() : null;
            if (deferred != null) {
                bakeQueue.add(deferred);
            }
            if (vss$loggedFluidDefers.add(blockId)) {
                VSSLogger.debug("Deferred self-dependent Voxy fluid LOD model until fallback model is ready: " + state);
            }
            return false;
        }

        Object removed = bakeQueue.peek() == bake ? bakeQueue.poll() : null;
        if (removed == null) {
            return false;
        }

        idMappings[blockId] = idMappings[vss$AIR_BLOCK_STATE_ID];
        vss$removeInFlight(blockId);
        if (vss$loggedSelfFluidFallbacks.add(blockId)) {
            VSSLogger.warn("Mapped self-dependent Voxy fluid LOD model to transparent fallback to avoid a bake loop: " + state);
        }
        return true;
    }

    @Unique
    @SuppressWarnings("unchecked")
    private ConcurrentLinkedDeque vss$getBakeQueue() {
        try {
            if (this.vss$bakeQueueField == null) {
                this.vss$bakeQueueField = vss$field("bakeQueue");
            }
            return (ConcurrentLinkedDeque) this.vss$bakeQueueField.get(this);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Unique
    private int[] vss$getIdMappings() throws ReflectiveOperationException {
        if (this.vss$idMappingsField == null) {
            this.vss$idMappingsField = vss$field("idMappings");
        }
        return (int[]) this.vss$idMappingsField.get(this);
    }

    @Unique
    private Object vss$getBlockStatesInFlight() throws ReflectiveOperationException {
        if (this.vss$blockStatesInFlightField == null) {
            this.vss$blockStatesInFlightField = vss$field("blockStatesInFlight");
        }
        return this.vss$blockStatesInFlightField.get(this);
    }

    @Unique
    private Lock vss$getBlockStatesInFlightLock() throws ReflectiveOperationException {
        if (this.vss$blockStatesInFlightLockField == null) {
            this.vss$blockStatesInFlightLockField = vss$field("blockStatesInFlightLock");
        }
        return (Lock) this.vss$blockStatesInFlightLockField.get(this);
    }

    @Unique
    private Object vss$getMapper() throws ReflectiveOperationException {
        if (this.vss$mapperField == null) {
            this.vss$mapperField = vss$field("mapper");
        }
        return this.vss$mapperField.get(this);
    }

    @Unique
    private Field vss$field(String name) throws NoSuchFieldException {
        Field field = this.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @Unique
    private int vss$getBakeBlockId(Object bake) throws ReflectiveOperationException {
        if (this.vss$blockBakeBlockIdMethod == null) {
            this.vss$blockBakeBlockIdMethod = bake.getClass().getDeclaredMethod("blockId");
            this.vss$blockBakeBlockIdMethod.setAccessible(true);
        }
        return (Integer) this.vss$blockBakeBlockIdMethod.invoke(bake);
    }

    @Unique
    private BlockState vss$getBakeState(Object bake) throws ReflectiveOperationException {
        if (this.vss$blockBakeStateMethod == null) {
            this.vss$blockBakeStateMethod = bake.getClass().getDeclaredMethod("state");
            this.vss$blockBakeStateMethod.setAccessible(true);
        }
        Object state = this.vss$blockBakeStateMethod.invoke(bake);
        return state instanceof BlockState blockState ? blockState : null;
    }

    @Unique
    private int vss$getBlockStateId(BlockState state) throws ReflectiveOperationException {
        if (this.vss$mapperGetIdForBlockStateMethod == null) {
            this.vss$mapperGetIdForBlockStateMethod = vss$getMapper().getClass().getMethod("getIdForBlockState", BlockState.class);
            this.vss$mapperGetIdForBlockStateMethod.setAccessible(true);
        }
        return (Integer) this.vss$mapperGetIdForBlockStateMethod.invoke(vss$getMapper(), state);
    }

    @Unique
    private void vss$removeInFlight(int blockId) throws ReflectiveOperationException {
        Lock lock = vss$getBlockStatesInFlightLock();
        lock.lock();
        try {
            Method remove = vss$getBlockStatesInFlight().getClass().getMethod("remove", int.class);
            remove.setAccessible(true);
            remove.invoke(vss$getBlockStatesInFlight(), blockId);
        } finally {
            lock.unlock();
        }
    }

    @Unique
    private void vss$addEntry(int blockId) throws ReflectiveOperationException {
        if (this.vss$addEntryMethod == null) {
            this.vss$addEntryMethod = this.getClass().getMethod("addEntry", int.class);
            this.vss$addEntryMethod.setAccessible(true);
        }
        this.vss$addEntryMethod.invoke(this, blockId);
    }
}
