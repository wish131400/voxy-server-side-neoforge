package dev.xantha.vss.client.prediction;
// Only for a separate offline JVM; never packaged and never an Alex's Caves terrain oracle.
final class JavaSurfaceConditions {
    static boolean verifyFailure, evaluationFailure, allowSurface;
    static int calls,lastX,lastY,lastZ,lastOffset;static long lastSeed;
    static void verifyAlexCaves() {if(verifyFailure)throw new IllegalStateException("fixture verification failure");}
    static boolean alexCavesBiome(long seed,int x,int y,int z,int offset) {
        if(!allowSurface)throw new AssertionError("Density-only replay must not evaluate surface conditions");
        if(evaluationFailure)throw new IllegalStateException("fixture evaluation failure");
        calls++;lastSeed=seed;lastX=x;lastY=y;lastZ=z;lastOffset=offset;
        return seed!=0 && y<=50 && offset==3;
    }
}
