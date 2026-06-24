package dev.xantha.vss.config;

public class VSSServerConfig extends JsonConfig {
    private static final String FILE_NAME = "vss-server-config.json";
    public static final int MIN_LOD_DISTANCE_CHUNKS = 1;
    public static final int MAX_LOD_DISTANCE_CHUNKS = 8196;
    public static final int MIN_BYTES_PER_SECOND_LIMIT_PER_PLAYER = 1024;
    public static final int MAX_BYTES_PER_SECOND_LIMIT_PER_PLAYER = 0x6400000;
    public static final int BYTES_PER_MIB = 1024 * 1024;
    public static final int MIN_SEND_QUEUE_BYTES_PER_PLAYER = 4 * BYTES_PER_MIB;
    public static final int MAX_SEND_QUEUE_BYTES_PER_PLAYER = 512 * BYTES_PER_MIB;
    public static final int MIN_DIRTY_BROADCAST_INTERVAL_TICKS = 1;
    public static final int MAX_DIRTY_BROADCAST_INTERVAL_TICKS = 600;
    private static final int OLD_DEFAULT_BYTES_PER_SECOND_LIMIT_PER_PLAYER = 0x1400000;
    private static final int OLD_DEFAULT_SEND_QUEUE_LIMIT_PER_PLAYER = 4000;
    private static final int OLD_DEFAULT_SYNC_ON_LOAD_RATE_LIMIT_PER_PLAYER = 800;
    private static final int OLD_DEFAULT_SYNC_ON_LOAD_CONCURRENCY_LIMIT_PER_PLAYER = 200;
    private static final int OLD_DEFAULT_GENERATION_RATE_LIMIT_PER_PLAYER = 80;
    private static final int OLD_DEFAULT_GENERATION_CONCURRENCY_LIMIT_PER_PLAYER = 16;
    private static final int OLD_DEFAULT_GENERATION_CONCURRENCY_LIMIT_GLOBAL = 32;
    private static final int OLD_DEFAULT_GENERATION_TIMEOUT_SECONDS = 60;
    private static final int CONSERVATIVE_GENERATION_CONCURRENCY_LIMIT_PER_PLAYER = 4;
    private static final int CONSERVATIVE_GENERATION_CONCURRENCY_LIMIT_GLOBAL = 8;
    private static final int CONSERVATIVE_GENERATION_TIMEOUT_SECONDS = 30;
    private static final int PREVIOUS_DEFAULT_BYTES_PER_SECOND_LIMIT_PER_PLAYER = 4 * BYTES_PER_MIB;
    private static final int PREVIOUS_DEFAULT_GENERATION_CONCURRENCY_LIMIT_GLOBAL = 24;
    public static final VSSServerConfig CONFIG = load(VSSServerConfig.class, FILE_NAME);

    public boolean memorySafeDefaultsApplied = false;
    public boolean generationThroughputDefaultsApplied = false;
    public boolean bandwidthAndGlobalGenerationDefaultsApplied = false;
    public boolean memoryOptimizedDefaultsApplied = false;
    public boolean enabled = true;
    public int lodDistanceChunks = 128;
    public int bytesPerSecondLimitPerPlayer = 2 * BYTES_PER_MIB;
    public int sendQueueLimitPerPlayer = 500;
    public int sendQueueBytesLimitPerPlayer = 16 * BYTES_PER_MIB;
    public int diskReaderThreads = 1;
    public int diskReadTimeoutMillis = 1500;
    public boolean enableChunkNbtColumnSync = false;
    public boolean enableChunkGeneration = true;
    public int syncOnLoadRateLimitPerPlayer = 80;
    public int syncOnLoadConcurrencyLimitPerPlayer = 16;
    public int generationRateLimitPerPlayer = 20;
    public int generationConcurrencyLimitPerPlayer = 4;
    public int generationConcurrencyLimitGlobal = 32;
    public int generationStartsPerTickLimit = 2;
    public int generationCompletionsPerTickLimit = 4;
    public int generationPackingThreads = 2;
    public int generationPackingQueueLimit = 32;
    public int generationTimeoutSeconds = 45;
    @Deprecated
    public transient int dirtyBroadcastIntervalSeconds = 2;
    public int dirtyBroadcastIntervalTicks = 10;
    public boolean dirtyVersionCacheEnabled = true;
    public int dirtyVersionCacheMaxEntries = 100000;
    public int dirtyVersionCacheRetentionSeconds = 43200;
    public boolean farPlayerSyncEnabled = true;
    public int farPlayerSyncIntervalTicks = 10;
    public boolean enableColumnCache = true;
    public int columnCacheMaxEntries = 4096;
    public int columnCacheMaxBytes = 32 * BYTES_PER_MIB;
    public boolean enablePersistentColumnCache = true;
    public boolean enablePersistentColumnCompression = true;
    public boolean enableNetworkColumnCompression = true;
    public int persistentColumnCacheMaxMiB = 512;
    public int persistentColumnCacheMaxEntries = 250000;
    public int persistentColumnCacheWriteQueueLimit = 128;
    public boolean ftbChunksSafeForceLoad = true;
    public int ftbChunksForceLoadTicketsPerTick = 4;

    @Override
    protected String getFileName() {
        return FILE_NAME;
    }

    @Override
    protected void validate() {
        applyMemorySafeDefaultMigration();
        applyGenerationThroughputDefaultMigration();
        applyBandwidthAndGlobalGenerationDefaultMigration();
        applyMemoryOptimizedDefaults();
        lodDistanceChunks = clamp(lodDistanceChunks, MIN_LOD_DISTANCE_CHUNKS, MAX_LOD_DISTANCE_CHUNKS);
        bytesPerSecondLimitPerPlayer = clamp(bytesPerSecondLimitPerPlayer, MIN_BYTES_PER_SECOND_LIMIT_PER_PLAYER, MAX_BYTES_PER_SECOND_LIMIT_PER_PLAYER);
        sendQueueLimitPerPlayer = clamp(sendQueueLimitPerPlayer, 1, 100000);
        sendQueueBytesLimitPerPlayer = clamp(sendQueueBytesLimitPerPlayer, MIN_SEND_QUEUE_BYTES_PER_PLAYER, MAX_SEND_QUEUE_BYTES_PER_PLAYER);
        diskReaderThreads = clamp(diskReaderThreads, 1, 8);
        diskReadTimeoutMillis = clamp(diskReadTimeoutMillis, 100, 60000);
        syncOnLoadRateLimitPerPlayer = clamp(syncOnLoadRateLimitPerPlayer, 1, 1000);
        syncOnLoadConcurrencyLimitPerPlayer = clamp(syncOnLoadConcurrencyLimitPerPlayer, 1, 1000);
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
        ftbChunksForceLoadTicketsPerTick = clamp(ftbChunksForceLoadTicketsPerTick, 1, 64);
    }

    private void applyMemorySafeDefaultMigration() {
        if (memorySafeDefaultsApplied) {
            return;
        }
        if (bytesPerSecondLimitPerPlayer == OLD_DEFAULT_BYTES_PER_SECOND_LIMIT_PER_PLAYER) {
            bytesPerSecondLimitPerPlayer = PREVIOUS_DEFAULT_BYTES_PER_SECOND_LIMIT_PER_PLAYER;
        }
        if (sendQueueLimitPerPlayer == OLD_DEFAULT_SEND_QUEUE_LIMIT_PER_PLAYER) {
            sendQueueLimitPerPlayer = 1000;
        }
        if (syncOnLoadRateLimitPerPlayer == OLD_DEFAULT_SYNC_ON_LOAD_RATE_LIMIT_PER_PLAYER) {
            syncOnLoadRateLimitPerPlayer = 320;
        }
        if (syncOnLoadConcurrencyLimitPerPlayer == OLD_DEFAULT_SYNC_ON_LOAD_CONCURRENCY_LIMIT_PER_PLAYER) {
            syncOnLoadConcurrencyLimitPerPlayer = 64;
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
        if (bytesPerSecondLimitPerPlayer == PREVIOUS_DEFAULT_BYTES_PER_SECOND_LIMIT_PER_PLAYER) {
            bytesPerSecondLimitPerPlayer = 3 * BYTES_PER_MIB;
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
        // 降低默认LOD距离从256到128区块，减少75%的内存占用
        if (lodDistanceChunks == 256) {
            lodDistanceChunks = 128;
        }
        // 降低带宽限制，减少网络缓冲
        if (bytesPerSecondLimitPerPlayer == 3 * BYTES_PER_MIB) {
            bytesPerSecondLimitPerPlayer = 2 * BYTES_PER_MIB;
        }
        // 减少发送队列大小
        if (sendQueueLimitPerPlayer == 1000) {
            sendQueueLimitPerPlayer = 500;
        }
        if (sendQueueBytesLimitPerPlayer == 32 * BYTES_PER_MIB) {
            sendQueueBytesLimitPerPlayer = 16 * BYTES_PER_MIB;
        }
        // 降低同步并发
        if (syncOnLoadRateLimitPerPlayer == 120) {
            syncOnLoadRateLimitPerPlayer = 80;
        }
        if (syncOnLoadConcurrencyLimitPerPlayer == 24) {
            syncOnLoadConcurrencyLimitPerPlayer = 16;
        }
        // 大幅降低生成并发，这是最大的内存消耗源
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
        // 降低缓存大小
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
        // 启用网络压缩减少带宽占用
        if (!enableNetworkColumnCompression) {
            enableNetworkColumnCompression = true;
        }
        // 增加广播间隔，减少频繁更新
        if (dirtyBroadcastIntervalTicks == 5) {
            dirtyBroadcastIntervalTicks = 10;
        }
        if (farPlayerSyncIntervalTicks == 5) {
            farPlayerSyncIntervalTicks = 10;
        }
        memoryOptimizedDefaultsApplied = true;
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

    public int getPerPlayerBandwidthMiBRounded() {
        return Math.max(1, Math.round(bytesPerSecondLimitPerPlayer / (float) BYTES_PER_MIB));
    }

    public int getSendQueueBytesMiBRounded() {
        return Math.max(1, Math.round(sendQueueBytesLimitPerPlayer / (float) BYTES_PER_MIB));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
