package dev.xantha.vss.networking.server.generation;

import dev.xantha.vss.config.VSSServerConfig;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GenerationToggleTest {
    @org.junit.jupiter.api.BeforeAll
    static void initializeConfigDirectory() {
        if (net.neoforged.fml.loading.FMLPaths.GAMEDIR.get() == null) {
            net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(
                    java.nio.file.Path.of("build", "tmp", "generation-tests"));
        }
    }

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
