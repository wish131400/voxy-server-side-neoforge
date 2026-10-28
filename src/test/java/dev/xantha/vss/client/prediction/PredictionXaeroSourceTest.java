package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.*;

class PredictionXaeroSourceTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    private static PredictionVoxySource.Tile tile(int lod,int step,int top,boolean complete,
                                                 List<PredictionVoxySource.Box> features) {
        int axis=64,span=axis*step;
        var columns=new PredictionVoxySource.Column[axis*axis];
        Arrays.fill(columns,new PredictionVoxySource.Column(List.of(new PredictionVoxySource.Run(-64,top,
                Blocks.GRASS_BLOCK.defaultBlockState())),"minecraft:forest"));
        return new PredictionVoxySource.Tile(new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,-1,-1,lod),
                -span,-span,span,axis,step,columns,features,256,complete,0);
    }
    @Test void negativeCoordinatesKeepCompleteParentPlantsUntilFineSurfaceCompletes() {
        try(var source=new PredictionVoxySource()) {
            var tree=new PredictionVoxySource.Box(112,64,112,128,76,128,Blocks.OAK_LEAVES.defaultBlockState());
            source.put(tile(1,2,64,true,List.of(tree)));
            source.put(tile(0,1,60,false,List.of()));
            var surface=source.mapChunk(-1,-1);
            assertNotNull(surface); assertEquals(1,surface.quality().terrainStep());
            assertEquals(2,surface.quality().featureStep()); assertTrue(surface.quality().complete());
            assertTrue(surface.pixels()[0].runs().stream().anyMatch(r -> r.state().is(Blocks.OAK_LEAVES)));
            assertEquals(60,surface.pixels()[0].runs().get(0).top());
            source.put(tile(0,1,60,true,List.of()));
            assertFalse(surface.current().getAsBoolean());
            var updated=source.mapChunk(-1,-1);
            assertEquals(1,updated.quality().featureStep());
            assertFalse(updated.pixels()[0].runs().stream().anyMatch(r -> r.state().is(Blocks.OAK_LEAVES)));
            assertNull(source.mapChunk(0,0));
        }
    }
    @Test void unrelatedPublicationDoesNotRevokeChunkButLocalChangesAndCloseDo() {
        var source=new PredictionVoxySource(); source.put(tile(0,1,60,false,List.of()));
        var surface=source.mapChunk(-1,-1);
        var far=tile(4,16,40,false,List.of());
            source.put(new PredictionVoxySource.Tile(new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,4,4,4),4096,4096,far.span(),far.axis(),far.step(),
                far.columns(),far.features(),far.bytes()));
        assertTrue(surface.current().getAsBoolean());
        source.remove(tile(0,1,60,false,List.of()).key());
        assertFalse(surface.current().getAsBoolean()); source.close();
        assertNull(source.mapChunk(-1,-1));
    }
    @Test void giantLodIsOneAreaAndOnlyRequestedChunkIsProjected() {
        try(var source=new PredictionVoxySource()) {
            source.put(tile(18,262144,64,false,List.of()));
            assertEquals(1,source.mapAreas().size());
            var area=source.mapAreas().get(0);
            assertEquals(-1048576,area.minX()); assertEquals(0,area.maxX());
            assertEquals(256,source.mapChunk(-900000,-800000).pixels().length);
        }
    }
    @Test void publicationUpdatesAreaRevisionWithoutMappingVoxySections() {
        try(var source=new PredictionVoxySource()) {
            source.put(tile(0,1,60,false,List.of())); long before=source.mapAreas().get(0).revision();
            source.put(tile(0,1,60,true,List.of()));
            assertTrue(source.mapAreas().get(0).revision()>before);
            source.mapChunk(-1,-1);
            assertEquals(0L,source.diagnostics().get("sectionBuilds"));
        }
    }
    @Test void rowMajorTerrainBecomesXMajorMapPixelsWithoutTransposingTreesOrBiomes() {
        try(var source=new PredictionVoxySource()) {
            var tile=tile(0,1,60,false,List.of());
            tile.columns()[57*64+53]=new PredictionVoxySource.Column(List.of(new PredictionVoxySource.Run(-64,90,
                    Blocks.SAND.defaultBlockState())),"minecraft:desert");
            source.put(tile);
            var result=source.mapChunk(-1,-1);
            assertEquals("minecraft:desert",result.pixels()[5*16+9].biome());
            assertEquals(90,result.pixels()[5*16+9].runs().get(0).top());
            assertEquals("minecraft:forest",result.pixels()[9*16+5].biome());
        }
    }
}
