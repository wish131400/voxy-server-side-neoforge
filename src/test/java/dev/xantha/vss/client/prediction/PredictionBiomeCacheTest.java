package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import org.junit.jupiter.api.Test;

class PredictionBiomeCacheTest {
    @Test void dispersedPopulatedPagesShareTheActualAnswerBudgetAcrossJobs() throws Exception {
        var source=mock(BiomeSource.class);var climate=mock(Climate.Sampler.class);
        var even=Holder.direct(mock(Biome.class));var odd=Holder.direct(mock(Biome.class));
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(source.getNoiseBiome(anyInt(),anyInt(),anyInt(),same(climate))).thenAnswer(call->{
            calls.incrementAndGet();return (((int)call.getArgument(0)^(int)call.getArgument(1)^(int)call.getArgument(2))&1)==0?even:odd;
        });
        var cache=new PredictionBiomeCache(source,climate);
        // 256 dispersed neighborhoods, 80 answers each: pages are populated
        // enough to bypass sparse retirement, yet waste most of a 512-slot page.
        var points=new java.util.ArrayList<int[]>();
        for(int page=0;page<256;page++)for(int y=0;y<5;y++)for(int z=0;z<4;z++)for(int x=0;x<4;x++)
            points.add(new int[]{page*104729*8+x,-2048+y,-page*8191*8+z});
        for(var p:points)cache.get(p[0],p[1],p[2]);
        int cold=calls.get();
        var worker=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {worker.submit(()->{
            for(var p:points)assertSame(((p[0]^p[1]^p[2])&1)==0?even:odd,cache.get(p[0],p[1],p[2]));
        }).get(15,java.util.concurrent.TimeUnit.SECONDS);}finally{worker.shutdownNow();}
        int repeated=calls.get()-cold;
        System.out.println("BIOME_DISPERSED reads="+points.size()*2+" cold="+cold+" repeated="+repeated+" "+cache.diagnostics());
        assertTrue(repeated<points.size()/50,"Cached working sets should survive job handoff without page waste: "+repeated);
    }
    @org.junit.jupiter.api.BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    @Test void failedLookupKeepsItsCauseAndRetriesInsteadOfRememberingEmptyBiome() {
        var source = mock(BiomeSource.class);
        var climate = mock(Climate.Sampler.class);
        var biome = Holder.direct(mock(Biome.class));
        var cause = new IllegalStateException("MultiNoiseSampler doesn't have a seed set");
        when(source.getNoiseBiome(-7, 20, 31, climate)).thenThrow(cause).thenReturn(biome);
        var cache = new PredictionBiomeCache(source, climate);
        var failure = assertThrows(PredictionBiomeCache.LookupFailure.class, () -> cache.get(-7, 20, 31));
        assertSame(cause, failure.getCause());
        assertSame(biome, cache.get(-7, 20, 31));
        assertSame(biome, cache.get(-7, 20, 31));
        verify(source, times(2)).getNoiseBiome(-7, 20, 31, climate);
    }

    @Test void cancellationAndDeferredResourcesKeepTheirControlFlow() {
        var source = mock(BiomeSource.class);
        var climate = mock(Climate.Sampler.class);
        var deferred = new PredictionWorkDeferred();
        var cancelled = new java.util.concurrent.CancellationException();
        when(source.getNoiseBiome(0, 0, 0, climate)).thenThrow(deferred).thenThrow(cancelled);
        var cache = new PredictionBiomeCache(source, climate);
        assertSame(deferred, assertThrows(PredictionWorkDeferred.class, () -> cache.get(0, 0, 0)));
        assertSame(cancelled, assertThrows(java.util.concurrent.CancellationException.class, () -> cache.get(0, 0, 0)));
    }

    @Test void adjacentJobsReuseExactNeighborhoodAcrossThreadsWithoutPointCollisions() throws Exception {
        var source=mock(BiomeSource.class);var climate=mock(Climate.Sampler.class);
        var answer=Holder.direct(mock(Biome.class));
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(source.getNoiseBiome(anyInt(),anyInt(),anyInt(),same(climate))).thenAnswer(invocation->{calls.incrementAndGet();return answer;});
        var cache=new PredictionBiomeCache(source,climate);
        // Eight complete adjacent pages. Reverse traversal on another worker
        // exercises both the shared pages and that worker's local references.
        for(int y=-8;y<8;y++)for(int z=-8;z<8;z++)for(int x=-8;x<8;x++)assertSame(answer,cache.get(x,y,z));
        assertEquals(4096,calls.get());
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {pool.submit(()->{
            for(int y=7;y>=-8;y--)for(int z=7;z>=-8;z--)for(int x=7;x>=-8;x--)assertSame(answer,cache.get(x,y,z));
        }).get(10,java.util.concurrent.TimeUnit.SECONDS);}finally{pool.shutdownNow();}
        assertEquals(4096,calls.get(),"Adjacent jobs must not resample cached quart coordinates");
    }

    @Test void fullCoordinatesAndSamplerInstancesNeverAliasAfterPageEviction() {
        var climate=mock(Climate.Sampler.class);var source=mock(BiomeSource.class);
        var answer=Holder.direct(mock(Biome.class));
        when(source.getNoiseBiome(anyInt(),anyInt(),anyInt(),same(climate))).thenReturn(answer);
        var cache=new PredictionBiomeCache(source,climate);
        for(int i=0;i<1024;i++)assertSame(answer,cache.get(i*32,Integer.MIN_VALUE+i,-i*64));
        for(int x:new int[]{Integer.MIN_VALUE,-1,0,Integer.MAX_VALUE})
            for(int y:new int[]{Integer.MIN_VALUE,-2048,0,2048,Integer.MAX_VALUE})assertSame(answer,cache.get(x,y,17));
        verify(source).getNoiseBiome(-1,-2048,17,climate);
        verify(source).getNoiseBiome(-1,2048,17,climate);
        var other=mock(BiomeSource.class);var otherAnswer=Holder.direct(mock(Biome.class));
        when(other.getNoiseBiome(-1,-2048,17,climate)).thenReturn(otherAnswer);
        assertSame(otherAnswer,new PredictionBiomeCache(other,climate).get(-1,-2048,17));
    }

    @Test void sharedPageReadsPreserveDifferentAnswersDuringConcurrentMisses() throws Exception {
        var source=mock(BiomeSource.class);var climate=mock(Climate.Sampler.class);
        var even=Holder.direct(mock(Biome.class));var odd=Holder.direct(mock(Biome.class));
        when(source.getNoiseBiome(anyInt(),anyInt(),anyInt(),same(climate))).thenAnswer(call->
                (((int)call.getArgument(0)^(int)call.getArgument(1)^(int)call.getArgument(2))&1)==0?even:odd);
        var cache=new PredictionBiomeCache(source,climate);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(4);
        var jobs=new java.util.ArrayList<java.util.concurrent.Callable<Void>>();
        for(int worker=0;worker<4;worker++)jobs.add(()->{
            for(int round=0;round<3;round++)for(int x=-9;x<=9;x++)for(int y=-9;y<=9;y++)for(int z=-9;z<=9;z++)
                assertSame(((x^y^z)&1)==0?even:odd,cache.get(x,y,z));
            return null;
        });
        try {for(var job:pool.invokeAll(jobs))job.get(15,java.util.concurrent.TimeUnit.SECONDS);}
        finally {pool.shutdownNow();}
        assertSame(even,cache.get(Integer.MIN_VALUE,0,0));
        assertSame(odd,cache.get(Integer.MAX_VALUE,0,0));
    }

    @Test void retiredSparsePointsPreserveFullCoordinatesAndAnswersOnWorkerHandoff() throws Exception {
        var source=mock(BiomeSource.class);var climate=mock(Climate.Sampler.class);
        var even=Holder.direct(mock(Biome.class));var odd=Holder.direct(mock(Biome.class));
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(source.getNoiseBiome(anyInt(),anyInt(),anyInt(),same(climate))).thenAnswer(call->{
            calls.incrementAndGet();
            return (((int)call.getArgument(0)^(int)call.getArgument(1)^(int)call.getArgument(2))&1)==0?even:odd;
        });
        var cache=new PredictionBiomeCache(source,climate);
        int[][] points=new int[1024][3];
        for(int i=0;i<points.length;i++) {
            points[i]=new int[]{Integer.MIN_VALUE+i*4099,Integer.MAX_VALUE-i*8193,-i*131071};
            var p=points[i];
            assertSame(((p[0]^p[1]^p[2])&1)==0?even:odd,cache.get(p[0],p[1],p[2]));
        }
        int before=calls.get();
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {pool.submit(()->{
            for(var p:points)assertSame(((p[0]^p[1]^p[2])&1)==0?even:odd,cache.get(p[0],p[1],p[2]));
        }).get(10,java.util.concurrent.TimeUnit.SECONDS);}finally{pool.shutdownNow();}
        assertTrue(calls.get()-before<points.length/10,"Retiring sparse pages must preserve cross-worker reuse");
    }
}
