package dev.xantha.vss.client.prediction;

/** Immutable Voxy voxel payload with CPU sampling provenance; owns no geometry or GPU buffers. */
public final class PredictionVoxelData {
    final PredictionVoxySource.Tile voxyTile;
    PredictionParentLod.Proof parentLod;
    int retainedSampleObjects = -1;
    long retainedVolumeBytes;
    private PredictionVoxelData(PredictionVoxySource.Tile tile) { voxyTile = java.util.Objects.requireNonNull(tile); }
    static PredictionVoxelData forVoxy(PredictionVoxySource.Tile tile) { return new PredictionVoxelData(tile); }
    public int cellAxis() { return voxyTile.axis(); }
    long retainedHeapBytes() { return 64L + voxyTile.bytes(); }
}
