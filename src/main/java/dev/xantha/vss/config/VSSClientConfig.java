package dev.xantha.vss.config;

public class VSSClientConfig extends JsonConfig {
    private static final String FILE_NAME = "vss-client-config.json";
    public static final int MAX_LOD_DISTANCE_CHUNKS = 512;
    public static VSSClientConfig CONFIG = load(VSSClientConfig.class, FILE_NAME);

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
    protected void validate() {
        lodDistanceChunks = clamp(lodDistanceChunks, 0, MAX_LOD_DISTANCE_CHUNKS);
        if (desiredBandwidthMiB != null) {
            if (desiredBandwidthMiB > 0) {
                desiredBandwidthKbps = Math.multiplyExact(desiredBandwidthMiB, 1024 * 1024 * 8 / 1000);
            }
            desiredBandwidthMiB = null;
        }
        desiredBandwidthKbps = clamp(desiredBandwidthKbps, 0, 100000);
    }

    public void normalizeAndSave() {
        validate();
        save();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
