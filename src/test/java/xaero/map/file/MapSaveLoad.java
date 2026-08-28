package xaero.map.file;

import xaero.map.region.LeveledRegion;
import xaero.map.region.MapRegion;

/** Tier-1 stub — records the load-request dance. */
public class MapSaveLoad {
    public boolean regionDetectionComplete = true;
    public LeveledRegion<?> nextToLoadByViewing;
    public final java.util.List<MapRegion> loadRequests = new java.util.ArrayList<>();

    public boolean isRegionDetectionComplete() { return this.regionDetectionComplete; }

    public void requestLoad(MapRegion region, String reason) {
        this.loadRequests.add(region);
        region.canRequestReload = false;
        dev.xantha.vss.compat.XaeroStubEvents.record("saveLoad.requestLoad " + reason);
    }

    public LeveledRegion<?> getNextToLoadByViewing() { return this.nextToLoadByViewing; }

    public void setNextToLoadByViewing(LeveledRegion<?> region) {
        this.nextToLoadByViewing = region;
        dev.xantha.vss.compat.XaeroStubEvents.record("saveLoad.setNextToLoadByViewing");
    }
}

