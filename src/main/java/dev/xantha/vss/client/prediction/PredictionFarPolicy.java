package dev.xantha.vss.client.prediction;

/** Whole-footprint distance test. The promotion band starts exact work before the inner boundary. */
final class PredictionFarPolicy {
    static final int PROMOTION_BLOCKS=256;
    static boolean outside(double cameraX,double cameraZ,int x,int z,int span,int exactRadius) {
        double dx=Math.max(Math.max(x-cameraX,cameraX-((double)x+span)),0);
        double dz=Math.max(Math.max(z-cameraZ,cameraZ-((double)z+span)),0);
        double radius=(double)exactRadius+PROMOTION_BLOCKS;
        return dx*dx+dz*dz>radius*radius;
    }
    private PredictionFarPolicy() { }
}
