package dev.xantha.vss.client.prediction;

import java.util.*;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;

/** Sampling look-ahead only. Native Voxy still owns mesh subdivision and drawing. */
final class PredictionVoxyViewDemand {
    static List<PredictionTileKey> build(PredictionVoxySource.DetailInput input) {
        if (input.view()==null || !input.view().hasProjection()) return List.of();
        var result=new ArrayList<PredictionTileKey>();
        int top=input.layout().levelCount()-1, span=input.layout().tileBlocks(top);
        int radius=input.layout().maxDistanceBlocks();
        for(int z=(int)Math.floor((input.z()-radius)/span);z<=(int)Math.floor((input.z()+radius)/span);z++)
            for(int x=(int)Math.floor((input.x()-radius)/span);x<=(int)Math.floor((input.x()+radius)/span);x++)
                collect(input,new PredictionTileKey(input.dimension(),x,z,top),result);
        return List.copyOf(result);
    }
    private static void collect(PredictionVoxySource.DetailInput input,PredictionTileKey key,List<PredictionTileKey> result) {
        var layout=input.layout();var view=input.view();
        if(PredictionWorkOrder.distanceSquared(key,layout,input.x(),input.z())>(double)layout.maxDistanceBlocks()*layout.maxDistanceBlocks()
                || view.band(key,layout,null,input.minY(),input.maxY())>PredictionWorkView.VISIBLE) return;
        double projected=view.projectedSize(key,layout,input.minY(),input.maxY(),0);
        boolean plants=layout.trees() && projected*8/layout.tileBlocks(key.lod())>3;
        // Full 64-cell tiles are the shared source unit. Do not generate a second
        // mesh hierarchy: submit the required horizontal sample identities to
        // the same bounded queue as GPU requests, including unseeded regions.
        if(key.lod()>0 && (key.lod()>4 || projected>layout.pixelThreshold()
                || plants && !PredictionWorkOrder.surfaceTile(key,layout))) {
            for(int z=0;z<2;z++)for(int x=0;x<2;x++)
                collect(input,new PredictionTileKey(key.dimension(),key.tileX()*2+x,key.tileZ()*2+z,key.lod()-1),result);
        } else result.add(key);
    }
}
