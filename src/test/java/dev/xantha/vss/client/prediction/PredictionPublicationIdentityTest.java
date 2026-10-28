package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import dev.xantha.vss.client.prediction.PredictionTileManager.*;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.*;

class PredictionPublicationIdentityTest {
    @BeforeAll static void bootstrap(){ClientTerrainSamplerTest.bootstrapMinecraft();}
    @SuppressWarnings("unchecked")
    @Test void duplicatePublicationSettlesDirtyWorkWithoutChangingTerrainOrPropagatingToParent() throws Exception {
        var samplerMethod=PredictionFirstCoverageTest.class.getDeclaredMethod("sampler",AtomicInteger.class,
                java.util.concurrent.CountDownLatch.class,java.util.concurrent.CountDownLatch.class);samplerMethod.setAccessible(true);
        var sampler=(ClientTerrainSampler)samplerMethod.invoke(null,new AtomicInteger(),null,null);
        var create=PredictionFirstCoverageTest.class.getDeclaredMethod("manager",ClientTerrainSampler.class,PredictionDiskCache.class);create.setAccessible(true);
        try(var manager=(PredictionTileManager)create.invoke(null,sampler,null)) {
            var parentKey=new PredictionTileKey(Level.OVERWORLD,0,0,1);
            var ready=(Map<PredictionTileKey,PredictionTile>)field(manager,"ready");ready.putAll(PredictionParentLodTest.children(parentKey));
            var parent=PredictionParentLodTest.tile(parentKey,90,64);ready.put(parentKey,parent);
            var dirty=(Set<PredictionTileKey>)field(manager,"dirtyTiles");
            var desired=(Set<PredictionTileKey>)field(manager,"desiredKeys");desired.addAll(ready.keySet());
            var childKey=new PredictionTileKey(Level.OVERWORLD,0,0,0);var old=ready.get(childKey);
            var publish=PredictionTileManager.class.getDeclaredMethod("publishTile",PredictionTile.class,PredictionMemoryBudget.Reservation.class,
                    long.class,long.class,boolean.class,boolean.class,boolean.class,PredictionRelief.class);publish.setAccessible(true);
            var budget=(PredictionMemoryBudget)field(manager,"memoryBudget");
            for(int i=0;i<30;i++) {
                dirty.add(childKey);
                var next=PredictionParentLodTest.tile(childKey,100+i,64);
                var reservation=budget.tryReserveBuild();assertNotNull(reservation);
                assertTrue((boolean)publish.invoke(manager,next,reservation,0L,0L,false,true,false,PredictionRelief.of(next)));
                reservation.completeBuild(next.retainedHeapBytes(),0,false);
                assertEquals(old.terrainRevision(),next.terrainRevision());
                assertSame(old.data().voxyTile,next.data().voxyTile);
                assertFalse(dirty.contains(childKey));assertFalse(dirty.contains(parentKey));
            }
            dirty.add(childKey);
            var changed=PredictionParentLodTest.tile(childKey,500,80);
            var reservation=budget.tryReserveBuild();assertNotNull(reservation);
            assertTrue((boolean)publish.invoke(manager,changed,reservation,0L,0L,false,true,false,PredictionRelief.of(changed)));
            reservation.completeBuild(changed.retainedHeapBytes(),0,false);
            assertTrue(dirty.contains(parentKey),"Real terrain changes must still enter parent refresh");
        }
    }
    private static Object field(Object target,String name)throws Exception {
        var f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);
    }
}
