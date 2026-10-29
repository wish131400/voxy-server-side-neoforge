package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import dev.xantha.vss.compat.VoxyPredictionBridge;
import dev.xantha.vss.client.prediction.PredictionVoxySource.*;
import net.minecraft.core.Holder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.*;

/** The same deterministic workload can run against either production revision. */
class PredictionPublicationReplayTest {
    @BeforeAll static void bootstrap() {ClientTerrainSamplerTest.bootstrapMinecraft();}
    private static final VoxyPredictionBridge.Mapper MAPPER=(state,biome)->1L<<27;
    @Test void replaySurfacePublicationsAndReportActualSectionWork() {
        long checksum=0;
        try(var source=new PredictionVoxySource()) {
            var columns=new Column[4096];var base=new Column(List.of(new Run(-64,130,Blocks.STONE.defaultBlockState())),"minecraft:plains");
            Arrays.fill(columns,base);
            var key=new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,-1,-1,0);
            var positions=new ArrayList<Long>();
            for(int lod=0;lod<=4;lod++)for(int y=-2;y<6;y++)
                for(int x=-2;x<0;x++)for(int z=-2;z<0;z++)positions.add(VoxyPredictionBridge.position(lod,x,y,z));
            long started=System.nanoTime();
            for(int round=0;round<24;round++) {
                var next=columns.clone();next[0]=new Column(List.of(new Run(-64,131+(round&3),Blocks.STONE.defaultBlockState())),"minecraft:plains");
                source.put(new Tile(key,-64,-64,64,64,1,next,List.of(),4096,true,0));
                for(long pos:positions) {
                    var data=source.mappedSection(pos,MAPPER);
                    if(data!=null) {long[] voxels=data.array();for(int index=0;index<32768;index+=997)checksum=checksum*31+voxels[index];}
                }
            }
            System.out.printf(Locale.ROOT,"PUBLICATION_REPLAY rounds=24 checksum=%d builds=%s invalidations=%s cacheHits=%s wallMs=%.3f%n",
                    checksum,source.diagnostics().get("sectionBuilds"),source.diagnostics().get("cacheInvalidations"),
                    source.diagnostics().get("cacheHits"),(System.nanoTime()-started)/1e6);
            assertTrue((long)source.diagnostics().get("sectionBuilds")>0);
        }
    }
    @Test void replayOverlappingDecorationBiomeNeighborhoods() {
        var source=mock(BiomeSource.class);var climate=mock(Climate.Sampler.class);
        var biome=Holder.direct(mock(Biome.class));var calls=new AtomicInteger();
        when(source.getNoiseBiome(anyInt(),anyInt(),anyInt(),same(climate))).thenAnswer(invocation->{calls.incrementAndGet();return biome;});
        var cache=new PredictionBiomeCache(source,climate);long reads=0;
        // Adjacent chunks revisit their halo at each feature stage. Keep this
        // trace below both caches' answer budget so collisions are measurable.
        for(int round=0;round<4;round++)for(int chunk=0;chunk<8;chunk++)
            for(int y=-8;y<8;y++)for(int z=-8;z<8;z++)for(int x=-8+chunk*4;x<8+chunk*4;x++) {
                assertSame(biome,cache.get(x,y,z));reads++;
            }
        System.out.printf("BIOME_NEIGHBORHOOD reads=%d loads=%d diagnostics={%s}%n",reads,calls.get(),cache.diagnostics());
        assertTrue(calls.get()>0);
    }
}
