package xaero.map.region;

/** Tier-1 stub. */
public class MapTile {
    public final int chunkX;
    public final int chunkZ;
    public final MapBlock[][] blocks = new MapBlock[16][16];
    public int worldInterpretationVersion;
    public int writtenCaveStart;
    public int writtenCaveDepth;
    public boolean writtenOnce;
    public boolean loaded;

    public MapTile(int chunkX, int chunkZ) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
    }

    public void setBlock(int x, int z, MapBlock block) { this.blocks[x][z] = block; }

    public void setWorldInterpretationVersion(int version) {
        this.worldInterpretationVersion = version;
        dev.xantha.vss.compat.XaeroStubEvents.record("tile.setWorldInterpretationVersion " + version);
    }

    public void setWrittenCave(int caveStart, int caveDepth) {
        this.writtenCaveStart = caveStart;
        this.writtenCaveDepth = caveDepth;
        dev.xantha.vss.compat.XaeroStubEvents.record("tile.setWrittenCave");
    }

    public void setWrittenOnce(boolean written) {
        this.writtenOnce = written;
        dev.xantha.vss.compat.XaeroStubEvents.record("tile.setWrittenOnce " + written);
    }

    public void setLoaded(boolean loaded) {
        this.loaded = loaded;
        dev.xantha.vss.compat.XaeroStubEvents.record("tile.setLoaded " + loaded);
    }

    public boolean isLoaded() { return this.loaded; }

    public boolean wasWrittenOnce() { return this.writtenOnce; }
}

