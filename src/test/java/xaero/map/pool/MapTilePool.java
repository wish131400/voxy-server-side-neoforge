package xaero.map.pool;

import xaero.map.region.MapTile;

/** Tier-1 stub. */
public class MapTilePool {
    public int gets;

    public MapTile get(String dimension, int chunkX, int chunkZ) {
        this.gets++;
        return new MapTile(chunkX, chunkZ);
    }
}


