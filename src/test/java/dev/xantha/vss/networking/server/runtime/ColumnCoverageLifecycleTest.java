package dev.xantha.vss.networking.server.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.xantha.vss.api.column.ColumnCoverage;
import dev.xantha.vss.api.column.ColumnCoverageApi;
import dev.xantha.vss.config.VSSServerConfig;
import dev.xantha.vss.networking.server.generation.ChunkGenerationService;
import dev.xantha.vss.networking.server.generation.ChunkyGenerationService;
import dev.xantha.vss.networking.server.preload.ExistingColumnPreloader;
import dev.xantha.vss.networking.server.sending.GeneratedColumnFlusher;
import dev.xantha.vss.networking.server.sending.QueuedColumnSender;
import dev.xantha.vss.networking.server.state.PlayerRequestRegistry;
import dev.xantha.vss.networking.server.storage.ColumnLodCache;
import dev.xantha.vss.networking.server.storage.PersistentColumnLodStore;
import dev.xantha.vss.networking.server.storage.PersistentColumnWriter;
import dev.xantha.vss.testutil.MinecraftTestBootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

class ColumnCoverageLifecycleTest {
    @BeforeAll static void bootstrap() { MinecraftTestBootstrap.initialize(); }
    @Test void actualRuntimeBindsRefreshesAndRevokesItsServerView() {
        var config = new VSSServerConfig();
        var cache = new ColumnLodCache(config);
        var disk = mock(DiskTaskRuntime.class);
        when(disk.awaitWrites(anyLong())).thenReturn(true);
        var runtime = new ServerNetworkingLifecycle(new PlayerRequestRegistry(), mock(ChunkGenerationService.class),
                cache, mock(PersistentColumnLodStore.class), mock(PersistentColumnWriter.class),
                new ServerLifecycleGuard(), disk, mock(GeneratedColumnFlusher.class),
                mock(ExistingColumnPreloader.class), mock(QueuedColumnSender.class), mock(ChunkyGenerationService.class));
        assertTrue(ColumnCoverageApi.get().isEmpty());
        runtime.onServerStarting();
        ColumnCoverage old = ColumnCoverageApi.get().orElseThrow();
        try {
            assertEquals(ColumnCoverage.State.UNKNOWN, old.section(Level.OVERWORLD, new ChunkPos(0, 0), 0));
            config.enabled = false;
            runtime.applyRuntimeConfig();
            assertEquals(ColumnCoverage.State.UNKNOWN, old.section(Level.OVERWORLD, new ChunkPos(0, 0), 0));
        } finally { runtime.onServerStopping(mock(MinecraftServer.class)); }
        assertTrue(ColumnCoverageApi.get().isEmpty());
        runtime.onServerStarting();
        try {
            assertNotSame(old, ColumnCoverageApi.get().orElseThrow());
            assertEquals(ColumnCoverage.State.UNKNOWN, old.section(Level.OVERWORLD, new ChunkPos(0, 0), 0));
        } finally { runtime.onServerStopping(mock(MinecraftServer.class)); }
    }
}
