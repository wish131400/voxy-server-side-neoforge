package dev.xantha.vss.common.worldgen;

/** JVM properties of the worldgen profile cache, read once. */
public final class WorldgenProfileCacheConfig {
    public static final String PREFIX = "vss.worldgenProfileCache.";
    /** Upper bound of the summed payload bytes the profile holder keeps; the eldest payload goes first. */
    public static final long MAX_BYTES = Math.max(0L, number("maxBytes", 64L << 20));
    /** One payload per level key plus the all-levels key: the packet limit of 64 dimensions. */
    public static final int MAX_PAYLOADS = 65;
    /** False keeps the holder bound and switches the registry section cache off. */
    public static final boolean SECTIONS = !"false".equalsIgnoreCase(System.getProperty(PREFIX + "sections"));
    /** Estimated heap bytes the section cache may hold; a section that would exceed it is encoded on every build. */
    public static final long MAX_SECTION_BYTES = Math.max(0L, number("maxSectionBytes", 128L << 20));
    /** An additional bound in JSON values, only when set (0: none). */
    public static final long MAX_NODES = Math.max(0L, number("maxNodes", 0L));
    /** Two INFO lines per build with short hashes of what was built; switch off with =false. */
    public static final boolean DIAGNOSE = !"false".equalsIgnoreCase(System.getProperty(PREFIX + "diagnose"));
    /** Diagnostic: every build is repeated without the cache and the two payloads are compared. Doubles the build time. */
    public static final boolean VERIFY = Boolean.getBoolean(PREFIX + "verify");

    private WorldgenProfileCacheConfig() { }

    private static long number(String name, long fallback) {
        String text = System.getProperty(PREFIX + name);
        if (text == null) return fallback;
        try { return Long.parseLong(text.trim()); }
        catch (NumberFormatException invalid) { return fallback; }
    }

    public static String describe() {
        return "holder bound " + MAX_BYTES + " B, registry sections " + (SECTIONS ? "cached up to about " + MAX_SECTION_BYTES + " B"
                + (MAX_NODES > 0L ? " and " + MAX_NODES + " JSON values" : "") : "not cached")
                + (VERIFY ? ", every build verified against an uncached build" : "");
    }
}
