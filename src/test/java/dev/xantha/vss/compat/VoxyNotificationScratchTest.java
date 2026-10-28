package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class VoxyNotificationScratchTest {
    @Test void reentrantAndExceptionalCallsReleaseWithoutAliasing() {
        VoxyNotificationScratch first;
        try(var scratch=VoxyNotificationScratch.borrow()) {
            first=scratch;scratch.faces[0][0]=17;
            try(var nested=VoxyNotificationScratch.borrow()){assertNotSame(scratch,nested);nested.faces[0][0]=33;}
            assertEquals(17,scratch.faces[0][0]);
        }
        assertThrows(IllegalStateException.class,()->{try(var scratch=VoxyNotificationScratch.borrow()) {
            assertSame(first,scratch);throw new IllegalStateException("test");}});
        try(var scratch=VoxyNotificationScratch.borrow()){assertSame(first,scratch);}
    }
    @Test void concurrentWorkersHaveIndependentFacesAndSignaturesMatchFreshBuffers() throws Exception {
        var pool=Executors.newFixedThreadPool(4);var start=new CyclicBarrier(4);
        try {
            var jobs=new ArrayList<Future<VoxyNotificationScratch>>();
            for(int worker=0;worker<4;worker++){final int id=worker;jobs.add(pool.submit(()->{
                try(var scratch=VoxyNotificationScratch.borrow()) {
                    start.await(5,TimeUnit.SECONDS);var expected=new long[6][1024];
                    for(int round=0;round<30;round++) {
                        for(int f=0;f<6;f++)for(int i=0;i<1024;i++)scratch.faces[f][i]=expected[f][i]=(long)id*10000+round*31+f+i;
                        assertEquals(VoxySectionSignature.faces(expected),VoxySectionSignature.faces(scratch.faces));
                    }
                    return scratch;
                }}));}
            var unique=Collections.newSetFromMap(new IdentityHashMap<VoxyNotificationScratch,Boolean>());
            for(var job:jobs)unique.add(job.get(10,TimeUnit.SECONDS));assertEquals(4,unique.size());
        } finally {pool.shutdownNow();}
    }
}
