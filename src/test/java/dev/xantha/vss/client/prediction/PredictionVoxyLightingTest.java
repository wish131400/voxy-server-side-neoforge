package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.compat.VoxyPredictionBridge;
import java.util.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.*;

class PredictionVoxyLightingTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    private static final VoxyPredictionBridge.Mapper NETHER = new VoxyPredictionBridge.Mapper() {
        public boolean hasSkyLight() { return false; }
        public long map(BlockState state, String biome) {
            return (state.isAir() ? 0L : state.is(Blocks.GLOWSTONE) ? 2L : 1L) << 27
                    | (long) state.getLightEmission() << 60 | 7L << 47;
        }
    };
    private static PredictionVoxySource.Tile tile(int x, List<PredictionVoxySource.Box> boxes) {
        var column = new PredictionVoxySource.Column(List.of(new PredictionVoxySource.Run(-64,0,
                Blocks.NETHERRACK.defaultBlockState())), "minecraft:nether_wastes");
        var columns = new PredictionVoxySource.Column[32*32]; Arrays.fill(columns,column);
        return new PredictionVoxySource.Tile(new PredictionTileManager.PredictionTileKey(Level.NETHER,x,0,0),
                x*32,0,32,32,1,columns,boxes,1024,true,0);
    }
    private static PredictionVoxySource.Box lamp(int x, int y, int z) {
        return new PredictionVoxySource.Box(x,y,z,x+1,y+1,z+1,Blocks.GLOWSTONE.defaultBlockState());
    }
    private static int index(int x,int y,int z) { return y<<10|z<<5|x; }
    private static int light(long[] data,int x,int y,int z) { return (int)(data[index(x,y,z)]>>>60); }

    @Test void glowstoneLightsAirWithoutInventingSkyOrChangingMaterialAndBiome() {
        try(var source=new PredictionVoxySource()) {
            source.put(tile(0,List.of(lamp(16,16,16))));
            long pos=VoxyPredictionBridge.position(0,0,0,0);
            var data=source.section(pos,NETHER);
            assertEquals(15,light(data,16,16,16)); assertEquals(14,light(data,17,16,16));
            assertEquals(1,light(data,30,16,16)); assertEquals(0,light(data,31,16,16));
            assertTrue(Arrays.stream(data).allMatch(v -> (v>>>56&15)==0));
            assertEquals(2,data[index(16,16,16)]>>>27&0xfffff);
            assertEquals(7,data[index(16,16,16)]>>>47&511);
            assertEquals(0,data[index(17,16,16)]>>>27&0xfffff,"Light in air must not create geometry");
            assertSame(data,source.section(pos,NETHER),"Illumination is cached with the section");
        }
    }
    @Test void lavaUsesItsActualEmissionAndOpaqueRockStopsTransmission() {
        int width=9, area=width*width;
        var solver=new PredictionVoxyLighting(width,1); var data=new long[width*width*width];
        for(int y=0;y<width;y++) for(int z=0;z<width;z++) {
            int i=y*area+z*width+4;
            data[i]=1L<<27;solver.opacity[i]=solver.opacity(Blocks.NETHERRACK.defaultBlockState());
        }
        data[4*area+4*width+3]=(long)Blocks.LAVA.defaultBlockState().getLightEmission()<<60|2L<<27;
        solver.propagate(data);
        assertEquals(14,data[4*area+4*width+2]>>>60);
        assertEquals(0,data[4*area+4*width+4]>>>60);
        assertEquals(0,data[4*area+4*width+5]>>>60,"A complete wall must not leak light");
    }
    @Test void aNeighboringTileLightRebuildsBothSidesWhenAddedMovedAndRemoved() {
        try(var source=new PredictionVoxySource()) {
            source.put(tile(0,List.of()));source.put(tile(1,List.of()));
            long left=VoxyPredictionBridge.position(0,0,0,0), right=VoxyPredictionBridge.position(0,1,0,0);
            var dark=source.section(left,NETHER);assertEquals(0,light(dark,31,16,16));
            source.put(tile(1,List.of(lamp(0,16,16))));
            var lit=source.section(left,NETHER);
            assertNotSame(dark,lit);assertEquals(14,light(lit,31,16,16));
            assertEquals(15,light(source.section(right,NETHER),0,16,16));
            source.put(tile(1,List.of(lamp(1,16,16))));
            assertEquals(13,light(source.section(left,NETHER),31,16,16));
            source.remove(tile(1,List.of()).key());
            assertEquals(0,light(source.section(left,NETHER),31,16,16));
            assertEquals(14,light(lit,31,16,16),"Already published snapshots remain immutable");
        }
    }
    @Test void lightAcrossVerticalSectionsIncludesSourcesAboveTheTarget() {
        try(var source=new PredictionVoxySource()) {
            source.put(tile(0,List.of(lamp(16,32,16))));
            assertEquals(14,light(source.section(VoxyPredictionBridge.position(0,0,0,0),NETHER),16,31,16));
            assertEquals(15,light(source.section(VoxyPredictionBridge.position(0,0,1,0),NETHER),16,0,16));
        }
    }
    @Test void coarseCellsAttenuateByWorldDistanceAndKeepNativeVoxelOccupancy() {
        for(int lod=1;lod<=4;lod++)try(var source=new PredictionVoxySource()) {
            int step=1<<lod;
            source.put(tile(0,List.of(lamp(0,step*2,0))));
            long pos=VoxyPredictionBridge.position(lod,0,0,0);
            var data=source.section(pos,NETHER);
            assertEquals(15,light(data,0,2,0));
            assertEquals(Math.max(0,15-step),light(data,0,3,0));
            assertEquals(2,data[index(0,2,0)]>>>27&0xfffff);
            assertEquals(0,data[index(0,3,0)]>>>27&0xfffff,"Light propagation must not add solid voxels");
            assertTrue(Arrays.stream(data).allMatch(v -> (v>>>56&15)==0));
        }
    }
    @Test void knownEmptySectionsAndMapperReplacementUseTheDimensionDefault() {
        try(var source=new PredictionVoxySource()) {
            source.put(tile(0,List.of()));long far=VoxyPredictionBridge.position(0,0,100,0);
            assertTrue(Arrays.stream(source.section(far,NETHER)).allMatch(v -> v==0));
            VoxyPredictionBridge.Mapper sky=(state,biome)->1L<<27;
            assertTrue(Arrays.stream(source.section(far,sky)).allMatch(v -> v==(15L<<56)));
            assertTrue(Arrays.stream(source.section(far,NETHER)).allMatch(v -> v==0));
        }
    }
    @Test void sectionsWithoutNearbySourcesNeverBuildALightingVolume() {
        try(var source=new PredictionVoxySource()) {
            source.put(tile(0,List.of()));long pos=VoxyPredictionBridge.position(0,0,0,0);
            assertTrue(Arrays.stream(source.section(pos,NETHER)).allMatch(v -> v==0));
            assertEquals(0L,source.diagnostics().get("lightingBuilds"));
            source.put(tile(0,List.of(lamp(16,160,16))));
            source.section(pos,NETHER);
            assertEquals(0L,source.diagnostics().get("lightingBuilds"),"A light at another height is irrelevant");
            source.put(tile(0,List.of(lamp(16,16,16))));
            source.section(pos,NETHER);assertEquals(1L,source.diagnostics().get("lightingBuilds"));
            source.section(pos,NETHER);assertEquals(1L,source.diagnostics().get("lightingBuilds"),"Cache hits never propagate again");
        }
    }
    @Test void aLampAtTheOriginAlsoIlluminatesNegativeSectionCoordinates() {
        try(var source=new PredictionVoxySource()) {
            source.put(tile(-1,List.of()));source.put(tile(0,List.of(lamp(0,16,16))));
            var data=source.section(VoxyPredictionBridge.position(0,-1,0,0),NETHER);
            assertEquals(14,light(data,31,16,16));assertEquals(13,light(data,30,16,16));
        }
    }
    @Test void severalDifferentEmissionLevelsUseTheBrightestReachablePath() {
        int width=13; var solver=new PredictionVoxyLighting(width,1);
        var data=new long[width*width*width];
        data[6*width*width+6*width+2]=4L<<60;
        data[6*width*width+6*width+10]=15L<<60;
        solver.propagate(data);
        assertEquals(10,data[6*width*width+6*width+5]>>>60);
        assertEquals(7,data[6*width*width+6*width+2]>>>60);
    }
}
