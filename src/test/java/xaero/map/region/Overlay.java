package xaero.map.region;

import net.minecraft.world.level.block.state.BlockState;

/** Tier-1 stub. */
public class Overlay {
    public final BlockState state;
    public final byte light;
    public final boolean glowing;
    public int opacity;

    public Overlay(BlockState state, byte light, boolean glowing) {
        this.state = state;
        this.light = light;
        this.glowing = glowing;
    }

    public void increaseOpacity(int toAdd) {
        this.opacity = Math.min(15, this.opacity + Math.min(15, toAdd));
    }
}


