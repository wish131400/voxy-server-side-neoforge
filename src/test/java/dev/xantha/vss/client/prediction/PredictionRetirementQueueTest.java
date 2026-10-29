package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class PredictionRetirementQueueTest {
    @Test void pendingCommandsCoalesceAndCloseDiscardsUnstartedWork() {
        var commands=new ArrayDeque<Runnable>();var result=new AtomicInteger();
        var queue=new PredictionRetirementQueue<String>(commands::add);
        var released=new AtomicInteger();
        queue.submit("tile",()->result.set(1),released::incrementAndGet);
        queue.submit("tile",()->result.set(2),released::incrementAndGet);
        assertEquals(0,released.get(),"Memory remains charged until the source is retired");
        assertEquals(1,queue.size());assertEquals(1,commands.size());
        while(!commands.isEmpty())commands.remove().run();assertEquals(2,result.get());assertEquals(2,released.get());
        queue.submit("old",()->result.set(3),released::incrementAndGet);queue.close();assertEquals(3,released.get());
        while(!commands.isEmpty())commands.remove().run();assertEquals(2,result.get());assertEquals(0,queue.size());
    }
    @Test void aSlowDisposalDoesNotBlockTheCallerAndConcurrentCommandsAreNotLost() throws Exception {
        var worker=Executors.newSingleThreadExecutor();var entered=new CountDownLatch(1);var resume=new CountDownLatch(1);
        var completed=new CountDownLatch(1);
        try(var queue=new PredictionRetirementQueue<String>(worker)) {
            queue.submit("old",()->{entered.countDown();try {assertTrue(resume.await(5,TimeUnit.SECONDS));}
                catch(InterruptedException e){throw new AssertionError(e);}});
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(1),()->queue.submit("new",completed::countDown));
            resume.countDown();assertTrue(completed.await(5,TimeUnit.SECONDS));
        } finally {resume.countDown();worker.shutdownNow();}
    }
}
