package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import dev.xantha.vss.compat.VoxyPredictionBridge;
import dev.xantha.vss.client.prediction.PredictionVoxySource.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.*;

class PredictionPublicationLocalityTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    private static final VoxyPredictionBridge.Mapper MAPPER = (state, biome) ->
            (long)(net.minecraft.world.level.block.Block.getId(state)+1) << 27
                    | (biome.equals("minecraft:desert") ? 1L << 47 : 0);
    private static Column column(int top) {
        return new Column(List.of(new Run(-64,top,Blocks.STONE.defaultBlockState())),"minecraft:plains");
    }
    private static Tile tile(Column[] columns, List<Box> features) {
        return new Tile(new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,-1,-1,0),
                -64,-64,64,64,1,columns,features,4096,true,0);
    }
    @Test void surfaceEditRetainsDeepSectionsAndFacesBeforeAnyComposition() {
        var columns=new Column[4096]; Arrays.fill(columns,column(130));
        var changed=columns.clone(); changed[0]=column(131);
        try(var source=new PredictionVoxySource()) {
            source.put(tile(columns,List.of()));
            long deep=VoxyPredictionBridge.position(0,-2,1,-2),surface=VoxyPredictionBridge.position(0,-2,4,-2);
            var retained=source.mappedSection(deep,MAPPER); var old=source.section(surface,MAPPER).clone();
            var border=source.face(VoxyPredictionBridge.position(0,-2,2,-2),MAPPER,0);
            long faceBuilds=(long)source.diagnostics().get("faceBuilds");
            source.put(tile(changed,List.of()));
            assertSame(retained,source.mappedSection(deep,MAPPER));
            assertArrayEquals(border,source.face(VoxyPredictionBridge.position(0,-2,2,-2),MAPPER,0));
            assertEquals(faceBuilds,source.diagnostics().get("faceBuilds"));
            assertFalse(Arrays.equals(old,source.section(surface,MAPPER)));
        }
    }
    @Test void biomeAnchorStillRecolorsPlantsFarAboveItsTerrain() {
        var columns=new Column[4096]; Arrays.fill(columns,column(8));
        var changed=columns.clone(); changed[0]=new Column(columns[0].runs(),"minecraft:desert");
        var boxes=List.of(new Box(0,120,0,1,150,1,Blocks.OAK_LEAVES.defaultBlockState()));
        long pos=VoxyPredictionBridge.position(0,-2,4,-2);
        try(var source=new PredictionVoxySource();var fresh=new PredictionVoxySource()) {
            source.put(tile(columns,boxes)); var old=source.section(pos,MAPPER).clone();
            source.put(tile(changed,boxes));fresh.put(tile(changed,boxes));
            assertFalse(Arrays.equals(old,fresh.section(pos,MAPPER)));
            assertArrayEquals(fresh.section(pos,MAPPER),source.section(pos,MAPPER));
        }
    }
    @Test void intervalDifferenceMatchesDenseStateChangesAndPreservesFluidMarkers() {
        var random=new Random(721);
        for(int round=0;round<100;round++) {
            var a=new ArrayList<Run>();var b=new ArrayList<Run>();
            for(var runs:List.of(a,b))for(int i=0;i<8;i++) {
                int bottom=random.nextInt(96)-48,top=bottom+1+random.nextInt(24);
                var state=switch(random.nextInt(3)) {case 0->Blocks.STONE;case 1->Blocks.DIRT;default->Blocks.AIR;};
                runs.add(new Run(bottom,top,state.defaultBlockState()));
            }
            var delta=new PredictionTerrainChange();delta.compare(new Column(a,"a"),new Column(b,"a"));
            for(int y=-64;y<96;y++) {
                var sa=Blocks.AIR.defaultBlockState();var sb=sa;
                for(var run:a)if(y>=run.bottom() && y<run.top())sa=run.state();
                for(var run:b)if(y>=run.bottom() && y<run.top())sb=run.state();
                if(sa!=sb)assertTrue(y>=delta.bottom() && y<delta.top());
            }
        }
        var water=Blocks.WATER.defaultBlockState();
        var delta=new PredictionTerrainChange();
        delta.compare(new Column(List.of(new Run(0,16,water)),"a"),
                new Column(List.of(new Run(0,8,water),new Run(8,16,water)),"a"));
        assertTrue(delta.bottom()<=7 && delta.top()>=16);
    }
    @Test void cachedPublicationMatchesFreshCompositionAtEveryNativeLod() {
        var random=new Random(2721);
        for(int round=0;round<8;round++) {
            var columns=new Column[4096];Arrays.fill(columns,column(130));
            var changed=columns.clone();
            for(int i=0;i<8;i++) {
                int index=random.nextInt(changed.length),top=random.nextInt(220)-32;
                changed[index]=new Column(List.of(new Run(-64,top,Blocks.STONE.defaultBlockState()),
                        new Run(top,top+3,(i%2==0?Blocks.WATER:Blocks.DIRT).defaultBlockState())),
                        i%3==0?"minecraft:desert":"minecraft:plains");
            }
            try(var source=new PredictionVoxySource();var fresh=new PredictionVoxySource()) {
                source.put(tile(columns,List.of()));fresh.put(tile(changed,List.of()));
                var positions=new ArrayList<Long>();
                for(int lod=0;lod<=4;lod++)for(int y=-2;y<=6;y++) {
                    long pos=VoxyPredictionBridge.position(lod,-1,y,-1);positions.add(pos);source.section(pos,MAPPER);
                }
                source.put(tile(changed,List.of()));
                for(long pos:positions)assertArrayEquals(fresh.section(pos,MAPPER),source.section(pos,MAPPER),
                        "lod="+VoxyPredictionBridge.level(pos)+", y="+VoxyPredictionBridge.y(pos));
            }
        }
    }
}
