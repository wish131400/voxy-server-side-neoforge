package xaero.map.world;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Tier-1 stub. */
public class MapWorld {
    public boolean cacheOnlyMode;
    public ResourceKey<Level> currentDimensionId;

    public boolean isCacheOnlyMode() { return this.cacheOnlyMode; }

    public ResourceKey<Level> getCurrentDimensionId() { return this.currentDimensionId; }
}


