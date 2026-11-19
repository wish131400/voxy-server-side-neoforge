package dev.xantha.vss.config;

import dev.xantha.vss.common.VSSConstants;
import com.google.gson.annotations.SerializedName;
import java.util.LinkedHashMap;
import java.util.Map;

public class VSSClientConfig extends JsonConfig {
    private static final String FILE_NAME = "vss-client-config.json";
    public static final String CURRENT_CONFIG_VERSION = PredictionConfigDefaults.VERSION;
    public static final int MAX_LOD_DISTANCE_CHUNKS = VSSConstants.MAX_CLIENT_LOD_DISTANCE_CHUNKS;
    public static final int MIN_PREDICTION_DISTANCE_BLOCKS = 1_024;
    public static final int MAX_PREDICTION_DISTANCE_BLOCKS = MAX_LOD_DISTANCE_CHUNKS * 16;
    public static final int MAX_DESIRED_BANDWIDTH_KBPS = 100_000;
    private static final Map<String, String> CONFIG_HELP = createConfigHelp();
    public static VSSClientConfig CONFIG = load(VSSClientConfig.class, FILE_NAME);

    public String configVersion;
    public boolean receiveServerLods = true;
    /** Convert locally loaded chunks into Voxy LOD, independently of server synchronization. */
    public volatile boolean enableLocalChunkIngestion = true;
    public int lodDistanceChunks = 0;
    public int desiredBandwidthKbps = 0;
    @Deprecated
    private Integer desiredBandwidthMiB;
    public boolean offThreadSectionProcessing = true;
    public boolean enableXaeroMapBridge = true;
    /** Prediction resource preset: low, medium, high or custom. */
    public volatile String performanceTier = "medium";
    /** Explicit prediction workers for the custom preset, bounded by available logical processors. */
    public volatile int predictionWorkerThreads = 0;
    /** CPU-set policy for VSS prediction workers; never changes game-thread affinity. */
    public volatile String predictionCoreMode = "system";
    /** Physical fastest-class cores excluded from reserve/performance worker sets, including SMT siblings. */
    public volatile int predictionReservedCores = 2;
    /** Generate deterministic far terrain predictions from the server worldgen profile. */
    @SerializedName("enablePrediction")
    public boolean enablePrediction = true;
    /** Pause generation/refinement/cache loading while keeping resident prediction LOD visible. */
    public volatile boolean enablePredictionLoading = true;
    /** Prediction horizon, in blocks. */
    public int predictionDistanceBlocks = PredictionConfigDefaults.DISTANCE_BLOCKS;
    /** Client prediction placement only; real chunks and terrain generation are unaffected. */
    public volatile boolean predictionDecorations = true;
    /** Horizontal placement radius in chunks; zero follows the prediction horizon. */
    public volatile int predictionDecorationDistanceChunks = 0;
    /** Biome-template decoration outside the exact radius. */
    public boolean predictionFarDecorations = PredictionConfigDefaults.FAR_DECORATIONS;
    public int predictionExactDistanceBlocks = PredictionConfigDefaults.EXACT_DISTANCE_BLOCKS;
    public int predictionBackgroundWorkers = 2;
    public int predictionRefinementWorkers = 0;
    /** Prediction detail profile: low, normal, high or extreme. */
    public String predictionDetail = "normal";
    /** Allow the spyglass to request distant terrain and surface refinement. */
    public boolean predictionSpyglassLoading = true;
    /** Reuse client prediction samples between launches. */
    public boolean rememberTerrain = true;
    /** Supersample terrain samples at the finest two LOD levels. */
    public boolean predictionSupersample = false;
    /** Apply distant-terrain fog with the active Voxy pipeline. */
    public boolean predictionFog = true;
    /** True singleplayer pause only; zero disables the pause cap. */
    public int predictionPausedFps = 30;
    public boolean debugLogging = false;

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
        help.put("receiveServerLods", "是否接收服务端发送的 Voxy LOD；默认 true。");
        help.put("enableLocalChunkIngestion", "是否将本地加载区块转换为 Voxy LOD；默认 true。关闭不影响接收服务端 LOD，也不会删除已缓存的远景。");
        help.put("lodDistanceChunks", "客户端请求 LOD 的半径，单位区块；默认 0；范围 0-"
                + MAX_LOD_DISTANCE_CHUNKS + "，0 表示使用服务端上限；有效距离超过 Voxy 设置时扩展渲染范围。");
        help.put("desiredBandwidthKbps", "客户端期望的 LOD 下载带宽上限，单位 Kbps；默认 0；范围 0-"
                + MAX_DESIRED_BANDWIDTH_KBPS + "（最高 100 Mbps），0 表示不额外限速，仍受服务端上限控制。");
        help.put("offThreadSectionProcessing", "是否在后台线程处理收到的 LOD 区块以减少主线程卡顿；默认 true。");
        help.put("enableXaeroMapBridge", "是否将服务端真实远景写入 Xaero 世界地图；默认 true。预测转地图暂时停用。可用 /vssclient xaero disable 临时关闭真实数据写入。");
        help.put("predictionCoreMode", "预测核心分配 system/reserve/performance/efficiency；默认 system。Windows 10/11 按系统核心性能等级识别；reserve 避开指定数量的最快物理核心，performance 使用其余大核，efficiency 仅使用小核。只影响 VSS 预测计算和缓存恢复线程；其他平台或无法识别时保持系统调度。");
        help.put("predictionReservedCores", "为游戏保留的物理大核数，包含对应的超线程；默认 2，0 不保留。用于 reserve/performance 模式，按可用物理核心数自动限制，至少保留一个可运行预测的核心。设置在工作线程下一项任务开始时生效。");
        help.put("predictionWorkerThreads", "自定义预测工作线程数；范围 1 到当前运行环境可用的最大逻辑线程数。仅 performanceTier=custom 时使用；0 使用中档默认数量。设置保存后生效。");
        help.put("performanceTier", "预测性能档位 low/medium/high/custom；默认 medium。low 使用四分之一逻辑线程（至少 2，不超过本机可用数量），medium 使用一半，high 使用逻辑线程数减 2。custom 使用 predictionWorkerThreads。保存后更新工作线程额度，与本地真实区块生成共享并发。画质参数独立。");
        help.put("enablePrediction", "预测远景总开关，控制预测 LOD 的显示与生成；Beta 功能，默认 true（开启）。只想暂停加载并保留已有远景，请关闭 enablePredictionLoading。");
        help.put("enablePredictionLoading", "是否继续加载预测远景；默认 true。关闭后暂停新区域生成、细化和成品缓存读取，已加载的预测 LOD 继续显示；重新开启后继续加载。仅影响客户端预测，不限制 Voxy 真实区块接收或 /vss chunky。");
        help.put("predictionDistanceBlocks", "预测远景半径，界面单位区块；默认 " + (PredictionConfigDefaults.DISTANCE_BLOCKS / 16) + "；范围 "
                + (MIN_PREDICTION_DISTANCE_BLOCKS / 16) + "-" + MAX_LOD_DISTANCE_CHUNKS
                + "。为兼容旧配置，此字段保存值为区块数乘以 16；它独立于 VSS 的 lodDistanceChunks。");
        help.put("predictionDecorations", "预测植被与装饰结构总开关；默认 true。关闭后停止对应放置、模板采样和装饰缓存读取，并隐藏已有预测装饰；地形与真实区块不受影响。重新开启可复用已有缓存。");
        help.put("predictionDecorationDistanceChunks", "预测植被与装饰结构的水平加载半径，单位区块；0 跟随预测远景距离，范围 0-" + MAX_LOD_DISTANCE_CHUNKS + "。超出范围不执行装饰放置；不扩大总预测距离。");
        help.put("predictionFarDecorations", "远区装饰模板与粗网格环境；默认 " + PredictionConfigDefaults.FAR_DECORATIONS + "。仅作用于真实计算半径外，允许改变装饰位置和形态，不扩大总预测距离。");
        help.put("predictionExactDistanceBlocks", "保留现有装饰计算的半径，默认 " + (PredictionConfigDefaults.EXACT_DISTANCE_BLOCKS / 16) + " 区块；外加 16 区块提前细化区。此字段保存值为区块数乘以 16；仅在开启远区装饰时生效。");
        help.put("predictionDetail", "预测地形采样细节等级 low/normal/high/extreme，影响视角细化需求；最终构网、LOD 和绘制使用 Voxy。");
        help.put("predictionBackgroundWorkers", "后台任务分类上限；默认 2，范围 1-4，与预测任务共用总 CPU 预算。可见区域按屏幕细节需求优先，纯视野外已有覆盖的区域停止新升级；画面边缘保留缓冲，最低粗覆盖、必要交接和已加载远景继续保留。");
        help.put("predictionRefinementWorkers", "中等覆盖完成后的普通精修分类上限，包含视野内地形和植被；默认 0 表示跟随性能档位（low、medium 跟随档位线程数，high 为一半逻辑线程），也可指定 1-32。实际并发仍受共享 CPU 预算限制；缺失覆盖、望远镜目标和脏列修复优先处理。");
        help.put("predictionSpyglassLoading", "是否允许望远镜额外加载目标区域的远处精细地形、植被和建筑；默认 true。关闭后仍可使用望远镜缩放。");
        help.put("rememberTerrain", "是否在客户端压缩保存预测地形、植被及地表建筑，重访时读取并允许释放闲置采样数据；默认 true。");
        help.put("predictionSupersample", "是否在最近两个 LOD 层级使用超采样；默认 false。");
        help.put("predictionFog", "是否在 Voxy 管线中应用预测远景雾；默认 true。");
        help.put("predictionPausedFps", "预测开启时，单人游戏真正暂停后的帧率上限；默认 30，0 关闭；保留更低的原有上限，恢复游戏立即解除。多人游戏菜单不受影响。");
        help.put("debugLogging", "是否输出客户端 VSS 调试日志；默认 false。");
        return help;
    }

    public static String normalizeCoreMode(String name) {
        String value = name == null ? "system" : name.toLowerCase(java.util.Locale.ROOT);
        return switch (value) { case "reserve", "performance", "efficiency" -> value; default -> "system"; };
    }

    @Override
    protected void validate() {
        if (!CURRENT_CONFIG_VERSION.equals(configVersion)) {
            // Keep choices already migrated before the exact-radius default changed.
            boolean farDefaultsApplied = "v0.2.17".equals(configVersion) || "v0.2.18".equals(configVersion);
            if (!"v0.2.16".equals(configVersion) && !farDefaultsApplied) enableXaeroMapBridge = true;
            if (PredictionConfigDefaults.FAR_DECORATIONS) {
                if (!farDefaultsApplied) {
                    predictionFarDecorations = true;
                    if (predictionDistanceBlocks == 4_096) {
                        predictionDistanceBlocks = PredictionConfigDefaults.DISTANCE_BLOCKS;
                    }
                }
                // Migrate the old 256-chunk default once; preserve custom radii.
                if (!"v0.2.18".equals(configVersion) && predictionExactDistanceBlocks == 4_096) {
                    predictionExactDistanceBlocks = PredictionConfigDefaults.EXACT_DISTANCE_BLOCKS;
                }
            }
        }
        configVersion = CURRENT_CONFIG_VERSION;
        lodDistanceChunks = clamp(lodDistanceChunks, 0, MAX_LOD_DISTANCE_CHUNKS);
        if (desiredBandwidthMiB != null) {
            if (desiredBandwidthMiB > 0) {
                desiredBandwidthKbps = Math.multiplyExact(desiredBandwidthMiB, 1024 * 1024 * 8 / 1000);
            }
            desiredBandwidthMiB = null;
        }
        desiredBandwidthKbps = clamp(desiredBandwidthKbps, 0, MAX_DESIRED_BANDWIDTH_KBPS);
        predictionDistanceBlocks = clamp(predictionDistanceBlocks,
                MIN_PREDICTION_DISTANCE_BLOCKS, MAX_PREDICTION_DISTANCE_BLOCKS);
        predictionExactDistanceBlocks = clamp(predictionExactDistanceBlocks, 256, MAX_PREDICTION_DISTANCE_BLOCKS);
        predictionDecorationDistanceChunks = clamp(predictionDecorationDistanceChunks, 0, MAX_LOD_DISTANCE_CHUNKS);
        predictionPausedFps = clamp(predictionPausedFps, 0, 260);
        predictionBackgroundWorkers = clamp(predictionBackgroundWorkers, 1, 4);
        if (performanceTier == null) performanceTier = "medium";
        performanceTier = performanceTier.toLowerCase(java.util.Locale.ROOT);
        if (!"low".equals(performanceTier) && !"high".equals(performanceTier) && !"custom".equals(performanceTier)) {
            performanceTier = "medium";
        }
        predictionCoreMode = normalizeCoreMode(predictionCoreMode);
        predictionReservedCores = clamp(predictionReservedCores, 0, 1024);
        predictionWorkerThreads = clamp(predictionWorkerThreads, 0, Math.max(1, Runtime.getRuntime().availableProcessors()));
        if ("custom".equals(performanceTier) && predictionWorkerThreads == 0)
            predictionWorkerThreads = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() / 2));
        predictionRefinementWorkers = clamp(predictionRefinementWorkers, 0, 32);
        if (predictionDetail == null) predictionDetail = "normal";
        predictionDetail = switch (predictionDetail.toLowerCase(java.util.Locale.ROOT)) {
            case "low", "normal", "high", "extreme" -> predictionDetail.toLowerCase(java.util.Locale.ROOT);
            default -> "normal";
        };
    }

    public boolean isPredictionLoadingEnabled() {
        return enablePrediction && enablePredictionLoading;
    }

    /** Settings use chunks; keep the existing persisted representation for compatibility. */
    public int getPredictionDistanceChunks() {
        return predictionDistanceBlocks / 16;
    }

    public void setPredictionDistanceChunks(int chunks) {
        predictionDistanceBlocks = chunks * 16;
    }

    public void normalizeAndSave() {
        validate();
        save();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
