package dev.xantha.vss.config;

public class VSSClientConfig extends JsonConfig {
    private static final String FILE_NAME = "vss-client-config.json";
    public static VSSClientConfig CONFIG = load(VSSClientConfig.class, FILE_NAME);

    public boolean receiveServerLods = true;
    public int lodDistanceChunks = 0;
    public int desiredBandwidthMiB = 0;
    public boolean offThreadSectionProcessing = true;

    @Override
    protected String getFileName() {
        return FILE_NAME;
    }

    @Override
    protected void validate() {
        lodDistanceChunks = clamp(lodDistanceChunks, 0, 512);
        desiredBandwidthMiB = clamp(desiredBandwidthMiB, 0, 100);
    }

    public void normalizeAndSave() {
        validate();
        save();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
