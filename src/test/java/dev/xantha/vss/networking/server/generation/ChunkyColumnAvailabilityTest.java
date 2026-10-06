package dev.xantha.vss.networking.server.generation;

import dev.xantha.vss.common.PositionUtil;
import dev.xantha.vss.networking.payloads.DirtyColumnsS2CPayload;
import java.util.ArrayList;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChunkyColumnAvailabilityTest {
    @Test void firstCompletedColumnNotifiesBeforeTheRestOfTheAreaCompletes() {
        var delivered = new ArrayList<DirtyColumnsS2CPayload>();
        var notices = new ChunkyColumnAvailability(delivered::add);
        var area = new ChunkyWorkQueue(ChunkyArea.square(0,0,128));
        var work = area.reserve();
        notices.available(work.chunkX, work.chunkZ, 1234);
        area.finish(work,true);
        notices.flush();
        assertFalse(area.finished()); assertEquals(1, area.completed()); assertEquals(1, delivered.size());
        assertArrayEquals(new long[]{PositionUtil.packPosition(work.chunkX,work.chunkZ)},delivered.get(0).dirtyPositions());
        assertArrayEquals(new long[]{1234},delivered.get(0).dirtyTimestamps());
        notices.flush(); assertEquals(1,delivered.size());
    }

    @Test void bothReusedAndFreshVersionsAreBatchedExactlyWithoutInventingDirtyVersions() {
        var delivered = new ArrayList<DirtyColumnsS2CPayload>();
        var notices = new ChunkyColumnAvailability(delivered::add);
        notices.available(-4,2,10); // Old disk record is still useful to an empty client.
        notices.available(5,-3,500); notices.available(5,-3,499);
        notices.available(5,-3,501); notices.available(9,9,0);
        notices.flush();
        assertEquals(1,delivered.size());
        assertArrayEquals(new long[]{10,501},delivered.get(0).dirtyTimestamps());
        var filtered = ChunkyColumnAvailability.missingForPlayer(delivered.get(0),0,0,128,(x,z)->0);
        assertArrayEquals(delivered.get(0).dirtyPositions(),filtered.dirtyPositions());
    }

    @Test void largeCompletionBurstsStayPacketBoundedAndDoNotLoseTheFinalPartialBatch() {
        var delivered = new ArrayList<DirtyColumnsS2CPayload>();
        var notices = new ChunkyColumnAvailability(delivered::add);
        int count = ChunkyColumnAvailability.BATCH_SIZE * 3 + 7;
        for (int i=0;i<count;i++) notices.available(i,-8,100+i);
        assertEquals(3,delivered.size()); notices.flush(); assertEquals(4,delivered.size());
        var versions = new HashMap<Long,Long>();
        for (var batch : delivered) {
            assertTrue(batch.dirtyPositions().length <= ChunkyColumnAvailability.BATCH_SIZE);
            for(int i=0;i<batch.dirtyPositions().length;i++)
                assertNull(versions.put(batch.dirtyPositions()[i],batch.dirtyTimestamps()[i]));
        }
        assertEquals(count,versions.size());
        for (int i=0;i<count;i++) assertEquals(100L+i,versions.get(PositionUtil.packPosition(i,-8)));
    }

    @Test void clientFilteringKeepsMissingAndStaleColumnsButSkipsCurrentAndDistantOnes() {
        var delivered = new ArrayList<DirtyColumnsS2CPayload>();
        var notices = new ChunkyColumnAvailability(delivered::add);
        for(int i=0;i<5;i++) notices.available(i,0,200);
        notices.flush();
        var filtered = ChunkyColumnAvailability.missingForPlayer(delivered.get(0),0,0,3,
                (x,z) -> x==1?199:x==2?200:x==3?201:0);
        assertArrayEquals(new long[]{PositionUtil.packPosition(0,0),PositionUtil.packPosition(1,0)},filtered.dirtyPositions());
        assertArrayEquals(new long[]{200,200},filtered.dirtyTimestamps());
    }

    @Test void cancelledPendingNoticesCannotLeakToAnotherJob() {
        var delivered = new ArrayList<DirtyColumnsS2CPayload>();
        var notices = new ChunkyColumnAvailability(delivered::add);
        notices.available(1,2,100); notices.clear(); notices.flush(); assertTrue(delivered.isEmpty());
        notices.available(3,4,200); notices.flush();
        assertArrayEquals(new long[]{PositionUtil.packPosition(3,4)},delivered.get(0).dirtyPositions());
    }
}
