package dev.xantha.vss.mixin.client;

import dev.xantha.vss.client.prediction.FreeTerraForgedPredictionTasks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(targets = "com.pandaismyname1.ultraterraforged.world.worldgen.tile.TileCache", remap = false)
public abstract class UltraTerraForgedPredictionTileCacheMixin {
    @Coerce
    @Redirect(method = {"provide", "queue"}, at = @At(value = "INVOKE",
            target = "Lcom/pandaismyname1/ultraterraforged/world/worldgen/tile/TileCache;computeEntry(II)Lcom/pandaismyname1/ultraterraforged/concurrent/cache/CacheEntry;"), remap = false)
    private Object vss$registerBeforePredictionBatches(@Coerce Object cache, int x, int z) {
        return FreeTerraForgedPredictionTasks.computeEntry(cache, x, z);
    }
}
