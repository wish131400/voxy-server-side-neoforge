package dev.xantha.vss.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.xantha.vss.client.prediction.feature.PredictionLevelSeed;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.structure.templatesystem.CappedProcessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** CappedProcessor seeds its block choice from level.getLevel().getSeed(); a prediction level gives its own seed. */
@Mixin(CappedProcessor.class)
public abstract class CappedProcessorPredictionSeedMixin {
    // A prediction level has no server level; its getSeed() below reads the level's own seed instead.
    @WrapOperation(method = "finalizeProcessing", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/ServerLevelAccessor;getLevel()Lnet/minecraft/server/level/ServerLevel;"))
    private ServerLevel vss$predictionServerLevel(ServerLevelAccessor level, Operation<ServerLevel> original) {
        return PredictionLevelSeed.ownSeed(level) ? null : original.call(level);
    }

    @WrapOperation(method = "finalizeProcessing", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;getSeed()J"))
    private long vss$predictionSeed(ServerLevel server, Operation<Long> original,
                                    @Local(argsOnly = true) ServerLevelAccessor level) {
        if (server == null && PredictionLevelSeed.ownSeed(level)) return ((WorldGenLevel) level).getSeed();
        return original.call(server);
    }
}
