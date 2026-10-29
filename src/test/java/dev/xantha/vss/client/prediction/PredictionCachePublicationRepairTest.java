package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.compat.VoxyPredictionBridge;
import dev.xantha.vss.client.prediction.PredictionVoxySource.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PredictionCachePublicationRepairTest {
    @TempDir Path directory;
    @BeforeAll static void bootstrap() {ClientTerrainSamplerTest.bootstrapMinecraft();}
    @AfterEach void finishWrites() throws Exception {PredictionCacheTestFiles.awaitBackgroundClose();}

    @Test void bytePressureAdmitsNewDemandInsteadOfRepeatedlyEvictingItself() {
        VoxyPredictionBridge.Mapper mapper=(state,biome)->(long)(Block.getId(state)+1)<<27;
        var columns=new Column[4096];var random=new Random(7729);
        for(int i=0;i<columns.length;i++)columns[i]=new Column(List.of(
                new Run(-64,4+random.nextInt(27),Blocks.STONE.defaultBlockState())),"minecraft:plains");
        try(var source=new PredictionVoxySource(32768,256L*1024)) {
            for(int x=0;x<2;x++)source.put(new Tile(
                    new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,x*2,0,0),
                    x*128,0,64,64,1,columns,List.of(),4096,true,0));
            long old=VoxyPredictionBridge.position(0,0,0,0),recent=VoxyPredictionBridge.position(0,4,0,0);
            source.mappedSection(old,mapper);source.mappedSection(old,mapper);
            var data=source.mappedSection(recent,mapper);
            long builds=(long)source.diagnostics().get("sectionBuilds");
            for(int i=0;i<20;i++)assertSame(data,source.mappedSection(recent,mapper));
            assertEquals(builds,source.diagnostics().get("sectionBuilds"));
            assertTrue((long)source.diagnostics().get("cacheEvictions")>0);
            assertTrue((long)source.diagnostics().get("mappedBytes")<=(long)source.diagnostics().get("sectionCacheLimit"));
            assertTrue((long)source.diagnostics().get("protectedBytes")<=(long)source.diagnostics().get("sectionCacheLimit")*3/4);
            source.releaseMapper(mapper);assertEquals(0L,source.diagnostics().get("protectedBytes"));
            assertEquals(0L,source.diagnostics().get("mappedBytes"));
            System.out.println("CACHE_REPAIR_REPLAY repeatedReads=20 addedBuilds="+((long)source.diagnostics().get("sectionBuilds")-builds));
        }
    }

    @Test void partialSurfaceDiskRestoreSkipsBusyAndMissingSourcesWithoutGenerationThenCompletes() throws Exception {
        var calls=new AtomicInteger();var sampler=PredictionSurfaceRetryTest.terrain(calls);
        try(var disk=new PredictionDiskCache(directory,77)) {
            var vegetation=new PredictionVegetation(sampler,disk,false);
            int settings=3|PredictionVegetation.runtimeSettings(sampler.profile().dimension());
            var tree=Map.of(new BlockPos(1,101,1),Blocks.OAK_LOG.defaultBlockState());
            try(var lease=disk.lease(PredictionDiskCache.Key.surface(0,0,settings))) {assertTrue(disk.writeSurface(lease,tree,true));}
            var field=PredictionVegetation.class.getDeclaredField("generationJobs");field.setAccessible(true);
            @SuppressWarnings("unchecked")var jobs=(Map<Long,Object>)field.get(vegetation);
            jobs.put(PredictionVegetationDisplayCache.chunkKey(-1,-1),PredictionSurfaceRetryTest.generationJob());
            var restored=vegetation.restoreForRendering(0,0,16,1,true,ignored->false,()->true);
            assertFalse(restored.complete());assertTrue(restored.tile().voxelCount()>0);
            assertEquals(0,calls.get(),"A busy or missing source must not start placement in the cache lane");
            assertEquals(0L,vegetation.performanceSnapshot().get("generatedChunks"));
            jobs.clear();
            for(int z=-1;z<=1;z++)for(int x=-1;x<=1;x++)if(x!=0 || z!=0)
                try(var lease=disk.lease(PredictionDiskCache.Key.surface(x,z,settings))) {assertTrue(disk.writeSurface(lease,Map.of(),true));}
            var complete=vegetation.restoreForRendering(0,0,16,1,true,ignored->false,()->true);
            assertTrue(complete.complete());assertEquals(restored.tile().voxelCount(),complete.tile().voxelCount());
            assertEquals(0,calls.get());
            vegetation.invalidate(0,0);
            try(var lease=disk.lease(PredictionDiskCache.Key.surface(0,0,settings))) {assertTrue(disk.writeSurface(lease,Map.of(),true));}
            var cleared=vegetation.restoreForRendering(0,0,16,1,true,ignored->false,()->true);
            assertTrue(cleared.complete());assertEquals(0,cleared.tile().voxelCount());
        }
    }
}
