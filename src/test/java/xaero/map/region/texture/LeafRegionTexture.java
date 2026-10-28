package xaero.map.region.texture;

/** Tier-1 stub. */
public class LeafRegionTexture {
    private final xaero.map.region.MapTileChunk tileChunk;
    public boolean downloadFromPBO;
    public boolean cachePrepared;
    public boolean toUpload;

    public LeafRegionTexture(xaero.map.region.MapTileChunk tileChunk) {
        this.tileChunk = tileChunk;
    }

    public boolean shouldDownloadFromPBO() { return this.downloadFromPBO; }
    public boolean shouldUpload() { return this.toUpload; }
    public boolean shouldIncludeInCache() { return this.tileChunk.hasHadTerrain(); }
    public boolean isCachePrepared() { return this.cachePrepared; }
    public void setCachePrepared(boolean prepared) { this.cachePrepared = prepared; }
}


