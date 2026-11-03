package dev.xantha.vss.client.prediction;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Exact unit-block occupancy, emitted as vertical boxes without packing and unpacking keys. */
final class SpanTile extends PredictionVegetation.Tile {
    private final ColumnBlocks data;
    private final int x,z,span,spacing,count,top;
    SpanTile(ColumnBlocks data,int x,int z,int span,int spacing){
        super(new PredictionVegetationBlocks.Builder().build(),x,z,span,spacing,1);
        this.data=data;this.x=x;this.z=z;this.span=span;this.spacing=spacing;
        int[] stats={0,Integer.MIN_VALUE};
        data.runs((px,pz,b,t,s)->{if(px>=x && pz>=z && px<x+span && pz<z+span){stats[0]+=t-b;stats[1]=Math.max(stats[1],t);}});
        count=stats[0];top=stats[1];
    }
    @Override int baseX(){return x;} @Override int baseZ(){return z;}
    @Override int maxY(){return top;} @Override int voxelCount(){return count;}
    @Override Map<BlockPos,BlockState> blocks(){return data;}
    @Override long bytes(){return 96+data.bytes();}
    @Override void forEachVoxel(Visitor visitor){data.runs((px,pz,b,t,s)->{if(px>=x&&pz>=z&&px<x+span&&pz<z+span)for(int y=b;y<t;y++)visitor.accept(px-x,y,pz-z,1,s);});}
    @Override void forEachSpan(SpanVisitor visitor){
        // Production uses spacing one: columns are ordered z,x and then ascending Y.
        // Preserve that overwrite order when several fine blocks share a coarse voxel.
        data.orderedRuns((px,pz,b,t,s)->{if(px>=x&&pz>=z&&px<x+span&&pz<z+span){
            if(s.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock){
                for(int y=b;y<t;y++){LodExperiment.outputRuns++;visitor.accept(px-x,y,pz-z,px-x+1,y+1,pz-z+1,s);}
            }else{LodExperiment.outputRuns++;visitor.accept(px-x,b,pz-z,px-x+1,t,pz-z+1,s);}
        }});
    }
    @Override Map<Integer,List<PredictionVegetation.Voxel>> cells(){
        var grouped=new HashMap<Integer,List<PredictionVegetation.Voxel>>();
        forEachVoxel((px,y,pz,size,s)->grouped.computeIfAbsent(pz/spacing*(span/spacing)+px/spacing,k->new ArrayList<>()).add(new PredictionVegetation.Voxel(px,y,pz,size,s)));
        grouped.replaceAll((k,v)->List.copyOf(v));return Map.copyOf(grouped);
    }
}
