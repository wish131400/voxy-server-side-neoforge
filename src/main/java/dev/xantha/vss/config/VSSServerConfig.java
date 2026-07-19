package dev.xantha.vss.config;

import com.google.gson.annotations.SerializedName;
import dev.xantha.vss.common.VSSConstants;
import java.util.LinkedHashMap;
import java.util.Map;

public class VSSServerConfig extends JsonConfig {
    private static final String FILE_NAME = "vss-server-config.json";
    public static final String CURRENT_CONFIG_VERSION = "v0.2.9";
    public static final int MIN_LOD_DISTANCE_CHUNKS = 1;
    public static final int MAX_LOD_DISTANCE_CHUNKS = VSSConstants.MAX_CLIENT_LOD_DISTANCE_CHUNKS;
    public static final int BYTES_PER_MIB = 1024 * 1024;
    public static final int MIN_TOTAL_BANDWIDTH_BYTES_PER_SECOND = 62_500;
    public static final int MAX_TOTAL_BANDWIDTH_BYTES_PER_SECOND = 100_000 * 125;
    public static final int MIN_TOTAL_BANDWIDTH_KBPS = bytesToKbpsCeil(MIN_TOTAL_BANDWIDTH_BYTES_PER_SECOND);
    public static final int MAX_TOTAL_BANDWIDTH_KBPS = 100_000;
    public static final int KBPS_PER_MBPS = 1000;
    public static final int MIN_SEND_QUEUE_LIMIT_PER_PLAYER = 1;
    public static final int MAX_SEND_QUEUE_LIMIT_PER_PLAYER = 8192;
    public static final int MIN_SEND_QUEUE_BYTES_PER_PLAYER = 4 * BYTES_PER_MIB;
    public static final int MAX_SEND_QUEUE_BYTES_PER_PLAYER = 128 * BYTES_PER_MIB;
    public static final int DEFAULT_DISK_READER_THREADS = 4;
    public static final int MIN_DISK_READER_THREADS = 1;
    public static final int MAX_DISK_READER_THREADS = 16;
    public static final int MIN_DIRTY_BROADCAST_INTERVAL_TICKS = 1;
    public static final int MAX_DIRTY_BROADCAST_INTERVAL_TICKS = 600;
    public static final int MIN_SYNC_RATE_LIMIT_PER_TICK = 0;
    public static final int MAX_SYNC_RATE_LIMIT_PER_TICK = 256;
    public static final int DEFAULT_NEAR_SYNC_RATE_LIMIT_PER_TICK = 0;
    public static final int DEFAULT_MID_SYNC_RATE_LIMIT_PER_TICK = 8;
    public static final int DEFAULT_FAR_SYNC_RATE_LIMIT_PER_TICK = 4;
    public static final int DEFAULT_DISTANT_SYNC_RATE_LIMIT_PER_TICK = 2;
    public static final int MIN_GENERATION_LIMIT = 1;
    public static final int MAX_GENERATION_CONCURRENCY_LIMIT_PER_PLAYER = 128;
    public static final int MAX_GENERATION_CONCURRENCY_LIMIT_GLOBAL = 1024;
    private static final int OLD_DEFAULT_BYTES_PER_SECOND_LIMIT_PER_PLAYER = 0x1400000;
    private static final int OLD_DEFAULT_SEND_QUEUE_LIMIT_PER_PLAYER = 4000;
    private static final int OLD_DEFAULT_GENERATION_CONCURRENCY_LIMIT_PER_PLAYER = 16;
    private static final int OLD_DEFAULT_GENERATION_CONCURRENCY_LIMIT_GLOBAL = 32;
    private static final int CONSERVATIVE_GENERATION_CONCURRENCY_LIMIT_PER_PLAYER = 4;
    private static final int CONSERVATIVE_GENERATION_CONCURRENCY_LIMIT_GLOBAL = 8;
    private static final int PREVIOUS_DEFAULT_BYTES_PER_SECOND_LIMIT_PER_PLAYER = 4 * BYTES_PER_MIB;
    private static final int PREVIOUS_DEFAULT_GENERATION_CONCURRENCY_LIMIT_GLOBAL = 24;
    private static final int LEGACY_DEFAULT_BANDWIDTH_KBPS_PER_PLAYER = KBPS_PER_MBPS;
    private static final int LEGACY_DEFAULT_BYTES_PER_SECOND_LIMIT_PER_PLAYER = kbpsToBytesPerSecond(LEGACY_DEFAULT_BANDWIDTH_KBPS_PER_PLAYER);
    private static final int DEFAULT_TOTAL_BANDWIDTH_KBPS = 8 * KBPS_PER_MBPS;
    private static final int DEFAULT_TOTAL_BANDWIDTH_BYTES_PER_SECOND = kbpsToBytesPerSecond(DEFAULT_TOTAL_BANDWIDTH_KBPS);
    private static final int DEFAULT_SEND_QUEUE_LIMIT_PER_PLAYER = 1024;
    private static final int DEFAULT_SEND_QUEUE_BYTES_PER_PLAYER = 32 * BYTES_PER_MIB;
    private static final int LOW_BANDWIDTH_DEFAULT_KBPS_PER_PLAYER = 500;
    private static final int LOW_BANDWIDTH_DEFAULT_BYTES_PER_SECOND_PER_PLAYER = kbpsToBytesPerSecond(LOW_BANDWIDTH_DEFAULT_KBPS_PER_PLAYER);
    private static final int LOW_BANDWIDTH_DEFAULT_SEND_QUEUE_LIMIT_PER_PLAYER = 512;
    private static final int LOW_BANDWIDTH_DEFAULT_SEND_QUEUE_BYTES_PER_PLAYER = 8 * BYTES_PER_MIB;
    private static final Map<String, String> CONFIG_HELP = createConfigHelp();
    public static final VSSServerConfig CONFIG = load(VSSServerConfig.class, FILE_NAME);

    public String configVersion;
    @Deprecated
    @SerializedName("memorySafeDefaultsApplied")
    private Boolean legacyMemorySafeDefaultsApplied;
    @Deprecated
    @SerializedName("generationThroughputDefaultsApplied")
    private Boolean legacyGenerationThroughputDefaultsApplied;
    @Deprecated
    @SerializedName("bandwidthAndGlobalGenerationDefaultsApplied")
    private Boolean legacyBandwidthAndGlobalGenerationDefaultsApplied;
    @Deprecated
    @SerializedName("memoryOptimizedDefaultsApplied")
    private Boolean legacyMemoryOptimizedDefaultsApplied;
    @Deprecated
    @SerializedName("lowBandwidthDefaultsApplied")
    private Boolean legacyLowBandwidthDefaultsApplied;
    @Deprecated
    @SerializedName("storageThroughputDefaultsApplied")
    private Boolean legacyStorageThroughputDefaultsApplied;
    @Deprecated
    @SerializedName("bandwidthDecoupledDefaultsApplied")
    private Boolean legacyBandwidthDecoupledDefaultsApplied;
    public boolean enabled = true;
    public boolean debugLogging = false;
    public int lodDistanceChunks = 128;
    public int totalBandwidthBytesPerSecond = DEFAULT_TOTAL_BANDWIDTH_BYTES_PER_SECOND;
    @Deprecated
    @SerializedName("bytesPerSecondLimitPerPlayer")
    private Integer legacyBytesPerSecondLimitPerPlayer;
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
    public int generationConcurrencyLimitPerPlayer = 4;
    public int generationConcurrencyLimitGlobal = 32;
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

    @Override
    protected Map<String, String> getConfigHelp() {
        return CONFIG_HELP;
    }

    private static Map<String, String> createConfigHelp() {
        Map<String, String> help = new LinkedHashMap<>();
        help.put("configVersion", "配置结构版本，升级时自动迁移；当前版本 " + CURRENT_CONFIG_VERSION + "，请勿手动修改。");
        help.put("enabled", "是否启用服务端 VSS LOD 同步；默认 true。");
        help.put("debugLogging", "是否输出 VSS 调试日志；默认 false。");
        help.put("lodDistanceChunks", "服务端 LOD 半径，单位区块；默认 128；范围 1-" + MAX_LOD_DISTANCE_CHUNKS + "。");
        help.put("totalBandwidthBytesPerSecond", "全服共享的 LOD 总发送带宽，单位 B/s；默认 1000000（8 Mbps）；范围 "
                + MIN_TOTAL_BANDWIDTH_BYTES_PER_SECOND + "-" + MAX_TOTAL_BANDWIDTH_BYTES_PER_SECOND + "（约 0.5-100 Mbps）。");
        help.put("sendQueueLimitPerPlayer", "每名玩家待发送 LOD 列数量上限；默认 1024；范围 "
                + MIN_SEND_QUEUE_LIMIT_PER_PLAYER + "-" + MAX_SEND_QUEUE_LIMIT_PER_PLAYER + "。");
        help.put("sendQueueBytesLimitPerPlayer", "每名玩家待发送 LOD 数据内存上限，单位字节；默认 16 MiB；范围 4-128 MiB。");
        help.put("diskReaderThreads", "持久化缓存和 NBT 读取线程数；默认 4；范围 "
                + MIN_DISK_READER_THREADS + "-" + MAX_DISK_READER_THREADS + "。");
        help.put("diskReadQueueLimit", "磁盘读取任务队列上限；默认 4096；范围 1-100000。");
        help.put("diskReadTimeoutMillis", "单次磁盘读取超时，单位毫秒；默认 1000；范围 100-60000。");
        help.put("enableChunkNbtColumnSync", "是否允许从区块 NBT 读取 LOD；默认 true。");
        help.put("enableChunkGeneration", "缺少 LOD 时是否允许服务端生成；默认 true。");
        help.put("nearSyncRateLimitPerTick", "0-32 区块已有 LOD 请求数；默认 0；手动范围 0-" + MAX_SYNC_RATE_LIMIT_PER_TICK + "，0 表示不限速。");
        help.put("midSyncRateLimitPerTick", "33-64 区块已有 LOD 请求数；默认 8；范围 0-" + MAX_SYNC_RATE_LIMIT_PER_TICK + "，0 表示关闭此档。");
        help.put("farSyncRateLimitPerTick", "65-128 区块已有 LOD 请求数；默认 4；范围 0-" + MAX_SYNC_RATE_LIMIT_PER_TICK + "，0 表示关闭此档。");
        help.put("distantSyncRateLimitPerTick", "129 区块外已有 LOD 请求数；默认 2；范围 0-" + MAX_SYNC_RATE_LIMIT_PER_TICK + "，0 表示关闭此档。");
        help.put("generationConcurrencyLimitPerPlayer", "每名玩家在途生成任务数，不是线程数；默认 4；范围 1-" + MAX_GENERATION_CONCURRENCY_LIMIT_PER_PLAYER + "。");
        help.put("generationConcurrencyLimitGlobal", "全服在途生成任务数，不是线程数，也是自动后台调度的上界；默认 32；范围 1-" + MAX_GENERATION_CONCURRENCY_LIMIT_GLOBAL + "。");
        help.put("dirtyBroadcastIntervalTicks", "脏列版本广播间隔，单位 tick；默认 10；范围 "
                + MIN_DIRTY_BROADCAST_INTERVAL_TICKS + "-" + MAX_DIRTY_BROADCAST_INTERVAL_TICKS + "。");
        help.put("dirtyVersionCacheEnabled", "是否启用脏列版本缓存；默认 true。");
        help.put("dirtyVersionCacheMaxEntries", "脏列版本缓存最大条目数；默认 100000；范围 1-5000000。");
        help.put("dirtyVersionCacheRetentionSeconds", "脏列版本保留时间，单位秒；默认 43200；范围 60-604800。");
        help.put("farPlayerSyncEnabled", "是否同步超出原版跟踪范围的玩家和载具；默认 true。");
        help.put("farPlayerSyncIntervalTicks", "远处玩家同步间隔，单位 tick；新配置默认 2，建议多人服使用 10；范围 1-100。");
        help.put("enableColumnCache", "是否启用内存列缓存；默认 true。");
        help.put("columnCacheMaxEntries", "内存列缓存最大条目数；默认 4096；范围 1-100000。");
        help.put("columnCacheMaxBytes", "内存列缓存最大字节数；默认 32 MiB；范围 1-512 MiB。");
        help.put("enablePersistentColumnCache", "是否启用世界持久化 .vcl 缓存；默认 true。");
        help.put("enablePersistentColumnCompression", "是否压缩持久化 .vcl 数据；默认 true。");
        help.put("enableNetworkColumnCompression", "是否压缩网络 LOD 数据；默认 true。");
        help.put("persistentColumnCacheMaxMiB", "持久化列缓存大小，单位 MiB；默认 512；范围 64-65536。");
        help.put("persistentColumnCacheMaxEntries", "持久化列缓存最大条目数；默认 250000；范围 1024-10000000。");
        help.put("persistentColumnCacheWriteQueueLimit", "持久化缓存写入队列上限；默认 128；范围 1-10000。");
        help.put("persistentColumnInvalidationBatchSize", "持久化缓存失效处理批量大小；默认 2048；范围 1-" + VSSConstants.MAX_DIRTY_COLUMN_POSITIONS + "。");
        help.put("ftbChunksSafeForceLoad", "是否启用 FTB Chunks 安全强制加载兼容；默认 true。");
        help.put("ftbChunksForceLoadTicketsPerTick", "FTB Chunks 每 tick 强制加载票据数；默认 4；范围 1-64。");
        return help;
    }

    public int effectiveColumnSyncDistanceChunks() {
        return Math.min(lodDistanceChunks, VSSConstants.MAX_CLIENT_LOD_DISTANCE_CHUNKS);
    }

    public int automaticGenerationPackingThreads() {
        return AutomaticGenerationSettings.packingThreads(availableProcessors(), generationConcurrencyLimitGlobal);
    }

    public int automaticGenerationStartsPerTick() {
        return AutomaticGenerationSettings.startsPerTick(availableProcessors(), generationConcurrencyLimitGlobal);
    }

    public int automaticGenerationCompletionsPerTick() {
        return AutomaticGenerationSettings.completionsPerTick(availableProcessors(), generationConcurrencyLimitGlobal);
    }

    public int automaticGenerationPackingQueueLimit() {
        return AutomaticGenerationSettings.packingQueueLimit(availableProcessors(), generationConcurrencyLimitGlobal);
    }

    public int automaticGenerationTimeoutSeconds() {
        return AutomaticGenerationSettings.TIMEOUT_SECONDS;
    }

    private static int availableProcessors() {
        return Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    @Override
    protected void validate() {
        migrateLegacyBandwidthIfNeeded();
        migrateConfigVersion();
        lodDistanceChunks = clamp(lodDistanceChunks, MIN_LOD_DISTANCE_CHUNKS, MAX_LOD_DISTANCE_CHUNKS);
        totalBandwidthBytesPerSecond = clamp(totalBandwidthBytesPerSecond, MIN_TOTAL_BANDWIDTH_BYTES_PER_SECOND, MAX_TOTAL_BANDWIDTH_BYTES_PER_SECOND);
        sendQueueLimitPerPlayer = clamp(sendQueueLimitPerPlayer, MIN_SEND_QUEUE_LIMIT_PER_PLAYER, MAX_SEND_QUEUE_LIMIT_PER_PLAYER);
        sendQueueBytesLimitPerPlayer = clamp(sendQueueBytesLimitPerPlayer, MIN_SEND_QUEUE_BYTES_PER_PLAYER, MAX_SEND_QUEUE_BYTES_PER_PLAYER);
        diskReaderThreads = clamp(diskReaderThreads, MIN_DISK_READER_THREADS, MAX_DISK_READER_THREADS);
        diskReadQueueLimit = clamp(diskReadQueueLimit, 1, 100000);
        diskReadTimeoutMillis = clamp(diskReadTimeoutMillis, 100, 60000);
        nearSyncRateLimitPerTick = clamp(nearSyncRateLimitPerTick, MIN_SYNC_RATE_LIMIT_PER_TICK, MAX_SYNC_RATE_LIMIT_PER_TICK);
        midSyncRateLimitPerTick = clamp(midSyncRateLimitPerTick, MIN_SYNC_RATE_LIMIT_PER_TICK, MAX_SYNC_RATE_LIMIT_PER_TICK);
        farSyncRateLimitPerTick = clamp(farSyncRateLimitPerTick, MIN_SYNC_RATE_LIMIT_PER_TICK, MAX_SYNC_RATE_LIMIT_PER_TICK);
        distantSyncRateLimitPerTick = clamp(distantSyncRateLimitPerTick, MIN_SYNC_RATE_LIMIT_PER_TICK, MAX_SYNC_RATE_LIMIT_PER_TICK);
        generationConcurrencyLimitPerPlayer = clamp(generationConcurrencyLimitPerPlayer, MIN_GENERATION_LIMIT, MAX_GENERATION_CONCURRENCY_LIMIT_PER_PLAYER);
        generationConcurrencyLimitGlobal = clamp(generationConcurrencyLimitGlobal, MIN_GENERATION_LIMIT, MAX_GENERATION_CONCURRENCY_LIMIT_GLOBAL);
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

    private void migrateConfigVersion() {
        if (!CURRENT_CONFIG_VERSION.equals(configVersion)) {
            applyMemorySafeDefaultMigration();
            applyGenerationThroughputDefaultMigration();
            applyBandwidthAndGlobalGenerationDefaultMigration();
            applyMemoryOptimizedDefaults();
            applyLowBandwidthDefaults();
            applyStorageThroughputDefaults();
            applyBandwidthDecoupledDefaults();
            configVersion = CURRENT_CONFIG_VERSION;
        }
        clearLegacyMigrationState();
    }

    private void clearLegacyMigrationState() {
        legacyMemorySafeDefaultsApplied = null;
        legacyGenerationThroughputDefaultsApplied = null;
        legacyBandwidthAndGlobalGenerationDefaultsApplied = null;
        legacyMemoryOptimizedDefaultsApplied = null;
        legacyLowBandwidthDefaultsApplied = null;
        legacyStorageThroughputDefaultsApplied = null;
        legacyBandwidthDecoupledDefaultsApplied = null;
    }

    private void applyMemorySafeDefaultMigration() {
        if (Boolean.TRUE.equals(legacyMemorySafeDefaultsApplied)) {
            return;
        }
        if (bandwidthEqualsBytes(OLD_DEFAULT_BYTES_PER_SECOND_LIMIT_PER_PLAYER)) {
            setTotalBandwidthBytesUnchecked(PREVIOUS_DEFAULT_BYTES_PER_SECOND_LIMIT_PER_PLAYER);
        }
        if (sendQueueLimitPerPlayer == OLD_DEFAULT_SEND_QUEUE_LIMIT_PER_PLAYER) {
            sendQueueLimitPerPlayer = 1000;
        }
        if (generationConcurrencyLimitPerPlayer == OLD_DEFAULT_GENERATION_CONCURRENCY_LIMIT_PER_PLAYER) {
            generationConcurrencyLimitPerPlayer = 4;
        }
        if (generationConcurrencyLimitGlobal == OLD_DEFAULT_GENERATION_CONCURRENCY_LIMIT_GLOBAL) {
            generationConcurrencyLimitGlobal = 8;
        }
    }

    private void applyGenerationThroughputDefaultMigration() {
        if (Boolean.TRUE.equals(legacyGenerationThroughputDefaultsApplied)) {
            return;
        }
        if (generationConcurrencyLimitPerPlayer == CONSERVATIVE_GENERATION_CONCURRENCY_LIMIT_PER_PLAYER) {
            generationConcurrencyLimitPerPlayer = 8;
        }
        if (generationConcurrencyLimitGlobal == CONSERVATIVE_GENERATION_CONCURRENCY_LIMIT_GLOBAL) {
            generationConcurrencyLimitGlobal = PREVIOUS_DEFAULT_GENERATION_CONCURRENCY_LIMIT_GLOBAL;
        }
    }

    private void applyBandwidthAndGlobalGenerationDefaultMigration() {
        if (Boolean.TRUE.equals(legacyBandwidthAndGlobalGenerationDefaultsApplied)) {
            return;
        }
        if (bandwidthEqualsBytes(PREVIOUS_DEFAULT_BYTES_PER_SECOND_LIMIT_PER_PLAYER)) {
            setTotalBandwidthBytesUnchecked(3 * BYTES_PER_MIB);
        }
        if (generationConcurrencyLimitGlobal == PREVIOUS_DEFAULT_GENERATION_CONCURRENCY_LIMIT_GLOBAL) {
            generationConcurrencyLimitGlobal = 128;
        }
    }

    private void applyMemoryOptimizedDefaults() {
        if (Boolean.TRUE.equals(legacyMemoryOptimizedDefaultsApplied)) {
            return;
        }
        // Lower the default LOD distance for memory-optimized installs.
        if (lodDistanceChunks == 256) {
            lodDistanceChunks = 128;
        }
        // Lower bandwidth pressure for memory-optimized installs.
        if (bandwidthEqualsBytes(3 * BYTES_PER_MIB)) {
            setTotalBandwidthBytesUnchecked(2 * BYTES_PER_MIB);
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
        if (generationConcurrencyLimitPerPlayer == 8) {
            generationConcurrencyLimitPerPlayer = 4;
        }
        if (generationConcurrencyLimitGlobal == 128) {
            generationConcurrencyLimitGlobal = 32;
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
    }

    private void applyLowBandwidthDefaults() {
        if (Boolean.TRUE.equals(legacyLowBandwidthDefaultsApplied)) {
            return;
        }
    }

    private void applyStorageThroughputDefaults() {
        if (Boolean.TRUE.equals(legacyStorageThroughputDefaultsApplied)) {
            return;
        }
        if (diskReaderThreads == 1) {
            diskReaderThreads = DEFAULT_DISK_READER_THREADS;
        }
    }

    private void applyBandwidthDecoupledDefaults() {
        if (Boolean.TRUE.equals(legacyBandwidthDecoupledDefaultsApplied)) {
            return;
        }
        if (bandwidthEqualsBytes(LOW_BANDWIDTH_DEFAULT_BYTES_PER_SECOND_PER_PLAYER)) {
            setTotalBandwidthBytesUnchecked(DEFAULT_TOTAL_BANDWIDTH_BYTES_PER_SECOND);
        }
        if (sendQueueLimitPerPlayer == LOW_BANDWIDTH_DEFAULT_SEND_QUEUE_LIMIT_PER_PLAYER) {
            sendQueueLimitPerPlayer = DEFAULT_SEND_QUEUE_LIMIT_PER_PLAYER;
        }
        if (sendQueueBytesLimitPerPlayer == LOW_BANDWIDTH_DEFAULT_SEND_QUEUE_BYTES_PER_PLAYER) {
            sendQueueBytesLimitPerPlayer = DEFAULT_SEND_QUEUE_BYTES_PER_PLAYER;
        }
    }

    public void normalizeAndSave() {
        validate();
        save();
    }

    public void setTotalBandwidthBytes(int bytesPerSecond) {
        totalBandwidthBytesPerSecond = bytesPerSecond;
        normalizeAndSave();
    }

    public void setTotalBandwidthMiB(int mibPerSecond) {
        setTotalBandwidthBytes(Math.multiplyExact(mibPerSecond, BYTES_PER_MIB));
    }

    public void setTotalBandwidthKbps(int kbps) {
        setTotalBandwidthKbpsUnsaved(kbps);
        normalizeAndSave();
    }

    public void setTotalBandwidthKbpsUnsaved(int kbps) {
        totalBandwidthBytesPerSecond = kbpsToBytesPerSecond(clamp(kbps, MIN_TOTAL_BANDWIDTH_KBPS, MAX_TOTAL_BANDWIDTH_KBPS));
    }

    public int totalBandwidthBytesPerSecond() {
        return totalBandwidthBytesPerSecond;
    }

    public int getTotalBandwidthKbpsRounded() {
        return bytesToKbpsCeil(totalBandwidthBytesPerSecond);
    }

    public int getSendQueueBytesMiBRounded() {
        return Math.max(1, Math.round(sendQueueBytesLimitPerPlayer / (float) BYTES_PER_MIB));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void migrateLegacyBandwidthIfNeeded() {
        if (legacyBytesPerSecondLimitPerPlayer != null) {
            totalBandwidthBytesPerSecond = upgradeLegacyDefaultBandwidth(legacyBytesPerSecondLimitPerPlayer);
            legacyBytesPerSecondLimitPerPlayer = null;
        }
        if (bandwidthLimitKbpsPerPlayer != null) {
            if (bandwidthLimitKbpsPerPlayer > 0) {
                int legacyBytesPerSecond = kbpsToBytesPerSecond(clamp(
                        bandwidthLimitKbpsPerPlayer,
                        MIN_TOTAL_BANDWIDTH_KBPS,
                        MAX_TOTAL_BANDWIDTH_KBPS));
                totalBandwidthBytesPerSecond = upgradeLegacyDefaultBandwidth(legacyBytesPerSecond);
            }
            bandwidthLimitKbpsPerPlayer = null;
        }
    }

    private static int upgradeLegacyDefaultBandwidth(int bytesPerSecond) {
        return bytesPerSecond == LEGACY_DEFAULT_BYTES_PER_SECOND_LIMIT_PER_PLAYER
                ? DEFAULT_TOTAL_BANDWIDTH_BYTES_PER_SECOND
                : bytesPerSecond;
    }

    private static int bytesToKbpsCeil(int bytesPerSecond) {
        return Math.toIntExact(((long) bytesPerSecond * 8L + 999L) / 1000L);
    }

    private boolean bandwidthEqualsBytes(int bytesPerSecond) {
        return totalBandwidthBytesPerSecond == bytesPerSecond;
    }

    private void setTotalBandwidthBytesUnchecked(int bytesPerSecond) {
        totalBandwidthBytesPerSecond = clamp(
                bytesPerSecond,
                MIN_TOTAL_BANDWIDTH_BYTES_PER_SECOND,
                MAX_TOTAL_BANDWIDTH_BYTES_PER_SECOND);
    }

    private static int kbpsToBytesPerSecond(int kbps) {
        return Math.max(1, kbps * 125);
    }
}
