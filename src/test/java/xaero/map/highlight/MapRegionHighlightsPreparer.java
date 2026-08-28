package xaero.map.highlight;

import xaero.map.region.MapRegion;

/** Tier-1 stub. */
public class MapRegionHighlightsPreparer {
    public int prepares;

    public void prepare(MapRegion region, int localX, int localZ, boolean flag) {
        this.prepares++;
        dev.xantha.vss.compat.XaeroStubEvents.record("highlights.prepare");
    }
}


