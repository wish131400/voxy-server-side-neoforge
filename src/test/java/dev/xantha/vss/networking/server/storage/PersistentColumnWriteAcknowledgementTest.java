package dev.xantha.vss.networking.server.storage;

import dev.xantha.vss.common.processing.EncodedColumnData;
import dev.xantha.vss.common.processing.LodByteCompression;
import dev.xantha.vss.config.VSSServerConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PersistentColumnWriteAcknowledgementTest {
    @BeforeAll static void initialize() {
        if (net.neoforged.fml.loading.FMLPaths.GAMEDIR.get() == null)
            net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(java.nio.file.Path.of("build", "tmp", "column-write-tests"));
    }

    @Test void disabledStorageDoesNotAcknowledgeASave() {
        VSSServerConfig config = new VSSServerConfig();
        config.enablePersistentColumnCache = false;
        PersistentColumnLodStore store = new PersistentColumnLodStore(config);
        assertFalse(store.writeConfirmed(null, null, column(true, EncodedColumnData.SCHEMA_VERSION)));
    }

    @Test void incompleteOrOldSchemaColumnsDoNotAcknowledgeASave() {
        VSSServerConfig config = new VSSServerConfig();
        config.enablePersistentColumnCache = true;
        PersistentColumnLodStore store = new PersistentColumnLodStore(config);
        // Null server confirms validation fails before attempting any world/disk access.
        assertFalse(store.writeConfirmed(null, null, column(false, EncodedColumnData.SCHEMA_VERSION)));
        assertFalse(store.writeConfirmed(null, null, column(true, EncodedColumnData.SCHEMA_VERSION - 1)));
        assertFalse(store.writeConfirmed(null, null, null));
    }

    private static EncodedColumnData column(boolean complete, int schema) {
        return new EncodedColumnData(0, 0, LodByteCompression.METHOD_DEFLATE, 32,
                new byte[] {1, 2, 3, 4}, 1L, schema, complete);
    }
}
