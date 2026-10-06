package dev.xantha.vss.networking.client;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClientPresenceReporterBudgetTest {
    private boolean previouslyLoaded;
    private ClientPresenceReporter reporter;

    @BeforeAll
    static void initializeConfigDirectory() throws Exception {
        if (net.neoforged.fml.loading.FMLPaths.GAMEDIR.get() == null) {
            net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(java.nio.file.Files.createTempDirectory("vss-presence-budget"));
        }
    }

    @BeforeEach
    void setup() throws Exception {
        Field loaded=field(ClientLodPresenceCache.class,"loaded");
        previouslyLoaded=loaded.getBoolean(null);
        loaded.setBoolean(null,true);
        reporter=new ClientPresenceReporter("presence-budget-empty",new ClientPresenceReporter.ReconciliationListener() {
            public void onPresent(long packed,long timestamp) { fail("The fixture contains no columns"); }
            public void onMissing(long packed) { fail("The fixture contains no columns"); }
        });
    }

    @AfterEach
    void cleanup() throws Exception {
        field(ClientLodPresenceCache.class,"loaded").setBoolean(null,previouslyLoaded);
    }

    @Test
    void expiredBudgetFinishesOneRegionAndRetainsTheRemainingQueue() throws Exception {
        reporter.updateWindow(null,Level.OVERWORLD,0,0,64);
        int queued=pending().size();
        AtomicInteger clockReads=new AtomicInteger();
        reporter.drain(null,Level.OVERWORLD,false,() -> clockReads.getAndIncrement()==0 ? 0L : Long.MAX_VALUE);
        assertEquals(queued-1,pending().size());
        assertEquals(ClientLodPresenceCache.regionKey(-1,-1),pending().peekFirst());
    }

    @Test
    void boundedBatchesEventuallyDrainEveryRegion() throws Exception {
        reporter.updateWindow(null,Level.OVERWORLD,0,0,64);
        int total=pending().size();
        int drains=0;
        while(!pending().isEmpty()) {
            int before=pending().size();
            reporter.drain(null,Level.OVERWORLD,false,() -> 0L);
            int consumed=before-pending().size();
            assertTrue(consumed>0 && consumed<=8);
            assertTrue(++drains<=total,"the region cursor must keep advancing");
        }
        assertTrue(drains>1,"cache replay must be spread across client ticks");
    }

    @SuppressWarnings("unchecked")
    private ArrayDeque<Long> pending() throws Exception {
        return (ArrayDeque<Long>)field(ClientPresenceReporter.class,"pendingRegions").get(reporter);
    }

    private static Field field(Class<?> type,String name) throws Exception {
        Field result=type.getDeclaredField(name);result.setAccessible(true);return result;
    }
}
