package dev.xantha.vss.client.prediction;
import java.util.*;
import net.minecraft.world.level.block.Blocks;
import dev.xantha.vss.client.prediction.PredictionTileManager.*;

/** CPU fixtures publish the same column-run representation consumed by Voxy. */
final class PredictionVoxelFixtures {
    static ClientColumnSample sample(int y) {
        return new ClientColumnSample(y, y, 0, PredictionMaterialPalette.grassBlockIndex(), 0, 0, 0, 0,
                0, ClientColumnSample.FLAG_SURFACE_ONLY, 0, PredictionMaterialPalette.dirtIndex(), PredictionMaterialPalette.stoneIndex(),
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
    }
    static org.joml.Matrix4f projection(double degrees) {
        return new org.joml.Matrix4f().perspective((float)Math.toRadians(degrees),1,.05f,32768);
    }
    static PredictionVoxelData flat(PredictionTileKey key,int axis,int step,int height) {
        var column=new PredictionVoxySource.Column(List.of(new PredictionVoxySource.Run(-64,height,
                Blocks.STONE.defaultBlockState())),"minecraft:plains");
        var columns=new PredictionVoxySource.Column[axis*axis]; Arrays.fill(columns,column);
        int span=axis*step;
        return PredictionVoxelData.forVoxy(new PredictionVoxySource.Tile(key,key.tileX()*span,key.tileZ()*span,
                span,axis,step,columns,List.of(),256L+columns.length*16L));
    }
    static PredictionTile tile(int x,int z,int lod,VssLodLayout layout) {
        var key=new PredictionTileKey(net.minecraft.world.level.Level.OVERWORLD,x,z,lod);
        int axis=64,step=layout.sampleSpacing(lod);
        var heights=new int[(axis+1)*(axis+1)]; Arrays.fill(heights,64);
        var samples=new ClientColumnSample[heights.length]; Arrays.fill(samples,PredictionVoxelFixtures.sample(64));
        return new PredictionTile(key,heights,heights,samples,flat(key,axis,step,64),
                new PredictionDepthBound(64,64),0,1,axis,step);
    }
}
