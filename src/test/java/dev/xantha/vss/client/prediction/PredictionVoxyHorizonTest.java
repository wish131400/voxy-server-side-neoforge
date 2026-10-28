package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.compat.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.*;
import java.util.*;

class PredictionVoxyHorizonTest {
    @BeforeAll static void bootstrap() {ClientTerrainSamplerTest.bootstrapMinecraft();}
    private static final VoxyPredictionBridge.Mapper MAPPER=(state,biome)->1L<<27;
    private static boolean solid(long value) {return (value&(0xfffffL<<27))!=0;}
    private static void ancestor(PredictionVoxySource source) {
        var col=new PredictionVoxySource.Column(List.of(new PredictionVoxySource.Run(0,128,
                Blocks.STONE.defaultBlockState())),"minecraft:plains");
        var columns=new PredictionVoxySource.Column[64];Arrays.fill(columns,col);
        var key=new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,0,0,7);
        source.put(new PredictionVoxySource.Tile(key,0,0,8192,8,1024,columns,List.of(),256));
    }
    @Test void stableMaskPreservesTheWholeRequestedCircleAtSignedCellCorners() {
        for(double x:new double[]{0,63.999,-64,-.001,3761})for(double z:new double[]{0,-2460,63.999}) {
            var mask=PredictionVoxyHorizon.of(x,z,4096);
            for(int i=0;i<360;i++) {
                double angle=Math.toRadians(i);
                assertTrue(mask.intersects(x+4096*Math.cos(angle),z+4096*Math.sin(angle),0));
            }
            assertFalse(mask.intersects(x+4300,z,1));
        }
        assertEquals(PredictionVoxyHorizon.of(-64,0,4096),PredictionVoxyHorizon.of(-.001,63.999,4096));
        assertTrue(PredictionVoxyHorizon.of(0,0,4096).changes(PredictionVoxyHorizon.of(63.999,63.999,4096)).isEmpty());
    }
    @Test void ancestorOutsidePredictionDistanceIsAirAndMovementInvalidatesOnlyItsBoundary() {
        try(var source=new PredictionVoxySource()) {
            ancestor(source);
            long inner=VoxyPredictionBridge.position(4,0,0,0),edge=VoxyPredictionBridge.position(4,8,0,0),
                    outer=VoxyPredictionBridge.position(4,12,0,0);
            var startup=source.mappedSection(inner,MAPPER);
            assertTrue(solid(source.section(outer,MAPPER)[0]));
            source.window(0,0,4096);
            assertSame(startup,source.mappedSection(inner,MAPPER),"Starting the mask keeps reusable interior sections");
            var edgeData=source.mappedSection(edge,MAPPER);
            assertTrue(solid(edgeData.array()[0]));
            assertFalse(solid(edgeData.array()[16]));
            assertFalse(solid(source.section(outer,MAPPER)[0]),"An 8192-block parent cannot draw beyond the 4096-block horizon");
            var expected=new long[1024];edgeData.copyFace(2,expected);
            assertArrayEquals(expected,source.face(edge,MAPPER,2));
            source.window(31,31,4096);
            assertSame(edgeData,source.mappedSection(edge,MAPPER),"Sub-cell movement must not remesh the horizon");
            source.window(2048,0,4096);
            assertSame(startup,source.mappedSection(inner,MAPPER));
            assertTrue(solid(source.section(edge,MAPPER)[16]),"Entering the circle restores the ancestor while detail is generated");
            assertTrue(solid(source.face(edge,MAPPER,2)[16]));
            source.window(0,0,1024);
            assertFalse(solid(source.section(edge,MAPPER)[16]),"Changing distance must retire mapped sections and faces");
            assertFalse(solid(source.face(edge,MAPPER,2)[16]));
        }
    }
    @Test void indexedBoundaryBoxesCoverAffectedSectionsAndTheirFaceHalo() {
        assertTrue(PredictionVoxyHorizon.of(0,0,1024).affected(VoxyPredictionBridge.position(0,33,0,1),
                PredictionVoxyHorizon.of(64,0,1024)),"A light halo can cross the mask while the section geometry is inside");
        var before=PredictionVoxyHorizon.of(-3761,-2460,4096);
        for(var next:List.of(PredictionVoxyHorizon.of(-3690,-2460,4096),
                PredictionVoxyHorizon.of(12000,24000,4096),PredictionVoxyHorizon.of(-3761,-2460,1024))) {
            var boxes=before.changes(next);assertFalse(boxes.isEmpty());
            var random=new Random(914);
            for(int i=0;i<12000;i++) {
                int lod=i%5,span=32<<lod;
                var center=i%2==0 ? before : next;
                double angle=random.nextDouble()*Math.PI*2,radius=center.radius()+(random.nextDouble()-.5)*span*3;
                int x=(int)Math.floor((center.x()+Math.cos(angle)*radius)/span),
                        z=(int)Math.floor((center.z()+Math.sin(angle)*radius)/span);
                long pos=VoxyPredictionBridge.position(lod,x,0,z);
                if(!before.affected(pos,next))continue;
                double bx=(double)x*span,bz=(double)z*span;
                assertTrue(boxes.stream().anyMatch(b->bx<b.x()+b.span()+16D && bx+span>b.x()-16D
                        && bz<b.z()+b.span()+16D && bz+span>b.z()-16D),"Missed native section or neighbor face");
            }
            assertFalse(before.affected(VoxyPredictionBridge.position(0,-118,0,-77),before));
        }
    }
    @Test void maximumConfiguredDistanceVisitsBoundarySubtreesInsteadOfTheWholeDisk() {
        var before=PredictionVoxyHorizon.of(0,0,65536);
        assertTrue(before.changes(PredictionVoxyHorizon.of(64,0,65536)).size()<2500);
        assertTrue(before.changes(PredictionVoxyHorizon.of(1_000_000,-1_000_000,65536)).size()<6000,
                "A teleport must compress complete changed subtrees into indexed boxes");
    }
}
