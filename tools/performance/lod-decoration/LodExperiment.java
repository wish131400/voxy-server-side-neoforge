package dev.xantha.vss.client.prediction;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Per-thread output demand and counters. These switches never enter release code. */
final class LodExperiment {
    static boolean spans,demand;
    static long modelCalls,modelCells,sourceChunks,extractionColumns,omittedColumns,outputRuns;
    record Demand(int x,int z,int span,int spacing){
        // Tile keeps a one-block publication border. Leaf-distance propagation is at most six.
        boolean needs(int px,int pz){return px>=x-7 && pz>=z-7 && px<x+span+7 && pz<z+span+7;}
    }
    static final ThreadLocal<Demand> CURRENT=new ThreadLocal<>();
    static void select(String mode){spans=mode.contains("span");demand=mode.contains("demand");}
    static void reset(){modelCalls=modelCells=sourceChunks=extractionColumns=omittedColumns=outputRuns=0;}
    static Map<String,Object> stats(){return Map.of("modelCalls",modelCalls,"modelCells",modelCells,"sourceChunks",sourceChunks,"extractionColumns",extractionColumns,"omittedColumns",omittedColumns,"outputRuns",outputRuns);}
    static boolean needed(int x,int z){var d=CURRENT.get();return !demand || d==null || d.needs(x,z);}
    static Map<BlockPos,BlockState> exterior(PredictionDecorationLevel level){
        if(!(level.placed() instanceof ColumnBlocks source))throw new AssertionError("column overlay absent");
        var output=new ColumnBlocks();
        var floors=new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
        var surfaces=new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
        floors.defaultReturnValue(Integer.MIN_VALUE);surfaces.defaultReturnValue(Integer.MIN_VALUE);
        source.runs((x,z,bottom,top,state)->{
            if(!needed(x,z)){omittedColumns++;return;}
            boolean air=state.isAir();
            if(level.interiorTerrain()){
                if(PredictionVegetation.renderable(state)||air||state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock)output.putRun(x,z,bottom,top,state);
                return;
            }
            long key=(long)x<<32|z&0xffffffffL;int surface=surfaces.get(key);
            if(surface==Integer.MIN_VALUE){extractionColumns++;surface=level.exteriorColumn(x,z).surfaceY();surfaces.put(key,surface);}
            if(air && bottom>=surface)return;
            int floor=floors.get(key);
            if(floor==Integer.MIN_VALUE){
                floor=surface;var cursor=new BlockPos.MutableBlockPos(x,floor-1,z);
                while(floor>level.getMinBuildHeight() && source.containsKey(cursor.setY(floor-1)))floor--;
                floors.put(key,floor);
            }
            if(PredictionVegetation.renderable(state)||air||state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock)
                output.putRun(x,z,Math.max(bottom,floor),air?Math.min(top,surface):top,state);
        });
        return output;
    }
}
