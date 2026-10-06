package dev.xantha.vss.config;

/** Stable display density, independent of world generation and resource presets. */
public enum PredictionVegetationDensity {
    LOW(25, 1),
    MEDIUM(50, 2),
    HIGH(100, 0);

    public static final int SETTINGS_MASK = 3 << 10;
    private final int percentage, cacheIndex;

    PredictionVegetationDensity(int percentage, int cacheIndex) {
        this.percentage = percentage;
        this.cacheIndex = cacheIndex;
    }

    public int percentage() { return percentage; }
    public String configName() { return name().toLowerCase(java.util.Locale.ROOT); }
    public int settingsBits() { return cacheIndex << 10; }

    public static PredictionVegetationDensity current() {
        return fromName(VSSClientConfig.CONFIG.predictionVegetationDensity);
    }

    public static PredictionVegetationDensity fromName(String name) {
        if (name == null) return MEDIUM;
        return switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "low" -> LOW;
            case "high" -> HIGH;
            default -> MEDIUM;
        };
    }

    public static PredictionVegetationDensity fromSettings(int settings) {
        return switch ((settings & SETTINGS_MASK) >>> 10) {
            case 1 -> LOW;
            case 2 -> MEDIUM;
            default -> HIGH;
        };
    }

    /** Nested subsets; a column's choice does not depend on height, camera or tile identity. */
    public boolean keep(int x, int z) {
        if (this == HIGH) return true;
        long hash = ((long) x << 32 ^ (z & 0xffffffffL)) ^ 0x632be59bd9b4e019L;
        hash = (hash ^ (hash >>> 30)) * 0xbf58476d1ce4e5b9L;
        hash = (hash ^ (hash >>> 27)) * 0x94d049bb133111ebL;
        hash ^= hash >>> 31;
        return (hash & 3) < percentage / 25;
    }
}
