package dev.xantha.vss.networking.server.request;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ColumnStorageReadPipelineTest {

    @Test
    void explicitNbtSyncAlwaysReadsExistingChunkNbt() {
        assertTrue(ColumnStorageReadPipeline.shouldReadExistingChunkNbt(true, true, true));
        assertTrue(ColumnStorageReadPipeline.shouldReadExistingChunkNbt(true, false, true));
    }

    @Test
    void unavailableGenerationReadsExistingChunkNbt() {
        assertTrue(ColumnStorageReadPipeline.shouldReadExistingChunkNbt(false, true, false));
    }

    @Test
    void disallowedGenerationReadsExistingChunkNbt() {
        assertTrue(ColumnStorageReadPipeline.shouldReadExistingChunkNbt(false, false, true));
    }

    @Test
    void generationPathSkipsExistingChunkNbtWhenNbtSyncIsDisabled() {
        assertFalse(ColumnStorageReadPipeline.shouldReadExistingChunkNbt(false, true, true));
    }

    @Test
    void cacheProbeAlwaysSkipsExistingChunkNbt() {
        assertFalse(ColumnStorageReadPipeline.shouldReadExistingChunkNbt(true, true, true, true));
        assertFalse(ColumnStorageReadPipeline.shouldReadExistingChunkNbt(false, false, false, true));
    }
}
