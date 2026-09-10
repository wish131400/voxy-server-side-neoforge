package dev.xantha.vss.config;

import dev.xantha.vss.common.VSSConstants;
import com.google.gson.annotations.SerializedName;
import java.util.LinkedHashMap;
import java.util.Map;

public class VSSClientConfig extends JsonConfig {
    private static final String FILE_NAME = "vss-client-config.json";
    public static final String CURRENT_CONFIG_VERSION = "v0.2.15";
    public static final int MAX_LOD_DISTANCE_CHUNKS = VSSConstants.MAX_CLIENT_LOD_DISTANCE_CHUNKS;
    public static final int MIN_PREDICTION_DISTANCE_BLOCKS = 1_024;
    public static final int MAX_PREDICTION_DISTANCE_BLOCKS = 65_536;
    public static final int MAX_DESIRED_BANDWIDTH_KBPS = 100_000;
    private static final Map<String, String> CONFIG_HELP = createConfigHelp();
    public static VSSClientConfig CONFIG = load(VSSClientConfig.class, FILE_NAME);

    public String configVersion;
    public boolean receiveServerLods = true;
    public int lodDistanceChunks = 0;
    public int desiredBandwidthKbps = 0;
    @Deprecated
    private Integer desiredBandwidthMiB;
    public boolean offThreadSectionProcessing = true;
    public boolean enableXaeroMapBridge = true;
    /** Generate deterministic far terrain predictions from the server worldgen profile. */
    @SerializedName("enablePrediction")
    public boolean enablePrediction = true;
    /** Prediction horizon, in blocks. */
    public int predictionDistanceBlocks = 8_192;
    public int predictionFineDistanceBlocks = 1_536;
    public int predictionBackgroundWorkers = 2;
    public int predictionRefinementWorkers = 0;
    /** Prediction detail profile: low, normal, high or extreme. */
    public String predictionDetail = "normal";
    /** Include deterministic feature stamps in predicted tiles. */
    public boolean predictionTrees = true;
    /** Surface decoration radius; distant decoration is generated only by the spyglass. */
    public int predictionSurfaceDistanceBlocks = 768;
    public boolean predictionStructures = true;
    /** Reuse client prediction samples between launches. */
    public boolean rememberTerrain = true;
    /** Supersample terrain samples at the finest two LOD levels. */
    public boolean predictionSupersample = false;
    /** Render the independent LOD fog pass. */
    public boolean predictionFog = true;
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
        help.put("lodDistanceChunks", "客户端请求 LOD 的半径，单位区块；默认 0；范围 0-"
                + MAX_LOD_DISTANCE_CHUNKS + "，0 表示自动取服务端上限与 Voxy 设置中的较小值。");
        help.put("desiredBandwidthKbps", "客户端期望的 LOD 下载带宽上限，单位 Kbps；默认 0；范围 0-"
                + MAX_DESIRED_BANDWIDTH_KBPS + "（最高 100 Mbps），0 表示不额外限速，仍受服务端上限控制。");
        help.put("offThreadSectionProcessing", "是否在后台线程处理收到的 LOD 区块以减少主线程卡顿；默认 true。");
        help.put("enableXaeroMapBridge", "是否将服务端远景写入 Xaero 世界地图；默认 true。可用 /vssclient xaero disable 临时关闭。");
        help.put("enablePrediction", "是否根据服务端同步的种子和世界生成元数据在远处生成预测地形；默认 true。");
        help.put("predictionDistanceBlocks", "预测远景范围，单位方块；默认 8192；范围 "
                + MIN_PREDICTION_DISTANCE_BLOCKS + "-" + MAX_PREDICTION_DISTANCE_BLOCKS
                + "。它独立于 VSS 的 lodDistanceChunks。");
        help.put("predictionDetail", "预测地形细节等级 low/normal/high/extreme；影响屏幕像素阈值。");
        help.put("predictionFineDistanceBlocks", "普通精细地形距离，单位方块；默认 1536，范围 256-4096，独立于预测远景距离；高空按实际距离降低精度，望远镜可突破此距离。");
        help.put("predictionBackgroundWorkers", "视野外地形的后台任务槽；默认 2，范围 1-4。近处和当前视野优先，已加载的远景仍保留。");
        help.put("predictionRefinementWorkers", "中等覆盖完成后的普通精修任务槽，包含视野内地形和植被；默认 0 自动使用一半逻辑线程，也可指定 1-32，实际不超过一半逻辑线程。缺失覆盖、望远镜目标和脏列修复优先处理。");
        help.put("predictionTrees", "是否生成近处及望远镜目标区域的原版树木、草等地表植被；默认 true。");
        help.put("predictionStructures", "是否生成近处及望远镜区域的原版地表结构；默认 true。");
        help.put("predictionSurfaceDistanceBlocks", "在已知近处地形外额外细化地表的范围，单位方块；默认 768，范围 128-2048；随真实地形覆盖边界向外延伸，远处只由望远镜触发。");
        help.put("rememberTerrain", "是否在客户端压缩保存预测地形、植被及地表建筑，重访时读取并允许释放闲置网格；默认 true。");
        help.put("predictionSupersample", "是否在最近两个 LOD 层级使用超采样；默认 false。");
        help.put("predictionFog", "是否使用独立的远景雾；默认 true。");
        help.put("debugLogging", "是否输出客户端 VSS 调试日志；默认 false。");
        return help;
    }

    @Override
    protected void validate() {
        if (!CURRENT_CONFIG_VERSION.equals(configVersion)) {
            enableXaeroMapBridge = true;
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
        predictionSurfaceDistanceBlocks = clamp(predictionSurfaceDistanceBlocks, 128, 2048);
        predictionFineDistanceBlocks = clamp(predictionFineDistanceBlocks, 256, 4096);
        predictionBackgroundWorkers = clamp(predictionBackgroundWorkers, 1, 4);
        predictionRefinementWorkers = clamp(predictionRefinementWorkers, 0, 32);
        if (predictionDetail == null) predictionDetail = "normal";
        predictionDetail = switch (predictionDetail.toLowerCase(java.util.Locale.ROOT)) {
            case "low", "normal", "high", "extreme" -> predictionDetail.toLowerCase(java.util.Locale.ROOT);
            default -> "normal";
        };
    }

    public void normalizeAndSave() {
        validate();
        save();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
