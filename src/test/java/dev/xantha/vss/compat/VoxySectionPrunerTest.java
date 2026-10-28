package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class VoxySectionPrunerTest {
    private static long pos(int x) { return VoxySectionPosition.key(0,x,0,0); }
    @SuppressWarnings("unchecked")
    private static VoxySectionVersions counted(java.util.concurrent.atomic.AtomicInteger visits) {
        var versions=org.mockito.Mockito.spy(new VoxySectionVersions());
        org.mockito.Mockito.doAnswer(call -> {
            Set<Map.Entry<Long,AtomicLong>> entries=(Set<Map.Entry<Long,AtomicLong>>)call.callRealMethod();
            return new AbstractSet<Map.Entry<Long,AtomicLong>>() {
                public int size(){return entries.size();}
                public Iterator<Map.Entry<Long,AtomicLong>> iterator(){var it=entries.iterator();return new Iterator<>() {
                    public boolean hasNext(){return it.hasNext();}
                    public Map.Entry<Long,AtomicLong> next(){visits.incrementAndGet();return it.next();}
                };}
            };
        }).when(versions).entrySet();
        return versions;
    }
    @Test void stableRangeScansOnceAtAnyFrameRateAndMovementOrInsertionStartsANewPass() {
        for(int fps:new int[]{30,60,240}) {
            var visits=new java.util.concurrent.atomic.AtomicInteger();var versions=counted(visits);for(int x=0;x<2000;x++)versions.put(pos(x),new AtomicLong());
            var pruner=new VoxySectionPruner();
            for(int frame=0;frame<fps*2;frame++)pruner.prune(versions,0,0,100000,frame*1_000_000_000L/fps,p->fail());
            assertEquals(2000,visits.get(),"Stable scanning must not scale with FPS");
            versions.put(pos(-1),new AtomicLong());
            pruner.prune(versions,0,0,100000,3_000_000_000L,p->fail());assertEquals(2512,visits.get());
        }
    }
    @Test void cleanupIsBoundedAndInsertionsDuringAPassCannotBeMarkedAlreadyVisited() {
        var visits=new java.util.concurrent.atomic.AtomicInteger();var versions=counted(visits);versions.put(pos(0),new AtomicLong());
        for(int x=1000;x<3400;x++)versions.put(pos(x),new AtomicLong());
        var pruner=new VoxySectionPruner();var removed=new HashSet<Long>();
        pruner.prune(versions,0,0,1024,0,removed::add);assertEquals(512,visits.get());
        pruner.prune(versions,0,0,1024,1,removed::add);assertEquals(512,visits.get());
        versions.put(pos(5000),new AtomicLong());
        for(int batch=1;batch<12;batch++)pruner.prune(versions,0,0,1024,batch*50_000_000L,removed::add);
        assertEquals(Set.of(pos(0)),versions.keySet());assertEquals(2401,removed.size());
        pruner.prune(versions,200000,0,1024,1_000_000_000L,removed::add);assertTrue(versions.isEmpty());
        versions.put(pos(0),new AtomicLong());pruner.reset();
        pruner.prune(versions,0,0,1024,1_000_000_001L,p->fail());assertEquals(1,versions.size());
    }
}
