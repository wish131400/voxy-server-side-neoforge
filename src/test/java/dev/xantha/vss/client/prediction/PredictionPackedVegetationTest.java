package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.*;

class PredictionPackedVegetationTest {
    @BeforeAll static void bootstrap(){ClientTerrainSamplerTest.bootstrapMinecraft();}
    static Map<Integer,List<PredictionVegetation.Voxel>> reference(Map<BlockPos,BlockState> blocks,int x,int z,int span,int spacing,int size) {
        var cells=new HashMap<Integer,List<PredictionVegetation.Voxel>>();
        blocks.forEach((p,s)->{int dx=p.getX()-x,dz=p.getZ()-z;
            if(dx>=0&&dz>=0&&dx<span&&dz<span)cells.computeIfAbsent(dz/spacing*(span/spacing)+dx/spacing,k->new ArrayList<>())
                    .add(new PredictionVegetation.Voxel(dx,p.getY(),dz,PredictionVegetation.voxelSize(s,size),s));});
        cells.values().forEach(v->v.sort(Comparator.comparingInt(PredictionVegetation.Voxel::y)
                .thenComparingInt(PredictionVegetation.Voxel::z).thenComparingInt(PredictionVegetation.Voxel::x)));
        cells.replaceAll((k,v)->List.copyOf(v));return Map.copyOf(cells);
    }
    @Test void palettePreservesFullCoordinatesAndImmutablePublication() {
        var input=new HashMap<BlockPos,BlockState>();var variants=new ArrayList<BlockState>();
        for(var block:net.minecraft.core.registries.BuiltInRegistries.BLOCK)variants.addAll(block.getStateDefinition().getPossibleStates());
        for(int i=0;i<700;i++)input.put(new BlockPos(-200+i,5000+i,-800-i),variants.get(i));
        input.put(new BlockPos(-200,5000+4096,-800),Blocks.WATER.defaultBlockState());
        var packed=PredictionVegetationBlocks.copyOf(input);assertEquals(input,packed);assertEquals(701,packed.size());
        input.forEach((p,s)->assertSame(s,packed.get(new BlockPos.MutableBlockPos(p.getX(),p.getY(),p.getZ()))));
        input.clear();assertEquals(701,packed.size());
        assertThrows(UnsupportedOperationException.class,()->packed.entrySet().iterator().next().setValue(Blocks.AIR.defaultBlockState()));
        var builder=new PredictionVegetationBlocks.Builder();builder.put(1,2,3,Blocks.OAK_LOG.defaultBlockState());
        builder.put(1,2,3,Blocks.OAK_LEAVES.defaultBlockState());var result=builder.build();
        assertEquals(Map.of(new BlockPos(1,2,3),Blocks.OAK_LEAVES.defaultBlockState()),result);
        assertThrows(IllegalStateException.class,()->builder.put(4,5,6,Blocks.AIR.defaultBlockState()));
    }
    @Test void primitiveDisplayMatchesLegacyCellsIncludingEnlargedProxyOrder() {
        var blocks=new HashMap<BlockPos,BlockState>();var random=new Random(78519);
        var states=List.of(Blocks.OAK_LEAVES.defaultBlockState(),Blocks.OAK_LOG.defaultBlockState(),Blocks.SEAGRASS.defaultBlockState(),Blocks.SNOW.defaultBlockState());
        for(int i=0;i<14000;i++)blocks.put(new BlockPos(-35+random.nextInt(70),-64+random.nextInt(400),-35+random.nextInt(70)),states.get(i%states.size()));
        for(int spacing:List.of(1,2,4,8))for(int size:List.of(1,2,4)) {
            var tile=PredictionVegetation.Tile.of(blocks,-32,-32,64,spacing,size);var expected=reference(blocks,-32,-32,64,spacing,size);
            var actual=new HashMap<Integer,List<PredictionVegetation.Voxel>>();var sequence=new ArrayList<PredictionVegetation.Voxel>();
            tile.forEachVoxel((x,y,z,s,state)->{var voxel=new PredictionVegetation.Voxel(x,y,z,s,state);sequence.add(voxel);
                actual.computeIfAbsent(z/spacing*(64/spacing)+x/spacing,k->new ArrayList<>()).add(voxel);});
            assertEquals(expected,actual);assertEquals(blocks,tile.blocks());
            assertEquals(size!=1,tile.hasCellObjects(),"unit-size display must avoid per-cell objects");
            if(size!=1)assertEquals(expected.values().stream().flatMap(List::stream).toList(),sequence);
            assertEquals(expected,tile.cells());
        }
    }
    @Test void mergedSourcesKeepLastWriterAndCaptureBoundaryRules() {
        var first=Map.of(new BlockPos(-1,70,0),Blocks.OAK_LOG.defaultBlockState(),new BlockPos(0,71,0),Blocks.OAK_LEAVES.defaultBlockState(),new BlockPos(64,71,0),Blocks.OAK_LEAVES.defaultBlockState());
        var last=Map.of(new BlockPos(0,71,0),Blocks.BIRCH_LEAVES.defaultBlockState(),new BlockPos(65,71,0),Blocks.OAK_LOG.defaultBlockState());
        var sources=List.of(new PredictionVegetationDisplayCache.Source(1,first),new PredictionVegetationDisplayCache.Source(2,last));
        var tile=PredictionVegetationDisplayCache.merge(0,0,64,1,true,sources,p->p.getX()<0);
        assertEquals(Map.of(new BlockPos(0,71,0),Blocks.BIRCH_LEAVES.defaultBlockState(),new BlockPos(64,71,0),Blocks.OAK_LEAVES.defaultBlockState()),tile.blocks());
        assertEquals(1,tile.voxelCount());assertFalse(tile.hasCellObjects());
    }
}
