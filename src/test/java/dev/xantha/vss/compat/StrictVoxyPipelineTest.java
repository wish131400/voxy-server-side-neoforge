package dev.xantha.vss.compat;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StrictVoxyPipelineTest {
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
