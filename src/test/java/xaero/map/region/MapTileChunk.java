package xaero.map.region;

import xaero.map.MapProcessor;
import xaero.map.biome.BlockTintProvider;
import xaero.map.cache.BlockStateShortShapeCache;
import xaero.map.region.texture.LeafRegionTexture;

/** Tier-1 stub — records the commit-sequence calls in order. */
public class MapTileChunk {
    public final MapRegion region;
    public final int tileChunkX;
    public final int tileChunkZ;
    public int loadState;
    public boolean changed;
    public boolean toUpdateBuffers;
    public boolean hasHadTerrain;
    public boolean includeInSaveResult = true;
    public final LeafRegionTexture leafTexture = new LeafRegionTexture();
    public final MapTile[][] tiles = new MapTile[4][4];
    public boolean setTileThrows; // arms the throw-latch tests
    public int bufferUpdates;
    public boolean updateBuffersThrows;

    public MapTileChunk(MapRegion region, int tileChunkX, int tileChunkZ) {
        this.region = region;
        this.tileChunkX = tileChunkX;
        this.tileChunkZ = tileChunkZ;
        dev.xantha.vss.compat.XaeroStubEvents.record("tileChunk.new " + tileChunkX + "," + tileChunkZ);
    }

    public int getLoadState() { return this.loadState; }

    public void setLoadState(byte state) {
        this.loadState = state;
        dev.xantha.vss.compat.XaeroStubEvents.record("tileChunk.setLoadState " + state);
    }

    public boolean wasChanged() { return this.changed; }

    public void setChanged(boolean changed) {
        this.changed = changed;
        dev.xantha.vss.compat.XaeroStubEvents.record("tileChunk.setChanged " + changed);
    }

    public boolean getToUpdateBuffers() { return this.toUpdateBuffers; }

    public void setToUpdateBuffers(boolean toUpdate) {
        this.toUpdateBuffers = toUpdate;
        dev.xantha.vss.compat.XaeroStubEvents.record("tileChunk.setToUpdateBuffers " + toUpdate);
    }

    public void updateBuffers(MapProcessor processor, BlockTintProvider tint,
                              OverlayManager overlayManager, boolean debug,
                              BlockStateShortShapeCache cache, MapUpdateFastConfig config) {
        if (!Thread.holdsLock(this.region.writerThreadPauseSync)
                || !Thread.holdsLock(this.region) || !this.region.resting) {
            throw new IllegalStateException("updateBuffers outside safe writer/resting gates");
        }
        if (this.updateBuffersThrows) throw new IllegalStateException("armed updateBuffers throw");
        this.bufferUpdates++;
        dev.xantha.vss.compat.XaeroStubEvents.record(
                "tileChunk.updateBuffers " + this.tileChunkX + "," + this.tileChunkZ);
    }

    public boolean includeInSave() { return this.includeInSaveResult; }

    public void setHasHadTerrain() {
        this.hasHadTerrain = true;
        dev.xantha.vss.compat.XaeroStubEvents.record("tileChunk.setHasHadTerrain");
    }

    public LeafRegionTexture getLeafTexture() { return this.leafTexture; }

    public MapTile getTile(int x, int z) { return this.tiles[x][z]; }

    public void setTile(int x, int z, MapTile tile, BlockStateShortShapeCache cache,
                        MapProcessor processor) {
        if (!Thread.holdsLock(this.region.writerThreadPauseSync)) {
            throw new IllegalStateException("setTile outside writerThreadPauseSync — the"
                    + " native writer commits tiles under the region's writer-pause monitor");
        }
        if (this.setTileThrows) throw new IllegalStateException("armed setTile throw");
        this.tiles[x][z] = tile;
        dev.xantha.vss.compat.XaeroStubEvents.record("tileChunk.setTile " + x + "," + z);
    }
}

