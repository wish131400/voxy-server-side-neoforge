package dev.xantha.vss.mixin.client;

import dev.xantha.vss.client.prediction.PredictionStructureTerrain;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Structure-only memoization; normal chunk generation always uses the original implementation. */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class PredictionStructureTerrainMixin {
    @Inject(method="getBaseHeight",at=@At("HEAD"),cancellable=true)
    private void vss$height(int x,int z,Heightmap.Types type,LevelHeightAccessor bounds,RandomState random,
                            CallbackInfoReturnable<Integer> result) {
        var cache=PredictionStructureTerrain.current(this,random);
        if(cache==null)return;
        var saved=cache.height(x,z,bounds.getMinBuildHeight(),bounds.getHeight(),type);
        if(saved!=null)result.setReturnValue(saved);
    }
    @Inject(method="getBaseHeight",at=@At("RETURN"))
    private void vss$rememberHeight(int x,int z,Heightmap.Types type,LevelHeightAccessor bounds,RandomState random,
                                    CallbackInfoReturnable<Integer> result) {
        var cache=PredictionStructureTerrain.current(this,random);
        if(cache!=null)cache.height(x,z,bounds.getMinBuildHeight(),bounds.getHeight(),type,result.getReturnValue());
    }
    @Inject(method="getBaseColumn",at=@At("HEAD"),cancellable=true)
    private void vss$column(int x,int z,LevelHeightAccessor bounds,RandomState random,
                            CallbackInfoReturnable<NoiseColumn> result) {
        var cache=PredictionStructureTerrain.current(this,random);
        if(cache==null)return;
        var saved=cache.column(x,z,bounds.getMinBuildHeight(),bounds.getHeight());
        if(saved!=null)result.setReturnValue(saved);
    }
    @Inject(method="getBaseColumn",at=@At("RETURN"))
    private void vss$rememberColumn(int x,int z,LevelHeightAccessor bounds,RandomState random,
                                    CallbackInfoReturnable<NoiseColumn> result) {
        var cache=PredictionStructureTerrain.current(this,random);
        if(cache!=null)cache.column(x,z,bounds.getMinBuildHeight(),bounds.getHeight(),result.getReturnValue());
    }
}
