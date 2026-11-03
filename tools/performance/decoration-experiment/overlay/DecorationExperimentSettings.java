package dev.xantha.vss.client.prediction;

/** Included only in the isolated experiment source overlay. Single generation thread. */
final class DecorationExperimentSettings {
    static boolean nativeBiomes, scan;
    static final boolean verifyBiomes = Boolean.getBoolean("vss.experiment.verifyBiomes");
    static int page = 1;
    static long surfaceCalls, surfacePoints, biomeQueries, biomeCalls, biomeCacheHits, biomeVerified, scans;
    static void select(String mode) {
        nativeBiomes = mode.equals("biome") || mode.equals("combined") || mode.equals("combined4");
        scan = mode.equals("scan") || mode.equals("combined") || mode.equals("combined4") || mode.equals("page4_scan");
        page = mode.equals("page4") || mode.equals("combined4") || mode.equals("page4_scan") ? 4
                : mode.equals("page2") || mode.equals("combined") ? 2 : 1;
    }
    static void reset() {
        surfaceCalls=surfacePoints=biomeQueries=biomeCalls=biomeCacheHits=biomeVerified=scans=0;
    }
}
