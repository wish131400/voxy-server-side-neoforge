package dev.xantha.vss.mixin.client;

import dev.xantha.vss.client.prediction.FreeTerraForgedPredictionTasks;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(targets = "com.pandaismyname1.ultraterraforged.world.worldgen.tile.TileGenerator", remap = false)
public abstract class UltraTerraForgedPredictionTasksMixin {
    @Redirect(method = "generate(II)Ljava/util/concurrent/CompletableFuture;",
            at = @At(value = "INVOKE", target = "Ljava/util/concurrent/CompletableFuture;runAsync(Ljava/lang/Runnable;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"),
            remap = false)
    private CompletableFuture<Void> vss$predictionBatch(Runnable task, Executor upstream) {
        return FreeTerraForgedPredictionTasks.run(this, task, upstream);
    }
}
