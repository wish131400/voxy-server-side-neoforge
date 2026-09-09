package dev.xantha.vss.networking.server.generation;

import dev.xantha.vss.config.VSSServerConfig;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GenerationToggleTest {
    @Test void disabledGenerationRejectsLateStorageCallbacksBeforeAccessingTheWorld() {
        VSSServerConfig config = new VSSServerConfig();
        config.enableChunkGeneration = false;
        ChunkGenerationService service = new ChunkGenerationService(config);
        try {
            service.applyRuntimeConfig();
            assertFalse(service.submitGeneration(UUID.randomUUID(), null, 1, null, 0, 0, 0));
        } finally { service.shutdown(); }
    }
}
