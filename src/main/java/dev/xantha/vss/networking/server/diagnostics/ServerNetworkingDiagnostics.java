package dev.xantha.vss.networking.server.diagnostics;

import dev.xantha.vss.config.VSSServerConfig;
import dev.xantha.vss.networking.server.dirty.DirtyColumnBroadcaster;
import dev.xantha.vss.networking.server.generation.ChunkGenerationService;
import dev.xantha.vss.networking.server.runtime.DiskTaskRuntime;
import dev.xantha.vss.networking.server.state.PlayerRequestRegistry;
import dev.xantha.vss.networking.server.state.PlayerRequestState;
import dev.xantha.vss.networking.server.storage.ColumnLodCache;
import dev.xantha.vss.networking.server.storage.PersistentColumnLodStore;
import java.util.Locale;
import net.minecraft.network.chat.Component;

public final class ServerNetworkingDiagnostics {
    private final PlayerRequestRegistry playerRegistry;
    private final ChunkGenerationService generationService;
    private final ColumnLodCache columnCache;
    private final PersistentColumnLodStore persistentStore;
    private final ServerRequestStats requestStats;
    private final DiskTaskRuntime diskRuntime;

    public ServerNetworkingDiagnostics(
            PlayerRequestRegistry playerRegistry,
            ChunkGenerationService generationService,
            ColumnLodCache columnCache,
            PersistentColumnLodStore persistentStore,
            ServerRequestStats requestStats,
            DiskTaskRuntime diskRuntime) {
        this.playerRegistry = playerRegistry;
        this.generationService = generationService;
        this.columnCache = columnCache;
        this.persistentStore = persistentStore;
        this.requestStats = requestStats;
        this.diskRuntime = diskRuntime;
    }

    public String generationDiagnostics() {
        return generationService.diagnostics();
    }

    public Component storageDiagnostics() {
        DiskTaskRuntime.Snapshot disk = diskRuntime.snapshot();
        return Component.translatable(
                "vss.command.storage.runtime",
                disk.readThreads(),
                disk.readQueueSize(),
                disk.writeQueueSize(),
                disk.pendingReads(),
                disk.pendingWrites())
                .append(Component.literal("; "))
                .append(diskRuntimeExtra(disk));
    }

    public Component generationDiagnosticsComponent() {
        return generationService.diagnosticsComponent(storageDiagnosticsComponent());
    }

    public String diagnostics() {
        ServerRequestStats.Snapshot stats = requestStats.snapshot();
        QueueTotals queue = queueTotals();
        return String.format(
                "players=%d, queuedColumns=%d, priorityQueuedColumns=%d, queuedBytes=%.2f MiB, generation={%s}, storage={requests=%d, duplicates=%d, distanceRejected=%d, upToDate=%d, cacheHits=%d, diskSubmitted=%d, diskPending=%d, diskHits=%d, diskMisses=%d, diskFailures=%d}, dirty={%s}, cache={%s}",
                playerRegistry.size(),
                queue.queuedPayloads(),
                queue.priorityQueuedPayloads(),
                queue.queuedBytes() / (double) VSSServerConfig.BYTES_PER_MIB,
                generationService.diagnostics(),
                stats.columnRequests(),
                stats.duplicateRequests(),
                stats.distanceRejectedRequests(),
                stats.upToDateResponses(),
                stats.cacheHits(),
                stats.diskReadsSubmitted(),
                diskRuntime.pendingReads(),
                stats.diskReadHits(),
                stats.diskReadMisses(),
                stats.diskReadFailures(),
                DirtyColumnBroadcaster.diagnostics(),
                columnCache.diagnostics() + ", " + persistentStore.diagnostics()
                        + ", persistentWritePending=" + diskRuntime.pendingWrites());
    }

    public Component diagnosticsComponent() {
        QueueTotals queue = queueTotals();
        return Component.translatable(
                "vss.command.stats.details",
                playerRegistry.size(),
                queue.queuedPayloads(),
                String.format(Locale.ROOT, "%.2f", queue.queuedBytes() / (double) VSSServerConfig.BYTES_PER_MIB),
                generationService.diagnosticsComponent(storageDiagnosticsComponent()),
                DirtyColumnBroadcaster.diagnosticsComponent());
    }

    private Component storageDiagnosticsComponent() {
        ServerRequestStats.Snapshot stats = requestStats.snapshot();
        return Component.translatable(
                "vss.command.generation.storage.details",
                stats.columnRequests(),
                stats.duplicateRequests(),
                stats.distanceRejectedRequests(),
                stats.upToDateResponses(),
                stats.cacheHits(),
                stats.diskReadsSubmitted(),
                diskRuntime.pendingReads(),
                stats.diskReadHits(),
                stats.diskReadMisses(),
                stats.diskReadFailures())
                .append(Component.literal("; "))
                .append(diskRuntimeExtra(diskRuntime.snapshot()));
    }

    private static Component diskRuntimeExtra(DiskTaskRuntime.Snapshot disk) {
        double averageWaitMs = disk.readWaitSamples() == 0L
                ? 0.0D
                : disk.readWaitNanos() / 1_000_000.0D / disk.readWaitSamples();
        return Component.translatable(
                "vss.command.storage.runtime.extra",
                disk.pendingPreloadReads(),
                disk.manualReadsSubmitted(),
                disk.manualReadsCompleted(),
                disk.manualReadsRejected(),
                disk.preloadReadsSubmitted(),
                disk.preloadReadsCompleted(),
                disk.preloadReadsRejected(),
                String.format(Locale.ROOT, "%.1f", averageWaitMs),
                String.format(Locale.ROOT, "%.1f", disk.maxReadWaitNanos() / 1_000_000.0D));
    }

    private QueueTotals queueTotals() {
        int queuedPayloads = 0;
        int priorityQueuedPayloads = 0;
        long queuedBytes = 0L;
        for (PlayerRequestState state : playerRegistry.states()) {
            queuedPayloads += state.queuedPayloadCount();
            priorityQueuedPayloads += state.priorityQueuedPayloadCount();
            queuedBytes += state.queuedBytes();
        }
        return new QueueTotals(queuedPayloads, priorityQueuedPayloads, queuedBytes);
    }

    private record QueueTotals(int queuedPayloads, int priorityQueuedPayloads, long queuedBytes) {
    }
}
