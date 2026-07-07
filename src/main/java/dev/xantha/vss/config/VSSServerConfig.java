package dev.xantha.vss.config;

import dev.xantha.vss.common.VSSConstants;

public class VSSServerConfig extends JsonConfig {
    private static final String FILE_NAME = "vss-server-config.json";
    public static final int MIN_LOD_DISTANCE_CHUNKS = 1;
    public static final int MAX_LOD_DISTANCE_CHUNKS = 8196;
    public static final int MIN_BYTES_PER_SECOND_LIMIT_PER_PLAYER = 62_500;
    public static final int MAX_BYTES_PER_SECOND_LIMIT_PER_PLAYER = 0x6400000;
    public static final int BYTES_PER_MIB = 1024 * 1024;
    public static final int MIN_BANDWIDTH_KBPS_PER_PLAYER = bytesToKbpsCeil(MIN_BYTES_PER_SECOND_LIMIT_PER_PLAYER);
    public static final int MAX_BANDWIDTH_KBPS_PER_PLAYER = MAX_BYTES_PER_SECOND_LIMIT_PER_PLAYER * 8 / 1000;
    public static final int KBPS_PER_MBPS = 1000;
    public static final int MIN_SEND_QUEUE_BYTES_PER_PLAYER = 4 * BYTES_PER_MIB;
    public static final int MAX_SEND_QUEUE_BYTES_PER_PLAYER = 512 * BYTES_PER_MIB;
    public static final int DEFAULT_DISK_READER_THREADS = 4;
    public static final int MIN_DISK_READER_THREADS = 1;
    public static final int MAX_DISK_READER_THREADS = 16;
    public static final int MIN_DIRTY_BROADCAST_INTERVAL_TICKS = 1;
    public static final int MAX_DIRTY_BROADCAST_INTERVAL_TICKS = 600;
    public static final int MIN_SYNC_RATE_LIMIT_PER_TICK = 0;
    public static final int MAX_SYNC_RATE_LIMIT_PER_TICK = VSSConstants.MAX_BATCH_CHUNK_REQUESTS;
    public static final int DEFAULT_NEAR_SYNC_RATE_LIMIT_PER_TICK = 0;
    public static final int DEFAULT_MID_SYNC_RATE_LIMIT_PER_TICK = 8;
    public static final int DEFAULT_FAR_SYNC_RATE_LIMIT_PER_TICK = 4;
    public static final int DEFAULT_DISTANT_SYNC_RATE_LIMIT_PER_TICK = 2;
    private static final int OLD_DEFAULT_BYTES_PER_SECOND_LIMIT_PER_PLAYER = 0x1400000;
    private static final int OLD_DEFAULT_SEND_QUEUE_LIMIT_PER_PLAYER = 4000;
    private static final int OLD_DEFAULT_GENERATION_RATE_LIMIT_PER_PLAYER = 80;
    private static final int OLD_DEFAULT_GENERATION_CONCURRENCY_LIMIT_PER_PLAYER = 16;
    private static final int OLD_DEFAULT_GENERATION_CONCURRENCY_LIMIT_GLOBAL = 32;
    private static final int OLD_DEFAULT_GENERATION_TIMEOUT_SECONDS = 60;
    private static final int CONSERVATIVE_GENERATION_CONCURRENCY_LIMIT_PER_PLAYER = 4;
    private static final int CONSERVATIVE_GENERATION_CONCURRENCY_LIMIT_GLOBAL = 8;
    private static final int CONSERVATIVE_GENERATION_TIMEOUT_SECONDS = 30;
    private static final int PREVIOUS_DEFAULT_BYTES_PER_SECOND_LIMIT_PER_PLAYER = 4 * BYTES_PER_MIB;
    private static final int PREVIOUS_DEFAULT_GENERATION_CONCURRENCY_LIMIT_GLOBAL = 24;
    private static final int DEFAULT_BANDWIDTH_KBPS_PER_PLAYER = KBPS_PER_MBPS;
    private static final int DEFAULT_BYTES_PER_SECOND_LIMIT_PER_PLAYER = kbpsToBytesPerSecond(DEFAULT_BANDWIDTH_KBPS_PER_PLAYER);
    private static final int DEFAULT_SEND_QUEUE_LIMIT_PER_PLAYER = 1024;
    private static final int DEFAULT_SEND_QUEUE_BYTES_PER_PLAYER = 32 * BYTES_PER_MIB;
    private static final int DEFAULT_GENERATION_RATE_LIMIT_PER_PLAYER = 40;
    private static final int LOW_BANDWIDTH_DEFAULT_KBPS_PER_PLAYER = 500;
    private static final int LOW_BANDWIDTH_DEFAULT_BYTES_PER_SECOND_PER_PLAYER = kbpsToBytesPerSecond(LOW_BANDWIDTH_DEFAULT_KBPS_PER_PLAYER);
    private static final int LOW_BANDWIDTH_DEFAULT_SEND_QUEUE_LIMIT_PER_PLAYER = 512;
    private static final int LOW_BANDWIDTH_DEFAULT_SEND_QUEUE_BYTES_PER_PLAYER = 8 * BYTES_PER_MIB;
    private static final int LOW_BANDWIDTH_DEFAULT_GENERATION_RATE_LIMIT_PER_PLAYER = 8;
    public static final VSSServerConfig CONFIG = load(VSSServerConfig.class, FILE_NAME);

    public boolean memorySafeDefaultsApplied = false;
    public boolean generationThroughputDefaultsApplied = false;
    public boolean bandwidthAndGlobalGenerationDefaultsApplied = false;
    public boolean memoryOptimizedDefaultsApplied = false;
    public boolean lowBandwidthDefaultsApplied = false;
    public boolean storageThroughputDefaultsApplied = false;
    public boolean bandwidthDecoupledDefaultsApplied = false;
    public boolean enabled = true;
    public boolean debugLogging = false;
    public int lodDistanceChunks = 128;
    public int bytesPerSecondLimitPerPlayer = DEFAULT_BYTES_PER_SECOND_LIMIT_PER_PLAYER;
    @Deprecated
    private Integer bandwidthLimitKbpsPerPlayer;
    public int sendQueueLimitPerPlayer = DEFAULT_SEND_QUEUE_LIMIT_PER_PLAYER;
    public int sendQueueBytesLimitPerPlayer = DEFAULT_SEND_QUEUE_BYTES_PER_PLAYER;
    public int diskReaderThreads = DEFAULT_DISK_READER_THREADS;
    public int diskReadQueueLimit = 4096;
    public int diskReadTimeoutMillis = 1000;
    public boolean enableChunkNbtColumnSync = true;
    public boolean enableChunkGeneration = true;
    public int nearSyncRateLimitPerTick = DEFAULT_NEAR_SYNC_RATE_LIMIT_PER_TICK;
    public int midSyncRateLimitPerTick = DEFAULT_MID_SYNC_RATE_LIMIT_PER_TICK;
    public int farSyncRateLimitPerTick = DEFAULT_FAR_SYNC_RATE_LIMIT_PER_TICK;
    public int distantSyncRateLimitPerTick = DEFAULT_DISTANT_SYNC_RATE_LIMIT_PER_TICK;
    public int generationRateLimitPerPlayer = DEFAULT_GENERATION_RATE_LIMIT_PER_PLAYER;
    public int generationConcurrencyLimitPerPlayer = 4;
    public int generationConcurrencyLimitGlobal = 32;
    public int generationStartsPerTickLimit = 2;
    public int generationCompletionsPerTickLimit = 4;
    public int generationPackingThreads = 2;
    public int generationPackingQueueLimit = 32;
    public int generationTimeoutSeconds = 30;
    @Deprecated
    public transient int dirtyBroadcastIntervalSeconds = 2;
    public int dirtyBroadcastIntervalTicks = 10;
    public boolean dirtyVersionCacheEnabled = true;
    public int dirtyVersionCacheMaxEntries = 100000;
    public int dirtyVersionCacheRetentionSeconds = 43200;
    public boolean farPlayerSyncEnabled = true;
    public int farPlayerSyncIntervalTicks = 2;
    public boolean enableColumnCache = true;
    public int columnCacheMaxEntries = 4096;
    public int columnCacheMaxBytes = 32 * BYTES_PER_MIB;
    public boolean enablePersistentColumnCache = true;
    public boolean enablePersistentColumnCompression = true;
    public boolean enableNetworkColumnCompression = true;
    public int persistentColumnCacheMaxMiB = 512;
    public int persistentColumnCacheMaxEntries = 250000;
    public int persistentColumnCacheWriteQueueLimit = 128;
    public int persistentColumnInvalidationBatchSize = 2048;
    public boolean ftbChunksSafeForceLoad = true;
    public int ftbChunksForceLoadTicketsPerTick = 4;

    @Override
    protected String getFileName() {
        return FILE_NAME;
    }

    public int effectiveColumnSyncDistanceChunks() {
        return Math.min(lodDistanceChunks, VSSConstants.MAX_CLIENT_LOD_DISTANCE_CHUNKS);
    }

    @Override
    protected void validate() {
        migrateBandwidthKbpsIfNeeded();
        applyMemorySafeDefaultMigration();
        applyGenerationThroughputDefaultMigration();
        applyBandwidthAndGlobalGenerationDefaultMigration();
        applyMemoryOptimizedDefaults();
        applyLowBandwidthDefaults();
        applyStorageThroughputDefaults();
        applyBandwidthDecoupledDefaults();
        lodDistanceChunks = clamp(lodDistanceChunks, MIN_LOD_DISTANCE_CHUNKS, MAX_LOD_DISTANCE_CHUNKS);
        bytesPerSecondLimitPerPlayer = clamp(bytesPerSecondLimitPerPlayer, MIN_BYTES_PER_SECOND_LIMIT_PER_PLAYER, MAX_BYTES_PER_SECOND_LIMIT_PER_PLAYER);
        sendQueueLimitPerPlayer = clamp(sendQueueLimitPerPlayer, 1, 100000);
        sendQueueBytesLimitPerPlayer = clamp(sendQueueBytesLimitPerPlayer, MIN_SEND_QUEUE_BYTES_PER_PLAYER, MAX_SEND_QUEUE_BYTES_PER_PLAYER);
        diskReaderThreads = clamp(diskReaderThreads, MIN_DISK_READER_THREADS, MAX_DISK_READER_THREADS);
        diskReadQueueLimit = clamp(diskReadQueueLimit, 1, 100000);
        diskReadTimeoutMillis = clamp(diskReadTimeoutMillis, 100, 60000);
        nearSyncRateLimitPerTick = clamp(nearSyncRateLimitPerTick, MIN_SYNC_RATE_LIMIT_PER_TICK, MAX_SYNC_RATE_LIMIT_PER_TICK);
        midSyncRateLimitPerTick = clamp(midSyncRateLimitPerTick, MIN_SYNC_RATE_LIMIT_PER_TICK, MAX_SYNC_RATE_LIMIT_PER_TICK);
        farSyncRateLimitPerTick = clamp(farSyncRateLimitPerTick, MIN_SYNC_RATE_LIMIT_PER_TICK, MAX_SYNC_RATE_LIMIT_PER_TICK);
        distantSyncRateLimitPerTick = clamp(distantSyncRateLimitPerTick, MIN_SYNC_RATE_LIMIT_PER_TICK, MAX_SYNC_RATE_LIMIT_PER_TICK);
        generationRateLimitPerPlayer = clamp(generationRateLimitPerPlayer, 1, 1000);
        generationConcurrencyLimitPerPlayer = clamp(generationConcurrencyLimitPerPlayer, 1, 1000);
        generationConcurrencyLimitGlobal = clamp(generationConcurrencyLimitGlobal, 1, 1000);
        generationStartsPerTickLimit = clamp(generationStartsPerTickLimit, 1, 256);
        generationCompletionsPerTickLimit = clamp(generationCompletionsPerTickLimit, 1, 256);
        generationPackingThreads = clamp(generationPackingThreads, 1, 8);
        generationPackingQueueLimit = clamp(generationPackingQueueLimit, 1, 1024);
        generationTimeoutSeconds = clamp(generationTimeoutSeconds, 1, 600);
        dirtyBroadcastIntervalTicks = clamp(dirtyBroadcastIntervalTicks, MIN_DIRTY_BROADCAST_INTERVAL_TICKS, MAX_DIRTY_BROADCAST_INTERVAL_TICKS);
        dirtyVersionCacheMaxEntries = clamp(dirtyVersionCacheMaxEntries, 1, 5000000);
        dirtyVersionCacheRetentionSeconds = clamp(dirtyVersionCacheRetentionSeconds, 60, 604800);
        farPlayerSyncIntervalTicks = clamp(farPlayerSyncIntervalTicks, 1, 100);
        columnCacheMaxEntries = clamp(columnCacheMaxEntries, 1, 100000);
        columnCacheMaxBytes = clamp(columnCacheMaxBytes, 1 * BYTES_PER_MIB, 512 * BYTES_PER_MIB);
        persistentColumnCacheMaxMiB = clamp(persistentColumnCacheMaxMiB, 64, 65536);
        persistentColumnCacheMaxEntries = clamp(persistentColumnCacheMaxEntries, 1024, 10000000);
        persistentColumnCacheWriteQueueLimit = clamp(persistentColumnCacheWriteQueueLimit, 1, 10000);
        persistentColumnInvalidationBatchSize = clamp(persistentColumnInvalidationBatchSize, 1, VSSConstants.MAX_DIRTY_COLUMN_POSITIONS);
        ftbChunksForceLoadTicketsPerTick = clamp(ftbChunksForceLoadTicketsPerTick, 1, 64);
    }

    private void applyMemorySafeDefaultMigration() {
        if (memorySafeDefaultsApplied) {
            return;
        }
        if (bandwidthEqualsBytes(OLD_DEFAULT_BYTES_PER_SECOND_LIMIT_PER_PLAYER)) {
            setPerPlayerBandwidthBytesUnchecked(PREVIOUS_DEFAULT_BYTES_PER_SECOND_LIMIT_PER_PLAYER);
        }
        if (sendQueueLimitPerPlayer == OLD_DEFAULT_SEND_QUEUE_LIMIT_PER_PLAYER) {
            sendQueueLimitPerPlayer = 1000;
        }
        if (generationRateLimitPerPlayer == OLD_DEFAULT_GENERATION_RATE_LIMIT_PER_PLAYER) {
            generationRateLimitPerPlayer = 40;
        }
        if (generationConcurrencyLimitPerPlayer == OLD_DEFAULT_GENERATION_CONCURRENCY_LIMIT_PER_PLAYER) {
            generationConcurrencyLimitPerPlayer = 4;
        }
        if (generationConcurrencyLimitGlobal == OLD_DEFAULT_GENERATION_CONCURRENCY_LIMIT_GLOBAL) {
            generationConcurrencyLimitGlobal = 8;
        }
        if (generationTimeoutSeconds == OLD_DEFAULT_GENERATION_TIMEOUT_SECONDS) {
            generationTimeoutSeconds = 30;
        }
        memorySafeDefaultsApplied = true;
    }

    private void applyGenerationThroughputDefaultMigration() {
        if (generationThroughputDefaultsApplied) {
            return;
        }
        if (generationConcurrencyLimitPerPlayer == CONSERVATIVE_GENERATION_CONCURRENCY_LIMIT_PER_PLAYER) {
            generationConcurrencyLimitPerPlayer = 8;
        }
        if (generationConcurrencyLimitGlobal == CONSERVATIVE_GENERATION_CONCURRENCY_LIMIT_GLOBAL) {
            generationConcurrencyLimitGlobal = PREVIOUS_DEFAULT_GENERATION_CONCURRENCY_LIMIT_GLOBAL;
        }
        if (generationTimeoutSeconds == CONSERVATIVE_GENERATION_TIMEOUT_SECONDS) {
            generationTimeoutSeconds = 60;
        }
        generationThroughputDefaultsApplied = true;
    }

    private void applyBandwidthAndGlobalGenerationDefaultMigration() {
        if (bandwidthAndGlobalGenerationDefaultsApplied) {
            return;
        }
        if (bandwidthEqualsBytes(PREVIOUS_DEFAULT_BYTES_PER_SECOND_LIMIT_PER_PLAYER)) {
            setPerPlayerBandwidthBytesUnchecked(3 * BYTES_PER_MIB);
        }
        if (generationConcurrencyLimitGlobal == PREVIOUS_DEFAULT_GENERATION_CONCURRENCY_LIMIT_GLOBAL) {
            generationConcurrencyLimitGlobal = 128;
        }
        bandwidthAndGlobalGenerationDefaultsApplied = true;
    }

    private void applyMemoryOptimizedDefaults() {
        if (memoryOptimizedDefaultsApplied) {
            return;
        }
        // Lower the default LOD distance for memory-optimized installs.
        if (lodDistanceChunks == 256) {
            lodDistanceChunks = 128;
        }
        // Lower bandwidth pressure for memory-optimized installs.
        if (bandwidthEqualsBytes(3 * BYTES_PER_MIB)) {
            setPerPlayerBandwidthBytesUnchecked(2 * BYTES_PER_MIB);
        }
        // Reduce the send queue size.
        if (sendQueueLimitPerPlayer == 1000) {
            sendQueueLimitPerPlayer = 500;
        }
        if (sendQueueBytesLimitPerPlayer == 32 * BYTES_PER_MIB) {
            sendQueueBytesLimitPerPlayer = 16 * BYTES_PER_MIB;
        }
        // 闄嶄綆鍚屾骞跺彂
        // 澶у箙闄嶄綆鐢熸垚骞跺彂锛岃繖鏄渶澶х殑鍐呭瓨娑堣€楁簮
        if (generationRateLimitPerPlayer == 40) {
            generationRateLimitPerPlayer = 20;
        }
        if (generationConcurrencyLimitPerPlayer == 8) {
            generationConcurrencyLimitPerPlayer = 4;
        }
        if (generationConcurrencyLimitGlobal == 128) {
            generationConcurrencyLimitGlobal = 32;
        }
        if (generationStartsPerTickLimit == 4) {
            generationStartsPerTickLimit = 2;
        }
        if (generationCompletionsPerTickLimit == 8) {
            generationCompletionsPerTickLimit = 4;
        }
        if (generationPackingQueueLimit == 64) {
            generationPackingQueueLimit = 32;
        }
        // 闄嶄綆缂撳瓨澶у皬
        if (columnCacheMaxEntries == 8192) {
            columnCacheMaxEntries = 4096;
        }
        if (columnCacheMaxBytes == 64 * BYTES_PER_MIB) {
            columnCacheMaxBytes = 32 * BYTES_PER_MIB;
        }
        if (persistentColumnCacheMaxMiB == 2048) {
            persistentColumnCacheMaxMiB = 512;
        }
        if (persistentColumnCacheMaxEntries == 500000) {
            persistentColumnCacheMaxEntries = 250000;
        }
        if (persistentColumnCacheWriteQueueLimit == 256) {
            persistentColumnCacheWriteQueueLimit = 128;
        }
        if (dirtyVersionCacheMaxEntries == 200000) {
            dirtyVersionCacheMaxEntries = 100000;
        }
        // 鍚敤缃戠粶鍘嬬缉鍑忓皯甯﹀鍗犵敤
        if (!enableNetworkColumnCompression) {
            enableNetworkColumnCompression = true;
        }
        // Increase broadcast intervals to reduce frequent updates.
        if (dirtyBroadcastIntervalTicks == 5) {
            dirtyBroadcastIntervalTicks = 10;
        }
        if (farPlayerSyncIntervalTicks == 5) {
            farPlayerSyncIntervalTicks = 10;
        }
        memoryOptimizedDefaultsApplied = true;
    }

    private void applyLowBandwidthDefaults() {
        if (lowBandwidthDefaultsApplied) {
            return;
        }
        lowBandwidthDefaultsApplied = true;
    }

    private void applyStorageThroughputDefaults() {
        if (storageThroughputDefaultsApplied) {
            return;
        }
        if (diskReaderThreads == 1) {
            diskReaderThreads = DEFAULT_DISK_READER_THREADS;
        }
        storageThroughputDefaultsApplied = true;
    }

    private void applyBandwidthDecoupledDefaults() {
        if (bandwidthDecoupledDefaultsApplied) {
            return;
        }
        if (lowBandwidthDefaultsApplied && bandwidthEqualsBytes(LOW_BANDWIDTH_DEFAULT_BYTES_PER_SECOND_PER_PLAYER)) {
            setPerPlayerBandwidthBytesUnchecked(DEFAULT_BYTES_PER_SECOND_LIMIT_PER_PLAYER);
        }
        if (lowBandwidthDefaultsApplied && sendQueueLimitPerPlayer == LOW_BANDWIDTH_DEFAULT_SEND_QUEUE_LIMIT_PER_PLAYER) {
            sendQueueLimitPerPlayer = DEFAULT_SEND_QUEUE_LIMIT_PER_PLAYER;
        }
        if (lowBandwidthDefaultsApplied && sendQueueBytesLimitPerPlayer == LOW_BANDWIDTH_DEFAULT_SEND_QUEUE_BYTES_PER_PLAYER) {
            sendQueueBytesLimitPerPlayer = DEFAULT_SEND_QUEUE_BYTES_PER_PLAYER;
        }
        if (lowBandwidthDefaultsApplied && generationRateLimitPerPlayer == LOW_BANDWIDTH_DEFAULT_GENERATION_RATE_LIMIT_PER_PLAYER) {
            generationRateLimitPerPlayer = DEFAULT_GENERATION_RATE_LIMIT_PER_PLAYER;
        }
        bandwidthDecoupledDefaultsApplied = true;
    }

    public void normalizeAndSave() {
        validate();
        save();
    }

    public void setPerPlayerBandwidthBytes(int bytesPerSecond) {
        bytesPerSecondLimitPerPlayer = bytesPerSecond;
        normalizeAndSave();
    }

    public void setPerPlayerBandwidthMiB(int mibPerSecond) {
        setPerPlayerBandwidthBytes(Math.multiplyExact(mibPerSecond, BYTES_PER_MIB));
    }

    public void setPerPlayerBandwidthKbps(int kbps) {
        setPerPlayerBandwidthKbpsUnsaved(kbps);
        normalizeAndSave();
    }

    public void setPerPlayerBandwidthKbpsUnsaved(int kbps) {
        bytesPerSecondLimitPerPlayer = kbpsToBytesPerSecond(clamp(kbps, MIN_BANDWIDTH_KBPS_PER_PLAYER, MAX_BANDWIDTH_KBPS_PER_PLAYER));
    }

    public int bandwidthBytesPerSecond() {
        return bytesPerSecondLimitPerPlayer;
    }

    public int getPerPlayerBandwidthKbpsRounded() {
        return bytesToKbpsCeil(bytesPerSecondLimitPerPlayer);
    }

    public int getSendQueueBytesMiBRounded() {
        return Math.max(1, Math.round(sendQueueBytesLimitPerPlayer / (float) BYTES_PER_MIB));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void migrateBandwidthKbpsIfNeeded() {
        if (bandwidthLimitKbpsPerPlayer != null) {
            if (bandwidthLimitKbpsPerPlayer > 0) {
                bytesPerSecondLimitPerPlayer = kbpsToBytesPerSecond(clamp(
                        bandwidthLimitKbpsPerPlayer,
                        MIN_BANDWIDTH_KBPS_PER_PLAYER,
                        MAX_BANDWIDTH_KBPS_PER_PLAYER));
            }
            bandwidthLimitKbpsPerPlayer = null;
        }
    }

    private static int bytesToKbpsCeil(int bytesPerSecond) {
        return Math.toIntExact(((long) bytesPerSecond * 8L + 999L) / 1000L);
    }

    private boolean bandwidthEqualsBytes(int bytesPerSecond) {
        return bytesPerSecondLimitPerPlayer == bytesPerSecond;
    }

    private void setPerPlayerBandwidthBytesUnchecked(int bytesPerSecond) {
        bytesPerSecondLimitPerPlayer = clamp(
                bytesPerSecond,
                MIN_BYTES_PER_SECOND_LIMIT_PER_PLAYER,
                MAX_BYTES_PER_SECOND_LIMIT_PER_PLAYER);
    }

    private static int kbpsToBytesPerSecond(int kbps) {
        return Math.max(1, kbps * 125);
    }
}
