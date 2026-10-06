package dev.xantha.vss.client.prediction;

/** Render-thread display state; changing it never regenerates or uploads geometry. */
final class PredictionAquaticLod {
    private int tier;
    int tier() { return tier; }

    boolean update(double distance, int fullDistance, boolean scoped) {
        int previous = tier;
        double radius = Math.max(256, fullDistance);
        double margin = Math.max(32, radius / 16);
        if (scoped || !Double.isFinite(distance)) tier = 0;
        else {
            while (tier < 3 && distance > Math.scalb(radius, tier) + margin) tier++;
            while (tier > 0 && distance < Math.scalb(radius, tier - 1) - margin) tier--;
        }
        return tier != previous;
    }

    static double distance(double cameraX, double cameraZ, int baseX, int baseZ, int span) {
        double dx = Math.max(0, Math.max(baseX - cameraX, cameraX - ((double) baseX + span)));
        double dz = Math.max(0, Math.max(baseZ - cameraZ, cameraZ - ((double) baseZ + span)));
        return Math.hypot(dx, dz);
    }
}
