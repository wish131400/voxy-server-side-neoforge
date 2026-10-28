package dev.xantha.vss.client.prediction;

/** Immutable geometry with CPU provenance. Publication may adopt an equivalent canonical payload. */
public final class PredictionVoxelData {
    volatile PredictionVoxySource.Tile voxyTile;
    PredictionParentLod.Proof parentLod;
    // Decoration-only publications share the terrain provenance without retaining old sample arrays.
    long terrainRevision = -1;
    volatile byte[] cacheContentIdentity;
    int retainedSampleObjects = -1;
    long retainedVolumeBytes;
    private PredictionVoxelData(PredictionVoxySource.Tile tile) { voxyTile = java.util.Objects.requireNonNull(tile); }
    static PredictionVoxelData forVoxy(PredictionVoxySource.Tile tile) { return new PredictionVoxelData(tile); }
    public int cellAxis() { return voxyTile.axis(); }
    long retainedHeapBytes() { return 64L + voxyTile.bytes(); }
}
