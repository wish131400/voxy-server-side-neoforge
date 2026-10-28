package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.compat.VoxyPredictionBridge;
import java.util.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PredictionVegetationHandoffTest {
    private static final long BLOCK_MASK=0xfffffL<<27;
    private static final VoxyPredictionBridge.Mapper MAPPER=(state,biome)->
            (state.is(Blocks.OAK_LOG)?2L:state.is(Blocks.OAK_LEAVES)?3L:1L)<<27;
    @BeforeAll static void bootstrap() {ClientTerrainSamplerTest.bootstrapMinecraft();}
    private static PredictionVoxySource.Box tree(int x) {
        return new PredictionVoxySource.Box(x,16,8,x+1,24,9,Blocks.OAK_LOG.defaultBlockState());
    }
    private static PredictionVoxySource.Tile tile(int lod,boolean complete,PredictionVoxySource.Box... plants) {
        int span=32<<lod,step=1<<lod,axis=32;
        var columns=new PredictionVoxySource.Column[axis*axis];
        Arrays.fill(columns,new PredictionVoxySource.Column(List.of(new PredictionVoxySource.Run(0,8,
                Blocks.STONE.defaultBlockState())),"minecraft:plains"));
        var key=new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,0,0,lod);
        return new PredictionVoxySource.Tile(key,0,0,span,axis,step,columns,List.of(plants),512,complete,0);
    }
    private static void assertTree(PredictionVoxySource source,int x) {
        long[] section=source.section(VoxyPredictionBridge.position(0,0,0,0),MAPPER);
        assertEquals(2L<<27,section[20<<10|8<<5|x]&BLOCK_MASK,"Known tree at x="+x+" must survive incomplete LOD handoff");
    }
    @Test void incompleteFineCacheCannotHideOtherKnownParentTrees() {
        try(var source=new PredictionVoxySource()) {
            source.put(tile(2,false,tree(8),tree(24)));
            assertTree(source,8);assertTree(source,24);
            source.put(tile(1,false,tree(8)));
            assertTree(source,8);assertTree(source,24);
            source.put(tile(0,false,tree(8)));
            assertTree(source,8);assertTree(source,24);
        }
    }
    @Test void partialParentChangesInvalidateUnderPartialChildren() {
        try(var source=new PredictionVoxySource()) {
            var parent=tile(1,false,tree(8));source.put(parent);
            source.put(tile(0,false,tree(8)));
            assertTree(source,8);
            var updated=tile(1,false,tree(8),tree(24));
            assertTrue(new PredictionVoxyChange(parent,updated,List.of(updated,tile(0,false,tree(8))),false)
                    .test(VoxyPredictionBridge.position(0,0,0,0)),"Partial child is not proof that the parent contributes nothing");
            source.put(updated);assertTree(source,24);
        }
    }
    @Test void completedEmptyChildAuthoritativelyClearsEarlierFallback() {
        try(var source=new PredictionVoxySource()) {
            source.put(tile(2,false,tree(8),tree(24)));
            source.put(tile(0,true));
            long[] section=source.section(VoxyPredictionBridge.position(0,0,0,0),MAPPER);
            assertEquals(0,section[20<<10|8<<5|8]&BLOCK_MASK);
            assertEquals(0,section[20<<10|8<<5|24]&BLOCK_MASK);
        }
    }
    @ParameterizedTest @ValueSource(ints={0,1,2})
    void partialHandoffRemainsStableAcrossNativeLodsAndNegativeCoordinates(int lod) {
        int step=1<<lod;
        for(int offset:new int[]{0,-128})try(var source=new PredictionVoxySource()) {
            var parent=shift(tile(2,false,tree(8),tree(24)),offset);source.put(parent);
            long pos=VoxyPredictionBridge.position(lod,Math.floorDiv(offset,32*step),0,Math.floorDiv(offset,32*step));
            for(int i=0;i<12;i++) {
                var fine=shift(tile(i%2,false,tree(8)),offset);source.put(fine);
                assertEquals(2L<<27,source.section(pos,MAPPER)[(20/step)<<10|(8/step)<<5|24/step]&BLOCK_MASK);
                source.remove(fine.key());
                assertEquals(2L<<27,source.section(pos,MAPPER)[(20/step)<<10|(8/step)<<5|24/step]&BLOCK_MASK);
            }
        }
    }
    private static PredictionVoxySource.Tile shift(PredictionVoxySource.Tile tile,int base) {
        var key=new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,base/tile.span(),base/tile.span(),tile.key().lod());
        return new PredictionVoxySource.Tile(key,base,base,tile.span(),tile.axis(),tile.step(),tile.columns(),
                tile.features(),tile.terrainBytes(),tile.surfaceComplete(),tile.cityFeatures());
    }
    @Test void completeParentRejectsPartialOverridesAndFineCompleteEmptyWins() {
        try(var source=new PredictionVoxySource()) {
            source.put(tile(2,true,tree(24)));
            var leaves=new PredictionVoxySource.Box(24,16,8,25,24,9,Blocks.OAK_LEAVES.defaultBlockState());
            source.put(tile(0,false,leaves));assertTree(source,24);
            source.put(tile(0,true));
            assertEquals(0,source.section(VoxyPredictionBridge.position(0,0,0,0),MAPPER)[20<<10|8<<5|24]&BLOCK_MASK);
        }
    }
    @Test void finerPartialStateWinsAtOverlappingVoxels() {
        try(var source=new PredictionVoxySource()) {
            source.put(tile(2,false,tree(8),tree(24)));
            var leaves=new PredictionVoxySource.Box(8,16,8,9,24,9,Blocks.OAK_LEAVES.defaultBlockState());
            source.put(tile(0,false,leaves));
            assertEquals(3L<<27,source.section(VoxyPredictionBridge.position(0,0,0,0),MAPPER)[20<<10|8<<5|8]&BLOCK_MASK);
            assertTree(source,24);
        }
    }
    @Test void nativeFacesKeepFallbackAndRefreshWhenParentPublishesMoreTrees() {
        try(var source=new PredictionVoxySource()) {
            source.put(tile(2,false,tree(0)));
            source.put(tile(0,false,tree(8)));
            long pos=VoxyPredictionBridge.position(0,0,0,0);
            assertEquals(2L<<27,source.face(pos,MAPPER,0)[20*32+8]&BLOCK_MASK);
            var taller=new PredictionVoxySource.Box(0,16,8,1,28,9,Blocks.OAK_LOG.defaultBlockState());
            source.put(tile(2,false,taller));
            assertEquals(2L<<27,source.face(pos,MAPPER,0)[26*32+8]&BLOCK_MASK);
            assertEquals(2L<<27,source.section(pos,MAPPER)[26<<10|8<<5]&BLOCK_MASK);
        }
    }
    @Test void mapProjectionKeepsPartialFallbackAndRespectsCompletedEmpty() {
        try(var source=new PredictionVoxySource()) {
            source.put(tile(2,false,tree(8),tree(24)));
            source.put(tile(0,false,tree(8)));
            var surface=source.mapChunk(1,0);
            assertFalse(surface.quality().complete());
            assertTrue(surface.pixels()[8*16+8].runs().stream().anyMatch(r->r.state().is(Blocks.OAK_LOG)));
            source.put(tile(0,true));
            var empty=source.mapChunk(1,0);
            assertTrue(empty.quality().complete());
            assertFalse(empty.pixels()[8*16+8].runs().stream().anyMatch(r->r.state().is(Blocks.OAK_LOG)));
        }
    }
}
