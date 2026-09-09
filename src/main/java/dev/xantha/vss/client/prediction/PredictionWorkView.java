package dev.xantha.vss.client.prediction;

import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;

/** Conservative scheduling cone; never controls render visibility or residency. */
record PredictionWorkView(double x, double y, double z, double dx, double dy, double dz,
                          double cosHalf, double sinHalf) {
    static PredictionWorkView of(double x, double y, double z, double dx, double dy, double dz,
                                 double verticalFov, double aspect) {
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!(length > 0) || !Double.isFinite(length)) return null;
        double half = Math.atan(Math.tan(Math.toRadians(Math.max(1, Math.min(120, verticalFov))) / 2)
                * Math.sqrt(1 + aspect * aspect)) + Math.toRadians(15);
        half = Math.min(Math.toRadians(85), half);
        return new PredictionWorkView(x, y, z, dx / length, dy / length, dz / length,
                Math.cos(half), Math.sin(half));
    }

    boolean foreground(PredictionTileKey key, VssLodLayout layout, VssLodFocus focus,
                       double minY, double maxY) {
        if (PredictionWorkOrder.scoped(key, layout, focus)) return true;
        double vertical = Math.max(0, Math.max(minY - y, y - maxY));
        double near = PredictionWorkOrder.distanceSquared(key, layout, x, z) + vertical * vertical;
        double fine = PredictionDetailBands.fineRadius(layout.maxDistanceBlocks());
        if (near <= fine * fine) return true;
        double span = layout.tileBlocks(key.lod());
        double px = (key.tileX() + .5) * span - x;
        double py = (minY + maxY) * .5 - y;
        double pz = (key.tileZ() + .5) * span - z;
        double distanceSquared = px * px + py * py + pz * pz;
        double radiusSquared = span * span * .5 + (maxY - minY) * (maxY - minY) * .25;
        if (distanceSquared <= radiusSquared) return true;
        return px * dx + py * dy + pz * dz >= cosHalf * Math.sqrt(distanceSquared - radiusSquared)
                - sinHalf * Math.sqrt(radiusSquared);
    }
}
