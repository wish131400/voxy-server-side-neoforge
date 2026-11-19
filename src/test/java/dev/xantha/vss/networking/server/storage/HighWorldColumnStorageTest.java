package dev.xantha.vss.networking.server.storage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import dev.xantha.vss.config.VSSServerConfig;
import dev.xantha.vss.testutil.HighWorldColumnFixtures;
import java.nio.file.Path;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HighWorldColumnStorageTest {
    @org.junit.jupiter.api.BeforeAll static void bootstrap() { dev.xantha.vss.testutil.ColumnFilterFixtures.biomes(); }
    @TempDir Path world;

    @Test void tallColumnsSurviveStoreReopenAndRegionIndexReload() throws Exception {
        for (int count : new int[] {65, 80, 256}) {
            var column = HighWorldColumnFixtures.column(count);
            var server = mock(MinecraftServer.class);
            when(server.getWorldPath(LevelResource.ROOT)).thenReturn(world.resolve("height-" + count));
            var config = new VSSServerConfig(); config.enablePersistentColumnCache = true;
            var store = new PersistentColumnLodStore(config);
            assertTrue(store.writeConfirmed(server, Level.OVERWORLD, column));
            store.flushDirtyIndexesBlocking(server);
            var reopened = new PersistentColumnLodStore(config);
            assertEquals(1, reopened.findExistingColumnsInRegion(server, Level.OVERWORLD, 0, 0, 0, 7, 0, 32, 32).size());
            var restored = reopened.read(server, Level.OVERWORLD, 0, 7, 0L);
            assertNotNull(restored);
            assertArrayEquals(column.sectionYs(), restored.columnData().sectionYs());
            assertArrayEquals(column.encodedBytes(), restored.columnData().encodedBytes());
        }
    }
}
