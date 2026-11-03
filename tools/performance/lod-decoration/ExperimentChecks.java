package dev.xantha.vss.client.prediction;

import java.nio.file.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

final class ExperimentChecks {
    static void run(Path output)throws Exception{
        LodExperiment.select("span");
        var columns=new ColumnBlocks();var oracle=new HashMap<BlockPos,BlockState>();var random=new Random(20261102);
        var states=new BlockState[]{Blocks.AIR.defaultBlockState(),Blocks.STONE.defaultBlockState(),Blocks.OAK_LOG.defaultBlockState(),Blocks.OAK_LEAVES.defaultBlockState(),Blocks.WATER.defaultBlockState()};
        for(int i=0;i<16000;i++){
            var p=new BlockPos(random.nextInt(13)-6,random.nextInt(50)-10,random.nextInt(13)-6);
            if(random.nextInt(4)==0){if(columns.remove(p)!=oracle.remove(p))throw new AssertionError("remove");}
            else{var state=states[random.nextInt(states.length)];if(columns.put(p,state)!=oracle.put(p,state))throw new AssertionError("put");}
            if(i%500==0 && (!columns.equals(oracle)||!oracle.equals(columns)))throw new AssertionError("column Map mismatch");
        }
        if(!ColumnBlocks.snapshot(columns).equals(oracle))throw new AssertionError("snapshot");
        var before=ColumnBlocks.snapshot(columns);columns.put(new BlockPos(0,100,0),states[0]);if(before.containsKey(new BlockPos(0,100,0)))throw new AssertionError("mutable snapshot");
        try{before.put(new BlockPos(0,101,0),states[0]);throw new AssertionError("mutable frozen");}catch(UnsupportedOperationException expected){}
        var leafMap=new HashMap<BlockPos,BlockState>();leafMap.put(new BlockPos(-7,64,0),Blocks.OAK_LOG.defaultBlockState());
        for(int x=-6;x<=7;x++)leafMap.put(new BlockPos(x,64,0),Blocks.OAK_LEAVES.defaultBlockState());
        var full=PredictionLeafStates.settle(leafMap);
        LodExperiment.select("span_demand");
        LodExperiment.CURRENT.set(new LodExperiment.Demand(0,0,16,4));
        var cropped=new ColumnBlocks();leafMap.forEach((p,s)->{if(LodExperiment.needed(p.getX(),p.getZ()))cropped.put(p,s);});
        var settled=PredictionLeafStates.settle(cropped);
        for(var e:full.entrySet())if(e.getKey().getX()>=-1&&e.getKey().getX()<17&&settled.get(e.getKey())!=e.getValue())throw new AssertionError("leaf halo");
        LodExperiment.CURRENT.remove();
        var packed=(ColumnBlocks)ColumnBlocks.snapshot(columns);
        var sources=List.of(new PredictionVegetationDisplayCache.Source(0,packed));
        LodExperiment.select("baseline");
        var base=PredictionVegetationDisplayCache.merge(-4,-4,8,1,true,sources,p->p.getX()==0);
        LodExperiment.select("span");
        var direct=PredictionVegetationDisplayCache.merge(-4,-4,8,1,true,sources,p->p.getX()==0);
        if(!base.blocks().equals(direct.blocks()))throw new AssertionError("capture/negative-border merge");
        var baseCells=base.cells();var directCells=direct.cells();
        for(var e:baseCells.entrySet())if(!new HashSet<>(e.getValue()).equals(new HashSet<>(directCells.get(e.getKey()))))throw new AssertionError("cell compatibility");
        Files.writeString(output.resolve("checks-complete.txt"),"success: 16000 randomized map operations, overwrite/split/remove, immutable publication, leaf halo, captures, negative-border merge, cell compatibility\n");
    }

    static void lifecycle(RustTerrainSampler sampler,ClientTerrainSampler context)throws Exception{
        LodExperiment.select("span");
        var level=new PredictionDecorationLevel(sampler,context,context.decorationAccess(),8192,-8192);
        var p=new BlockPos(8192*16,90,-8192*16);
        BlockState before=level.getBlockState(p);
        level.beginFeature();level.setBlock(p,Blocks.OAK_LOG.defaultBlockState(),19,0);
        level.setBlock(p.above(),Blocks.OAK_LEAVES.defaultBlockState(),19,0);
        if(level.getBlockState(p)!=Blocks.OAK_LOG.defaultBlockState())throw new AssertionError("ordered read");
        level.endFeature(false);
        if(!level.placed().isEmpty()||level.getBlockState(p)!=before)throw new AssertionError("rollback");
        level.beginStructure();level.setBlock(p,Blocks.STONE.defaultBlockState(),19,0);level.endFeature(true);
        if(!level.isStructureBlock(p)||level.getBlockState(p)!=Blocks.STONE.defaultBlockState())throw new AssertionError("structure protection");
        level.beginFeature();level.setBlock(p,Blocks.AIR.defaultBlockState(),19,0);level.endFeature(false);
        if(level.getBlockState(p)!=Blocks.STONE.defaultBlockState())throw new AssertionError("overwritten rollback");
    }
}
