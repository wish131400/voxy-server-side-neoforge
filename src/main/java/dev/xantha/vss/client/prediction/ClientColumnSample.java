package dev.xantha.vss.client.prediction;

/**
 * VSS-owned equivalent of the column sample.  Keeping the complete
 * column metadata (rather than only a height) lets the mesh builder represent
 * water, underground bands and floating spans entirely in-process.
 */
public record ClientColumnSample(
        int surfaceY,
        int fluidY,
        int biomeIndex,
        int topBlockIndex,
        int structureIndex,
        int treeKind,
        int treeDensity,
        int treeHeight,
        int fluid,
        int flags,
        int groundFeatureKind,
        int underBlockIndex,
        int deepBlockIndex,
        int surfaceBottom,
        int lowerTop,
        int lowerBottom,
        int spanFloor,
        PredictionColumnVolume volume) {

    public ClientColumnSample(int surfaceY, int fluidY, int biomeIndex, int topBlockIndex,
            int structureIndex, int treeKind, int treeDensity, int treeHeight, int fluid, int flags,
            int groundFeatureKind, int underBlockIndex, int deepBlockIndex, int surfaceBottom,
            int lowerTop, int lowerBottom, int spanFloor) {
        this(surfaceY, fluidY, biomeIndex, topBlockIndex, structureIndex, treeKind, treeDensity,
                treeHeight, fluid, flags, groundFeatureKind, underBlockIndex, deepBlockIndex,
                surfaceBottom, lowerTop, lowerBottom, spanFloor, null);
    }

    public static final int NO_SPAN = Short.MIN_VALUE;
    public static final int NO_BLOCK = 0xFFFF;
    public static final int FLAG_SNOW = 1;
    public static final int FLAG_ICE = 1 << 1;
    public static final int FLAG_TREE_HERE = 1 << 2;
    public static final int FLAG_CAPTURED = 1 << 30;
    public static final int FLAG_NO_SURFACE = 1 << 29;
    /** Underground occupancy was deliberately not sampled; never infer a filled volume from it. */
    public static final int FLAG_SURFACE_ONLY = 1 << 28;
    /** Preview geometry must be resampled before exact terrain or decoration. */
    public static final int FLAG_APPROXIMATE = 1 << 27;
    /** Interpolated-density display surface, distinct from the raw first preview. */
    public static final int FLAG_DISPLAY = 1 << 26;
    /** City-plan heights are render summaries, never inputs to noise refinement. */
    public static final int FLAG_CITY_GROUND = 1 << 20;
    public static final int PREFILLED = 0xFF;

    public boolean hasFluid() {
        return fluid != 0 && fluidY > surfaceY;
    }

    public boolean captured() { return (flags & FLAG_CAPTURED) != 0; }

    public boolean hasSurface() { return (flags & FLAG_NO_SURFACE) == 0; }

    public boolean surfaceOnly() { return (flags & FLAG_SURFACE_ONLY) != 0; }
    public boolean approximate() { return (flags & FLAG_APPROXIMATE) != 0; }
    boolean cityGround() {
        // Older summaries did not carry a provenance bit. Their dry-fluid sentinel
        // distinguishes them from native dry columns (fluidY == surfaceY).
        return (flags & FLAG_CITY_GROUND) != 0 || flags == FLAG_SURFACE_ONLY
                && fluid == 0 && fluidY == NO_SPAN && surfaceBottom == NO_SPAN
                && lowerTop == NO_SPAN && lowerBottom == NO_SPAN && spanFloor == NO_SPAN
                && treeKind == 0 && treeDensity == 0 && treeHeight == 0
                && groundFeatureKind == 0 && underBlockIndex == deepBlockIndex && volume == null;
    }
    boolean reusableFor(boolean preview) {
        return !cityGround() && (preview || captured() || (!approximate() && (flags & FLAG_DISPLAY) == 0));
    }
    boolean reusableForDisplay() { return !cityGround() && (!approximate() || captured() || (flags & FLAG_DISPLAY) != 0); }

    ClientColumnSample withoutVegetationHints() {
        return new ClientColumnSample(surfaceY, fluidY, biomeIndex, topBlockIndex,
                structureIndex, 0, 0, 0, fluid, flags & ~FLAG_TREE_HERE, 0,
                underBlockIndex, deepBlockIndex, surfaceBottom, lowerTop, lowerBottom, spanFloor, volume);
    }

    public boolean hasLowerSpan() {
        return lowerTop != NO_SPAN && lowerBottom != NO_SPAN;
    }

    public boolean floating() {
        return surfaceBottom != NO_SPAN;
    }

    public boolean snow() {
        return (flags & FLAG_SNOW) != 0;
    }

    public boolean ice() {
        return (flags & FLAG_ICE) != 0;
    }
}
