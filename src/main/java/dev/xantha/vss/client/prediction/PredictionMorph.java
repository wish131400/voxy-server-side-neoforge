package dev.xantha.vss.client.prediction;

/** Shared X/Z displacement: tops and walls can never disagree at one vertex. */
final class PredictionMorph {
    static final long DURATION_NANOS = 350_000_000L;
    private PredictionMorph() { }

    static float[] field(PredictionTileManager.PredictionTile child, PredictionTileManager.PredictionTile parent) {
        int axis = child.cellAxis() + 1;
        if (parent == null || parent.spacingBlocks() <= child.spacingBlocks()
                || parent.heights().length != (parent.cellAxis()+1)*(parent.cellAxis()+1)
                || child.heights().length != axis*axis || child.spacingBlocks() <= 2) return null;
        // Fluid banks must stay at the same positions as their unmorphed water.
        for (var s : child.samples()) if (s == null || !s.hasSurface() || s.hasFluid() || s.captured()) return null;
        float[] delta = new float[axis * axis];
        boolean changed = false;
        int span = child.cellAxis() * child.spacingBlocks();
        for (int z = 1; z < axis-1; z++) for (int x = 1; x < axis-1; x++) {
            int localX = x * child.spacingBlocks(), localZ = z * child.spacingBlocks();
            int px = child.baseBlockX() + localX - parent.baseBlockX();
            int pz = child.baseBlockZ() + localZ - parent.baseBlockZ();
            double gx = px / (double)parent.spacingBlocks(), gz = pz / (double)parent.spacingBlocks();
            int ix = Math.min(parent.cellAxis()-1, Math.max(0,(int)Math.floor(gx)));
            int iz = Math.min(parent.cellAxis()-1, Math.max(0,(int)Math.floor(gz)));
            if (gx < 0 || gz < 0 || gx > parent.cellAxis() || gz > parent.cellAxis()) return null;
            int pa=parent.cellAxis()+1;
            double tx=gx-ix,tz=gz-iz;
            int[] h=parent.heights();
            double top=(1-tz)*((1-tx)*h[iz*pa+ix]+tx*h[iz*pa+ix+1])
                    +tz*((1-tx)*h[(iz+1)*pa+ix]+tx*h[(iz+1)*pa+ix+1]);
            double edge=Math.min(Math.min(localX,span-localX),Math.min(localZ,span-localZ));
            float d=(float)(Math.max(-8,Math.min(8,top-child.heights()[z*axis+x]))
                    * Math.min(1,edge/(child.spacingBlocks()*2.0)));
            delta[z*axis+x]=d;
            changed |= d != 0;
        }
        return changed ? delta : null;
    }

    static float amount(long ageNanos) {
        float t=Math.max(0,Math.min(1,ageNanos/(float)DURATION_NANOS));
        return 1-t*t*(3-2*t);
    }
}
