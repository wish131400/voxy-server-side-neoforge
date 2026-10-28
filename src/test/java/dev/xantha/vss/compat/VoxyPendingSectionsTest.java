package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class VoxyPendingSectionsTest {
    private static boolean intersects(Set<Long> positions,int x,int z,int maxX,int maxZ) {
        for(long p:positions) {
            long span=2L<<VoxyPredictionBridge.level(p);
            long bx=VoxyPredictionBridge.x(p)*span,bz=VoxyPredictionBridge.z(p)*span;
            if(bx<=maxX && bz<=maxZ && bx+span>x && bz+span>z)return true;
        }
        return false;
    }
    @Test void indexedQueriesMatchTheFullScanAcrossAllNativeLodsAndNegativeEdges() {
        var queue=new VoxyPendingSections();var reference=new HashSet<Long>();var random=new Random(42);
        for(int i=0;i<6000;i++) {
            long p=VoxyPredictionBridge.position(i%5,random.nextInt(800)-400,i%16-8,random.nextInt(800)-400);
            assertEquals(reference.add(p),queue.add(p));
        }
        for(int i=0;i<1500;i++) {
            int x=random.nextInt(16000)-8000,z=random.nextInt(16000)-8000;
            int span=i%100==0 ? 20000 : random.nextInt(80);
            assertEquals(intersects(reference,x,z,x+span,z+span),queue.intersects(x,z,x+span,z+span));
        }
        queue.removeIf(p -> VoxyPredictionBridge.level(p)%2==0);
        reference.removeIf(p -> VoxyPredictionBridge.level(p)%2==0);
        assertEquals(reference,queue);assertEquals(intersects(reference,-32,-32,32,32),queue.intersects(-32,-32,32,32));
        queue.clear();assertFalse(queue.intersects(Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE));
    }
    @Test void mutationsPublishTheirRegionBeforeACompletedAddCanBeObserved() throws Exception {
        var queue=new VoxyPendingSections();var executor=Executors.newSingleThreadExecutor();
        try {
            for(int i=0;i<100;i++) {
                final int x=i-50;
                long p=VoxyPredictionBridge.position(4,x,0,-1);
                executor.submit(()->queue.add(p)).get(5,TimeUnit.SECONDS);
                assertTrue(queue.intersects(x*32,-32,x*32+31,-1));
                executor.submit(()->queue.remove(p)).get(5,TimeUnit.SECONDS);
                assertFalse(queue.intersects(x*32,-32,x*32+31,-1));
            }
        } finally {executor.shutdownNow();}
    }
}
