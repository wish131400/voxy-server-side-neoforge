package dev.xantha.vss.compat;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StrictVoxyPipelineTest {
    @Test void boxQueriesMatchEverySectionIncludingWrappedPackedCoordinates() {
        var pipeline = new StrictVoxyPipeline(); var random = new java.util.Random(740195);
        for (int i = 0; i < 400; i++) {
            int lod = random.nextInt(5), shift = lod + 1;
            pipeline.queued(StrictVoxyNodeIndex.key(lod, (random.nextInt(160) - 80) >> shift,
                    (random.nextInt(128) - 64) >> shift, (random.nextInt(160) - 80) >> shift));
        }
        for (int check = 0; check < 800; check++) {
            int x = random.nextInt(180) - 90, z = random.nextInt(180) - 90;
            int y = (check % 2 == 0 ? 8192 : 0) + random.nextInt(180) - 90;
            int endX = x + random.nextInt(10), endZ = z + random.nextInt(10), endY = y + random.nextInt(50);
            boolean expected = true;
            outer: for (int cz = z; cz <= endZ; cz++) for (int cx = x; cx <= endX; cx++)
                for (int sy = y; sy <= endY; sy++) if (!pipeline.idle(cx, sy, cz)) { expected = false; break outer; }
            assertEquals(expected, pipeline.idleBox(x, y, z, endX, endY, endZ));
            assertEquals(expected, pipeline.idleBox(x, endY, z, endX, y, endZ));
        }
        assertFalse(pipeline.idleBox(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE,
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));
    }

    @Test void staleMeshCompletionCannotMakeAWholeRegionIdle() {
        var pipeline = new StrictVoxyPipeline(); long key = StrictVoxyNodeIndex.key(0, -3, -1, 2);
        pipeline.queued(key); var old = pipeline.started(key);
        pipeline.queued(key); old.uploaded();
        assertFalse(pipeline.idleBox(-8, -4, 0, -1, 5, 7));
        pipeline.started(key).uploaded();
        assertTrue(pipeline.idleBox(-8, -4, 0, -1, 5, 7));
    }

    @Test void rangeQueriesMatchSectionQueriesIncludingNegativeAncestorsAndEmptyFastPath() {
        var pipeline = new StrictVoxyPipeline();
        assertTrue(pipeline.idleRange(0, Integer.MIN_VALUE, Integer.MAX_VALUE, 0));
        var random = new java.util.Random(3294);
        for (int id = 0; id < 120; id++) {
            int lod = random.nextInt(5), shift = lod + 1;
            pipeline.queued(StrictVoxyNodeIndex.key(lod, (random.nextInt(128) - 64) >> shift,
                    (random.nextInt(96) - 48) >> shift, (random.nextInt(128) - 64) >> shift));
        }
        for (int check = 0; check < 1000; check++) {
            int x = random.nextInt(128) - 64, z = random.nextInt(128) - 64;
            int first = random.nextInt(80) - 40, last = first + random.nextInt(65);
            boolean expected = true;
            for (int y = first; y <= last; y++) if (!pipeline.idle(x, y, z)) { expected = false; break; }
            assertEquals(expected, pipeline.idleRange(x, first, last, z));
            assertEquals(expected, pipeline.idleRange(x, last, first, z));
        }
    }

    @Test void distantWorkDoesNotBlockNearRingButQueuedBuildingAndUnuploadedLocalWorkDo() {
        StrictVoxyPipeline p = new StrictVoxyPipeline();
        long far = StrictVoxyNodeIndex.key(0, 80, 0, 80);
        long near = StrictVoxyNodeIndex.key(0, 0, 0, 0);
        p.queued(far);
        assertTrue(p.idle(0, 0, 0));
        p.queued(near);
        var result = p.started(near);
        assertFalse(p.idle(0, 0, 0), "starting or finishing a build does not prove upload");
        result.uploaded();
        assertTrue(p.idle(0, 0, 0));
        assertEquals(1, p.size());
    }

    @Test void oldUploadCannotAcknowledgeANewerEditOrAnotherRenderSystem() {
        StrictVoxyPipeline p = new StrictVoxyPipeline();
        long key = StrictVoxyNodeIndex.key(4, -1, 0, 0);
        p.queued(key); var old = p.started(key);
        p.queued(key); var latest = p.started(key);
        old.uploaded();
        assertFalse(p.idle(-1, 0, 0));
        StrictVoxyPipeline replacement = new StrictVoxyPipeline();
        replacement.queued(key);
        latest.uploaded();
        assertTrue(p.idle(-1, 0, 0));
        assertFalse(replacement.idle(-1, 0, 0));
        old.uploaded();
        assertEquals(0, p.size());
    }
}
