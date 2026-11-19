package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.testutil.TerrainPredictionFixtures;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.*;
import org.junit.jupiter.api.*;

class PredictionChunkTerrainTest {
    @BeforeAll static void bootstrap(){TerrainPredictionFixtures.bootstrap();}
    @Test void sharedChunkMatchesOriginalColumnsIncludingFluidsAndNegativeBorders() {
        var access=TerrainPredictionFixtures.access();
        for(var dimension:List.of(NoiseGeneratorSettings.OVERWORLD,NoiseGeneratorSettings.NETHER,NoiseGeneratorSettings.END)) {
            var settings=access.registryOrThrow(Registries.NOISE_SETTINGS).getHolderOrThrow(dimension).value();
            var context=PredictionJavaExteriorTest.context(settings);
            var fast=new PredictionChunkTerrain(context.generator(),context.random(),context.heights());
            for(var pos:List.of(new ChunkPos(-1,-2),new ChunkPos(100,-91))) {
                var chunk=new ProtoChunk(pos,UpgradeData.EMPTY,context.heights(),access.registryOrThrow(Registries.BIOME),null);
                fast.fill(pos,chunk);
                for(int[] p:List.of(new int[]{0,0},new int[]{3,7},new int[]{4,4},new int[]{15,15})) {
                    int x=pos.getMinBlockX()+p[0],z=pos.getMinBlockZ()+p[1];
                    var original=context.generator().getBaseColumn(x,z,context.heights(),context.random());
                    var cursor=new BlockPos.MutableBlockPos();
                    for(int y=context.heights().getMinBuildHeight();y<context.heights().getMaxBuildHeight();y++)
                        assertSame(original.getBlock(y),chunk.getBlockState(cursor.set(x,y,z)),dimension+" "+x+","+y+","+z);
                }
            }
            assertTrue(fast.diagnostics().contains("mode=chunkBatch"));
            assertTrue(fast.diagnostics().contains("orderedColumns=0"));
        }
    }
    @Test void unknownOrVerticalCacheOrderKeepsOriginalColumnEvaluation() {
        var y=DensityFunctions.yClampedGradient(-64,192,1,-1);
        var context=PredictionJavaExteriorTest.context(PredictionJavaExteriorTest.synthetic(DensityFunctions.cache2d(y),false));
        var fast=new PredictionChunkTerrain(context.generator(),context.random(),context.heights());
        assertTrue(fast.diagnostics().contains("mode=orderedColumns"));
    }
}
