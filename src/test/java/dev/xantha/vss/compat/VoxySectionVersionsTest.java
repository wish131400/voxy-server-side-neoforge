package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class VoxySectionVersionsTest {
    @Test void indexedInvalidationMatchesAnExhaustiveScanAcrossNegativeCoordinatesLodsAndHalo() {
        var index = new VoxySectionVersions();
        for(int lod=0;lod<=5;lod++) for(int x=-18;x<=18;x++) for(int z=-18;z<=18;z++) for(int y=-2;y<=2;y++)
            index.put(VoxyPredictionBridge.position(lod,x,y,z),new AtomicLong());
        var random=new Random(714);
        for(int trial=0;trial<150;trial++) {
            int x=random.nextInt(20000)-10000, y=random.nextInt(1200)-600,z=random.nextInt(20000)-10000;
            int endX=x+random.nextInt(2048)+1,endY=y+random.nextInt(256)+1,endZ=z+random.nextInt(2048)+1;
            var expected=new HashSet<Long>();
            for(long pos:index.keySet()) {
                long span=32L<<VoxyPredictionBridge.level(pos);
                long bx=VoxyPredictionBridge.x(pos)*span,by=VoxyPredictionBridge.y(pos)*span,bz=VoxyPredictionBridge.z(pos)*span;
                if(bx<endX && by<endY && bz<endZ && bx+span>x && by+span>y && bz+span>z)expected.add(pos);
            }
            var actual=index.intersect(x,y,z,endX,endY,endZ);
            assertEquals(expected,new HashSet<>(actual.stream().map(Map.Entry::getKey).toList()));
            assertEquals(expected.size(),actual.size());
        }
        assertEquals(index.size(),index.intersect(Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE,
                Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE).size());
        assertTrue(index.intersect(0,0,0,0,1,1).isEmpty());
    }
    @Test void removalsAndReinsertionsPreserveTheIndexAndVersionIdentity() {
        var index=new VoxySectionVersions();long pos=VoxyPredictionBridge.position(0,-1,0,-1);
        var old=new AtomicLong(1);var fresh=new AtomicLong(2);index.put(pos,old);
        assertTrue(index.intersect(-16,0,-16,16,16,16).stream().anyMatch(e->e.getKey()==pos));
        assertFalse(index.remove(pos,fresh));assertTrue(index.remove(pos,old));
        assertTrue(index.intersect(-32,0,-32,0,32,0).isEmpty());
        index.computeIfAbsent(pos,p->fresh);index.put(pos,old);
        assertSame(old,index.intersect(-32,0,-32,0,32,0).get(0).getValue());
        index.keySet().removeIf(p->true);assertTrue(index.isEmpty());
        index.put(pos,fresh);index.clear();assertTrue(index.intersect(-32,0,-32,0,32,0).isEmpty());
    }
    @Test void concurrentRegistrationAndRemovalNeverLosesTheFinalRegistration() throws Exception {
        var index=new VoxySectionVersions();var pool=Executors.newFixedThreadPool(4);
        try {
            var tasks=new ArrayList<Future<?>>();
            for(int worker=0;worker<4;worker++)tasks.add(pool.submit(()->{
                for(int i=0;i<8000;i++) {
                    long pos=VoxyPredictionBridge.position(i%5,i%127-64,0,i%131-66);
                    var token=index.computeIfAbsent(pos,p->new AtomicLong());
                    if(i%3==0)index.remove(pos,token);
                }
            }));
            for(var task:tasks)task.get(15,TimeUnit.SECONDS);
            var entries=index.intersect(Integer.MIN_VALUE,-1,Integer.MIN_VALUE,Integer.MAX_VALUE,1024,Integer.MAX_VALUE);
            assertEquals(index.size(),entries.size());
            for(var entry:entries)assertSame(index.get(entry.getKey()),entry.getValue());
        } finally {pool.shutdownNow();}
    }
}
