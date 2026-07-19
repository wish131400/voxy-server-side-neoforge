package dev.xantha.vss.config;

import dev.xantha.vss.common.VSSConstants;
import java.util.LinkedHashMap;
import java.util.Map;

public class VSSClientConfig extends JsonConfig {
    private static final String FILE_NAME = "vss-client-config.json";
    public static final String CURRENT_CONFIG_VERSION = "v0.2.9";
    public static final int MAX_LOD_DISTANCE_CHUNKS = VSSConstants.MAX_CLIENT_LOD_DISTANCE_CHUNKS;
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
        help.put("debugLogging", "是否输出客户端 VSS 调试日志；默认 false。");
        return help;
    }

    @Override
    protected void validate() {
        configVersion = CURRENT_CONFIG_VERSION;
        lodDistanceChunks = clamp(lodDistanceChunks, 0, MAX_LOD_DISTANCE_CHUNKS);
        if (desiredBandwidthMiB != null) {
            if (desiredBandwidthMiB > 0) {
                desiredBandwidthKbps = Math.multiplyExact(desiredBandwidthMiB, 1024 * 1024 * 8 / 1000);
            }
            desiredBandwidthMiB = null;
        }
        desiredBandwidthKbps = clamp(desiredBandwidthKbps, 0, MAX_DESIRED_BANDWIDTH_KBPS);
    }

    public void normalizeAndSave() {
        validate();
        save();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
