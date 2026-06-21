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
    public boolean enabled = true;
    public int lodDistanceChunks = 256;
    public int bytesPerSecondLimitPerPlayer = 3 * BYTES_PER_MIB;
    public int sendQueueLimitPerPlayer = 1000;
    public int sendQueueBytesLimitPerPlayer = 32 * BYTES_PER_MIB;
    public int diskReaderThreads = 1;
    public int diskReadTimeoutMillis = 1500;
    public boolean enableChunkGeneration = true;
    public int syncOnLoadRateLimitPerPlayer = 120;
    public int syncOnLoadConcurrencyLimitPerPlayer = 24;
    public int generationRateLimitPerPlayer = 40;
    public int generationConcurrencyLimitPerPlayer = 8;
    public int generationConcurrencyLimitGlobal = 128;
    public int generationStartsPerTickLimit = 4;
    public int generationCompletionsPerTickLimit = 8;
    public int generationPackingThreads = 2;
    public int generationPackingQueueLimit = 64;
    public int generationTimeoutSeconds = 60;
    public int dirtyBroadcastIntervalSeconds = 2;
    public boolean dirtyVersionCacheEnabled = true;
    public int dirtyVersionCacheMaxEntries = 200000;
    public int dirtyVersionCacheRetentionSeconds = 86400;
    public boolean farPlayerSyncEnabled = true;
    public int farPlayerSyncIntervalTicks = 5;
    public boolean enableColumnCache = true;
    public int columnCacheMaxEntries = 8192;
    public int columnCacheMaxBytes = 64 * BYTES_PER_MIB;
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
        dirtyBroadcastIntervalSeconds = clamp(dirtyBroadcastIntervalSeconds, 1, 300);
        dirtyVersionCacheMaxEntries = clamp(dirtyVersionCacheMaxEntries, 1, 5000000);
        dirtyVersionCacheRetentionSeconds = clamp(dirtyVersionCacheRetentionSeconds, 60, 604800);
        farPlayerSyncIntervalTicks = clamp(farPlayerSyncIntervalTicks, 1, 100);
        columnCacheMaxEntries = clamp(columnCacheMaxEntries, 1, 100000);
        columnCacheMaxBytes = clamp(columnCacheMaxBytes, 1 * BYTES_PER_MIB, 512 * BYTES_PER_MIB);
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
