package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Field;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StrictIngestCompletionTest {
    record Task(Object section) { }
    @Test void enqueueIsNotCompletionAndCallbacksCannotReleaseUnrelatedSections() throws Exception {
        StrictLodVisibility.reset();
        Object a=new Object(), b=new Object();
        StrictLodVisibility.beginIngest(a);StrictLodVisibility.beginIngest(a);StrictLodVisibility.beginIngest(b);
        assertEquals(2,pending().size());
        StrictLodVisibility.ingestCompleted(new Task(new Object()));
        assertEquals(2,pending().get(a));
        StrictLodVisibility.ingestCompleted(new Task(a));assertEquals(1,pending().get(a));
        StrictLodVisibility.cancelIngest(a);assertFalse(pending().containsKey(a));
        assertTrue(pending().containsKey(b));
        StrictLodVisibility.reset();
        StrictLodVisibility.ingestCompleted(new Task(b));assertTrue(pending().isEmpty());
    }
    @SuppressWarnings("unchecked") private static Map<Object,Integer> pending() throws Exception {
        Field f=StrictLodVisibility.class.getDeclaredField("pendingIngest");f.setAccessible(true);return (Map<Object,Integer>)f.get(null);
    }
}
