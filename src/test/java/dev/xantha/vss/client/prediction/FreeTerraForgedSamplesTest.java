package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FreeTerraForgedSamplesTest {
    @org.junit.jupiter.api.BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    @Test void concurrentLodsShareOneAnswerAndClosedWorldsReleaseIt() throws Exception {
        var calls=new AtomicInteger();var start=new CountDownLatch(1);var computing=new CountDownLatch(1);
        var workers=Executors.newFixedThreadPool(4);
        try(var cache=new FreeTerraForgedSamples(2)) {
            var jobs=new ArrayList<Future<ClientColumnSample>>();
            for(int i=0;i<4;i++)jobs.add(workers.submit(()->{start.await();return cache.get(-1,17,()->{
                calls.incrementAndGet();computing.countDown();
                try { Thread.sleep(30); } catch(InterruptedException e){throw new CancellationException();}
                return PredictionVoxelFixtures.sample(73);
            });}));
            start.countDown();assertTrue(computing.await(5,TimeUnit.SECONDS));
            var first=jobs.get(0).get(5,TimeUnit.SECONDS);
            for(var job:jobs)assertSame(first,job.get(5,TimeUnit.SECONDS));
            assertEquals(1,calls.get());
            assertSame(first,cache.get(-1,17,()->{throw new AssertionError();}));
            cache.get(10,10,()->first);cache.get(11,11,()->first);
            assertTrue(cache.diagnostics().contains("entries=2,"));
            cache.close();assertThrows(CancellationException.class,()->cache.get(-1,17,()->first));
        } finally {workers.shutdownNow();}
    }

    @Test void failedAndCancelledQueriesCanBeRetriedWithoutPoisoningOtherCoordinates() {
        try(var cache=new FreeTerraForgedSamples(2)) {
            assertThrows(CancellationException.class,()->cache.get(1,2,()->{throw new CancellationException();}));
            var sample=PredictionVoxelFixtures.sample(64);
            assertSame(sample,cache.get(1,2,()->sample));
            assertThrows(IllegalStateException.class,()->cache.get(2,1,()->{throw new IllegalStateException();}));
            assertSame(sample,cache.get(1,2,()->{throw new AssertionError();}));
            assertSame(sample,cache.get(2,1,()->sample));
        }
    }

    @Test void primitiveCacheRetainsLruOrderWithoutIncreasingTheCapacity() {
        try(var cache=new FreeTerraForgedSamples(2)) {
            var first=PredictionVoxelFixtures.sample(64);var second=PredictionVoxelFixtures.sample(65);
            var third=PredictionVoxelFixtures.sample(66);
            cache.get(-1,-2,()->first);cache.get(1,2,()->second);
            assertSame(first,cache.get(-1,-2,()->{throw new AssertionError();}));
            cache.get(3,4,()->third);
            assertSame(first,cache.get(-1,-2,()->{throw new AssertionError();}));
            assertSame(second,cache.get(1,2,()->second));
            assertTrue(cache.diagnostics().contains("evictions=2,"));
        }
    }
}
