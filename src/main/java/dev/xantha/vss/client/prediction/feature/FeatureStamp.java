package dev.xantha.vss.client.prediction.feature;

import java.util.List;
import net.minecraft.world.level.block.state.BlockState;

/** Immutable result of a configured-feature simulation and exposed-face mesh. */
public record FeatureStamp(List<StampQuad> quads, List<StampBlock> blocks,
                           int height, GroundLight ground) {
    public static final FeatureStamp EMPTY = new FeatureStamp(List.of(), List.of(), 0, GroundLight.OPEN);
    public boolean isEmpty() { return quads.isEmpty() && blocks.isEmpty(); }
    public record StampBlock(int x, int y, int z, BlockState state) { }
    public record StampQuad(int face, int x, int y, int z, int extentA, int extentB,
                            BlockState state, int light) { }
    public record GroundLight(int minX, int minZ, int sizeX, int sizeZ, byte[] values) {
        public static final GroundLight OPEN = new GroundLight(0, 0, 0, 0, new byte[0]);
        public GroundLight {
            values = values == null ? new byte[0] : values.clone();
        }
        @Override public byte[] values() { return values.clone(); }
    }
}
