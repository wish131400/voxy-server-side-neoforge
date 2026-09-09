package dev.xantha.vss.client.prediction;

/**
 * Conservative tile-space depth bound used by the VSS terrain pass,
 * derived from the sampled columns and attached to every tile so the
 * renderer can reject impossible vertical ranges without another world
 * query.
 */
public record PredictionDepthBound(int minY, int maxY) {
    public static PredictionDepthBound from(PredictionTileManager.PredictionTile tile) {
        return fromSamples(tile.samples());
    }

    public static PredictionDepthBound fromSamples(ClientColumnSample[] samples) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (ClientColumnSample sample : samples) {
            if (sample == null) continue;
            min = Math.min(min, sample.surfaceY());
            max = Math.max(max, sample.surfaceY());
            if (sample.hasFluid()) max = Math.max(max, sample.fluidY());
            if (sample.treeHeight() > 0) max = Math.max(max, sample.surfaceY() + sample.treeHeight());
        }
        if (min == Integer.MAX_VALUE) min = -64;
        if (max == Integer.MIN_VALUE) max = 320;
        return new PredictionDepthBound(min, max);
    }

    public boolean contains(double y) {
        return y >= minY && y <= maxY;
    }

    public int height() {
        return Math.max(1, maxY - minY);
    }
}
