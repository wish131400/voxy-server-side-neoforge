package xaero.map.region;

/** Tier-1 stub parent (the pacing gauge lives here in the real jar too; the real
 *  class is abstract with a RegionTexture-bounded type parameter — irrelevant to
 *  the resolved members). The gauge consult is EVENT-RECORDED: production must
 *  consult it BEFORE taking any region monitor (the real method synchronizes on
 *  its own — possibly BRANCH — region, and Xaero's loader thread nests
 *  parent-then-leaf, so consulting under a leaf monitor is a lock-order
 *  inversion; review MAJOR — a real client deadlock). */
public class LeveledRegion<T> {
    public boolean allowAnotherRegionToLoad = true;

    public boolean shouldAllowAnotherRegionToLoad() {
        dev.xantha.vss.compat.XaeroStubEvents.record("pacing.shouldAllowAnotherRegionToLoad");
        return this.allowAnotherRegionToLoad;
    }
}


