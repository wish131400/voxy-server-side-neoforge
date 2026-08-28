package xaero.map.region;

/** Tier-1 stub — records the writeChunk lifecycle calls the compat must mirror,
 *  and ENFORCES the region-monitor discipline: the state accessors throw unless
 *  the caller holds the monitors the native writer holds (review MAJOR: without
 *  this, dropping a synchronized block ships green). In the real jar
 *  setAllCachePrepared and the load-pacing members live on LeveledRegion. */
public class MapRegion extends LeveledRegion<Object> {
    public final Object writerThreadPauseSync = new Object();
    public boolean writingPaused;
    public byte loadState = 2;
    public boolean resting = true;
    public boolean canRequestReload = true;
    public int visits;
    public Boolean beingWritten; // null until first set — pins "set true, never cleared"
    public final MapTileChunk[][] chunks = new MapTileChunk[8][8];

    private void requireRegionMonitor(String site) {
        if (!Thread.holdsLock(this)) {
            throw new IllegalStateException(site + " outside the region monitor — the native"
                    + " writeChunk discipline requires synchronized(region)");
        }
    }

    public boolean isWritingPaused() {
        if (!Thread.holdsLock(this.writerThreadPauseSync)) {
            throw new IllegalStateException("region.isWritingPaused outside"
                    + " writerThreadPauseSync — the save-race exclusion's monitor");
        }
        return this.writingPaused;
    }

    public byte getLoadState() {
        requireRegionMonitor("getLoadState");
        return this.loadState;
    }

    public void setLoadState(byte state) {
        requireRegionMonitor("setLoadState");
        this.loadState = state;
    }

    public boolean isResting() {
        requireRegionMonitor("isResting");
        return this.resting && this.loadState != 3 && this.loadState != 1;
    }

    public void registerVisit() {
        requireRegionMonitor("registerVisit");
        this.visits++;
    }

    public void setBeingWritten(boolean beingWritten) {
        requireRegionMonitor("setBeingWritten");
        this.beingWritten = beingWritten;
        dev.xantha.vss.compat.XaeroStubEvents.record("region.setBeingWritten " + beingWritten);
    }

    public boolean canRequestReload_unsynced() {
        requireRegionMonitor("canRequestReload_unsynced");
        return this.canRequestReload && (this.loadState == 0 || this.loadState == 4
                || (this.loadState == 2 && Boolean.TRUE.equals(this.beingWritten)));
    }

    public void setAllCachePrepared(boolean prepared) {
        dev.xantha.vss.compat.XaeroStubEvents.record("region.setAllCachePrepared " + prepared);
    }

    public MapTileChunk getChunk(int x, int z) { return this.chunks[x][z]; }

    public void setChunk(int x, int z, MapTileChunk chunk) { this.chunks[x][z] = chunk; }
}

