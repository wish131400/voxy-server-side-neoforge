package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PredictionExteriorColumnsTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    static int rock() { return BuiltInRegistries.BLOCK.getId(Blocks.STONE); }
    static ClientColumnSample surface(int y) {
        return new ClientColumnSample(y,y,0,rock(),0,0,0,0,0,ClientColumnSample.FLAG_SURFACE_ONLY,
                0,rock(),rock(),ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN,
                ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN);
    }
    static PredictionColumnVolume runs(int... spans) {
        int[] data = new int[spans.length * 2];
        for (int i=0;i<spans.length;i+=2) {
            data[i*2]=spans[i];data[i*2+1]=spans[i+1];data[i*2+2]=rock();
        }
        return new PredictionColumnVolume(data);
    }
    static ClientColumnSample profiled(int top, PredictionColumnVolume column, int spacing) {
        var s=PredictionExteriorColumns.capture(surface(top),-64,47,y->column.occupied(y,false));
        return PredictionExteriorColumns.withProfile(s,s.volume(),47,spacing);
    }
    static ClientTerrainSampler sampler(java.util.function.BiFunction<Integer,Integer,PredictionColumnVolume> columns) {
        var profile=new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                net.minecraft.resources.ResourceLocation.withDefaultNamespace("overworld"),-64,384,"noise","minecraft:overworld",0);
        return new ClientTerrainSampler(5052304137288917019L,profile) {
            @Override PredictionColumnVolume exteriorColumn(int x,int z) { return columns.apply(x,z); }
        };
    }

    @Test void emptyProofRangeDoesNotQueryBelowTheDimensionFloor() {
        var sampler=sampler((x,z)->{fail("no column is needed for an empty height range");return null;});
        assertNull(sampler.exteriorFootprint(0,0,1,-64,-64,()->true));
    }

    @Test void savedMountainColumnsDoNotGrowWallsAcrossAnyConfirmedGap() throws Exception {
        JsonObject corpus;
        try (var in=getClass().getResourceAsStream("/prediction/mountain-5052304137288917019.json")) {
            assertNotNull(in);corpus=JsonParser.parseReader(new java.io.InputStreamReader(in,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        }
        int checked=0;
        for (var entry:corpus.getAsJsonArray("columns")) {
            var row=entry.getAsJsonObject();int top=row.get("predictedTop").getAsInt();
            var occupied=row.getAsJsonArray("occupiedRuns");
            var sample=PredictionExteriorColumns.capture(surface(top),-64,47,y->{
                for(var run:occupied) if(y>=run.getAsJsonArray().get(0).getAsInt() && y<run.getAsJsonArray().get(1).getAsInt())return true;
                return false;
            });
            assertTrue(PredictionExteriorColumns.profiled(sample));
            var data=new ClientColumnSample[]{sample,surface(47),sample,surface(47)};
            var mesh=PredictionMeshBuilder.build(data,null,63,0,1,2,false);
            for(var entryGap:row.getAsJsonArray("exteriorGaps")) {
                var gap=entryGap.getAsJsonArray();int low=gap.get(0).getAsInt(),high=gap.get(1).getAsInt();
                assertNoWall(mesh,low,high);
                assertTrue(hasHorizontal(mesh,low,1),"missing floor at "+low+" "+row);
                assertTrue(hasHorizontal(mesh,high,-1),"missing underside at "+high+" "+row);
            }
            checked++;
        }
        assertEquals(52,checked);
    }

    @Test void allSolidIntervalsAndSuspendedUndersidesSurvive() {
        var s=profiled(194,runs(-64,63,112,120,157,171,192,194),1);
        var spans=PredictionWallEvidence.exposed(s,surface(47),194,47,1);
        assertEquals(List.of(new PredictionLodSeams.HeightSpan(47,63),new PredictionLodSeams.HeightSpan(112,120),
                new PredictionLodSeams.HeightSpan(157,171),new PredictionLodSeams.HeightSpan(192,194)),spans);
        var mesh=PredictionMeshBuilder.build(new ClientColumnSample[]{s,surface(47),s,surface(47)},null,63,0,1,2,false);
        for(int y:new int[]{112,157,192}) assertTrue(hasHorizontal(mesh,y,-1));
        var floating=profiled(150,runs(120,150),1);
        assertTrue(hasHorizontal(PredictionMeshBuilder.build(new ClientColumnSample[]{floating,floating,floating,floating},
                null,63,0,1,2,false),120,-1));
    }

    @Test void coarseAirNeedsTheEntireFootprintAndCannotReuseFineProof() {
        for(int step:new int[]{1,2,4}) {
            var counter=new AtomicInteger();
            var samples=new ClientColumnSample[9];Arrays.fill(samples,surface(180));samples[0]=surface(64);
            var sampler=sampler((x,z)->{counter.incrementAndGet();return x==0&&z==0?runs(-64,64,173,180):runs(-64,180);});
            PredictionExteriorColumns.enrich(samples,3,step,0,0,sampler,()->true);
            assertEquals(step==1,PredictionExteriorColumns.profiled(samples[4]));
            int calls=counter.get();PredictionExteriorColumns.enrich(samples,3,step,0,0,sampler,()->true);
            assertEquals(calls,counter.get(),"cached column checks must not repeat");
        }
        var fine=profiled(180,runs(-64,64,173,180),1);
        assertFalse(PredictionWallEvidence.hasInterior(fine,2));
        assertEquals(List.of(new PredictionLodSeams.HeightSpan(64,180)),PredictionWallEvidence.intervals(fine,64,180,2));
    }

    @Test void roofFloodReachesFlatInteriorAndFlatTerrainDoesNoExtraSampling() {
        int grid=12;var samples=new ClientColumnSample[grid*grid];Arrays.fill(samples,surface(180));samples[0]=surface(64);
        var calls=new AtomicInteger();var sampler=sampler((x,z)->{calls.incrementAndGet();return runs(-64,64,173,180);});
        PredictionExteriorColumns.enrich(samples,grid,1,0,0,sampler,()->true);
        assertTrue(PredictionExteriorColumns.profiled(samples[samples.length-1]));
        assertTrue(calls.get()<=samples.length);
        Arrays.fill(samples,surface(180));calls.set(0);
        assertEquals(0,PredictionExteriorColumns.enrich(samples,grid,1,0,0,sampler,()->true));
        assertEquals(0,calls.get());
        samples[0]=surface(64);
        assertThrows(java.util.concurrent.CancellationException.class,
                ()->PredictionExteriorColumns.enrich(samples,grid,1,0,0,sampler,()->false));
    }

    @Test void inconsistentOrUnsupportedGeneratorDoesNotPunchHoles() {
        var s=surface(180);
        assertSame(s,PredictionExteriorColumns.capture(s,-64,47,y->y<160));
        var samples=new ClientColumnSample[9];Arrays.fill(samples,s);samples[0]=surface(64);
        assertEquals(0,PredictionExteriorColumns.enrich(samples,3,1,0,0,sampler((x,z)->null),()->true));
        assertSame(s,samples[4]);
    }

    @Test void exteriorCaptureDoesNotReplaceInteriorDimensionVolumeSampling() {
        var section=ClientCaptureExtractorTest.section();
        section.setBlockState(8,15,8,Blocks.COARSE_DIRT.defaultBlockState());
        section.setBlockState(8,0,8,Blocks.STONE.defaultBlockState());
        var data=new dev.xantha.vss.api.VoxelColumnData(new dev.xantha.vss.api.VoxelColumnData.SectionData[]{
                new dev.xantha.vss.api.VoxelColumnData.SectionData(4,section,null,null)},1L,true);
        var exterior=sampler((x,z)->runs(-64,64,79,80));
        var interior=new ClientTerrainSampler(42L,exterior.profile()) {
            @Override boolean interiorTerrain() { return true; }
            @Override public ClientColumnSample sampleSurface(int x,int z) { return surface(80); }
        };
        assertTrue(PredictionExteriorColumns.profiled(ClientCaptureExtractor.extract(0,0,data,exterior)));
        var captured=ClientCaptureExtractor.extract(0,0,data,interior);
        assertTrue(captured.captured());
        assertNull(captured.volume(),"interior scheduler must still obtain its complete volume");
    }

    @Test void displayProfilesKeepSnowWaterAndMaterialMetadata() {
        var base=new ClientColumnSample(180,185,7,rock(),3,1,12,8,1,
                ClientColumnSample.FLAG_SURFACE_ONLY | ClientColumnSample.FLAG_APPROXIMATE
                        | ClientColumnSample.FLAG_DISPLAY | ClientColumnSample.FLAG_SNOW,
                2,rock(),rock(),ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN,
                ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN);
        var samples=new ClientColumnSample[9];Arrays.fill(samples,base);samples[0]=surface(64);
        assertTrue(PredictionExteriorColumns.enrich(samples,3,1,0,0,
                sampler((x,z)->runs(-64,64,173,180)),()->true)>0);
        var s=samples[4];
        assertTrue(PredictionExteriorColumns.profiled(s));
        assertTrue(s.snow());assertTrue(s.hasFluid());assertTrue(s.approximate());
        assertEquals(base.flags(),s.flags() & ~PredictionExteriorColumns.CHECKED);
        assertEquals(base.fluidY(),s.fluidY());assertEquals(base.biomeIndex(),s.biomeIndex());
        assertEquals(base.topBlockIndex(),s.topBlockIndex());assertEquals(base.groundFeatureKind(),s.groundFeatureKind());
    }

    @Test void lowerFloorSnowCannotCutTheSuspendedMountainAboveIt() {
        var s=profiled(180,runs(-64,65,112,120,173,180),1);
        var samples=new ClientColumnSample[9];Arrays.fill(samples,s);
        for(int y:new int[]{64,112}) {
            var vegetation=PredictionVegetation.Tile.of(Map.of(new net.minecraft.core.BlockPos(0,y,0),
                    Blocks.SNOW.defaultBlockState()),0,0,2,1,1);
            var mesh=PredictionMeshBuilder.build(samples,null,63,0,1,3,false,null,null,null,0,0,vegetation);
            assertNoWall(mesh,65,112);assertNoWall(mesh,120,173);
            assertTrue(hasHorizontal(mesh,180,1));
            assertTrue(hasHorizontal(mesh,173,-1));
            // The edited column must retain the same upper roof, not only its unedited neighbors.
            boolean roof=false;
            for(int v=0;v<mesh.vertexCount();v+=6) if(mesh.normalY(v)==1&&mesh.y(v)==180
                    &&mesh.x(v)==0&&mesh.z(v)==0)roof=true;
            assertTrue(roof,"lower placement at "+y+" removed upper roof");
        }
    }

    @Test void placementAtTheExactRoofBottomCannotRestoreASolidHeightfield() {
        // Live report at 1610,-1460: air 98..160, one-block roof 160..161.
        // A surface replacement at 160 used to erase the profile and grow a 62-block dirt wall.
        for (int step : new int[]{1,2,4}) {
            var s=profiled(161,runs(-64,63,71,98,160,161),step);
            var samples=new ClientColumnSample[9];Arrays.fill(samples,s);
            var vegetation=PredictionVegetation.Tile.of(Map.of(new net.minecraft.core.BlockPos(0,160,0),
                    Blocks.DIRT.defaultBlockState()),0,0,2*step,step,1);
            var mesh=PredictionMeshBuilder.build(samples,null,63,0,step,3,false,null,null,null,0,0,vegetation);
            assertNoWall(mesh,98,160);
            var packed=mesh.compactForRendering().packed();
            for(int q=0;q<packed.quadCount();q++) {
                if(packed.normalY(q,0)!=0)continue;
                float low=Float.POSITIVE_INFINITY,high=Float.NEGATIVE_INFINITY;
                for(int c=0;c<4;c++){low=Math.min(low,packed.y(q,c));high=Math.max(high,packed.y(q,c));}
                assertFalse(low<160&&high>98,"packed wall bridges the live mountain air");
            }
        }
    }

    @Test void surfaceCutsNeverExtrudeAirUsingSnowOrFoliageTint() {
        int air=BuiltInRegistries.BLOCK.getId(Blocks.AIR);
        int grass=BuiltInRegistries.BLOCK.getId(Blocks.GRASS_BLOCK);
        var raw=new ClientColumnSample(180,180,0,grass,0,0,0,0,0,
                ClientColumnSample.FLAG_SURFACE_ONLY | ClientColumnSample.FLAG_SNOW,0,air,air,
                ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN);
        for(int tint:new int[]{0xfff9fefe,0xff345a39}) {
            var samples=new ClientColumnSample[9];Arrays.fill(samples,raw);
            var colors=new int[9];Arrays.fill(colors,tint);
            var vegetation=PredictionVegetation.Tile.of(Map.of(new net.minecraft.core.BlockPos(0,110,0),
                    Blocks.AIR.defaultBlockState()),0,0,2,1,1);
            var mesh=PredictionMeshBuilder.build(samples,colors,63,0,1,3,false,null,null,null,0,0,vegetation);
            boolean found=false;
            for(int v=0;v<mesh.vertexCount();v+=6) if(mesh.normalY(v)==1&&mesh.y(v)==110) {
                found=true;
                int expected=Math.round(138 * PredictionLighting.combine(3,15) / 15F);
                assertEquals(expected * 0x010101,mesh.color(v)&0xffffff,
                        "cut floor must use shaded stone, not air or old surface tint");
            }
            assertTrue(found);
        }
    }

    @Test void multiRunDataPersistsInBothCaches(@TempDir Path directory) {
        var s=profiled(194,runs(-64,63,112,120,157,171,192,194),2);
        var key=PredictionDiskCache.Key.terrain(-72,-25,1);
        try(var cache=new PredictionDiskCache(directory.resolve("tiles"),17);var lease=cache.lease(key)) {
            assertTrue(cache.writeTerrain(lease,new ClientColumnSample[]{s}));
        }
        try(var cache=new PredictionDiskCache(directory.resolve("tiles"),17);var lease=cache.lease(key)) {
            assertEquals(s,cache.readTerrain(lease,1)[0]);
        }
        try(var cache=new PredictionSampleStore(directory.resolve("samples"),17)) { cache.put(123,s); }
        try(var cache=new PredictionSampleStore(directory.resolve("samples"),17)) { assertEquals(s,cache.get(123)); }
    }

    @Test void mixedLodSeamsPreserveMultiLayerAirAndEqualHeightSideFaces() {
        for(int step:new int[]{1,2}) {
            var high=profiled(194,runs(-64,63,112,120,157,171,192,194),step);
            var low=surface(63);
            var a=tile(-1,step,high); var b=tile(0,step*2,low);
            var surfaces=List.of(PredictionLodSeamsTest.surface(a),PredictionLodSeamsTest.surface(b));
            var seams=new PredictionLodSeams();var patches=seams.update(surfaces);
            assertFalse(patches.isEmpty());
            for(var p:patches)for(int q=0;q<p.mesh().quadCount();q++) {
                var words=p.mesh().quads();
                float lo=((words[q*12+5]&65535)-32768)/4F,hi=((words[q*12+4]&65535)-32768)/4F;
                for(int[] gap:new int[][]{{63,112},{120,157},{171,192}})
                    assertFalse(lo<gap[1]&&hi>gap[0],"seam bridges air "+lo+".."+hi);
            }
            // Stale full-height edge walls can be replaced, then restored when the neighbor disappears.
            assertTrue(PredictionBoundaryWalls.replaced(seams.boundaryMask(a.key()),63,64,step,64*step,true));
            seams.update(List.of(surfaces.get(0)));
            assertFalse(PredictionBoundaryWalls.replaced(seams.boundaryMask(a.key()),63,64,step,64*step,true));
            var sameTop=surface(194);
            var equal=new PredictionLodSeams().update(List.of(PredictionLodSeamsTest.surface(tile(-1,step,high)),
                    PredictionLodSeamsTest.surface(tile(0,step*2,sameTop))));
            assertFalse(equal.isEmpty(),"solid neighbor has side faces inside the opening even with equal top heights");
        }
    }

    static PredictionTileManager.PredictionTile tile(int x,int step,ClientColumnSample sample) {
        var samples=new ClientColumnSample[65*65];Arrays.fill(samples,sample);
        int[] heights=new int[samples.length];Arrays.fill(heights,sample.surfaceY());
        var mesh=PredictionMeshBuilder.build(samples,null,63,0,step,65,false).compactForRendering();
        var tile=new PredictionTileManager.PredictionTile(new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,x,0,Integer.numberOfTrailingZeros(step)),
                heights,heights,samples,mesh,PredictionDepthBound.fromSamples(samples),0,1,64,step);
        mesh.prepareGpuPayload(tile);return tile;
    }
    static void assertNoWall(PredictionMesh mesh,int low,int high) {
        for(int v=0;v<mesh.vertexCount();v+=6) {
            if(mesh.normalY(v)!=0)continue;
            float min=Float.POSITIVE_INFINITY,max=Float.NEGATIVE_INFINITY;
            for(int j=0;j<6;j++){min=Math.min(min,mesh.y(v+j));max=Math.max(max,mesh.y(v+j));}
            assertFalse(min<high&&max>low,"wall inside air: "+min+".."+max+" vs "+low+".."+high);
        }
    }
    static boolean hasHorizontal(PredictionMesh mesh,int y,int normal) {
        for(int v=0;v<mesh.vertexCount();v++)if(mesh.y(v)==y&&mesh.normalY(v)==normal)return true;
        return false;
    }
}
