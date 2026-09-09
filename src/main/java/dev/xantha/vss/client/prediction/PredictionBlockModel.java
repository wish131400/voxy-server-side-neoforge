package dev.xantha.vss.client.prediction;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/** Copies the game's baked multipart geometry for thin surface blocks. */
final class PredictionBlockModel {
    private static final ThreadLocal<RandomSource> RANDOM = ThreadLocal.withInitial(() -> RandomSource.create(0));
    private static final java.util.Set<BlockState> FAILED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    record Face(float[] positions, float[] normal, int color) { }

    static List<Face> bamboo(BlockState state, BlockPos position, int biomeTint) {
        try {
            var model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
            RandomSource random = RANDOM.get();
            long seed = state.getSeed(position);
            List<BakedQuad> quads = new ArrayList<>();
            random.setSeed(seed);
            quads.addAll(model.getQuads(state, null, random));
            for (Direction direction : Direction.values()) {
                if (direction == Direction.DOWN) continue;
                random.setSeed(seed);
                quads.addAll(model.getQuads(state, direction, random));
            }
            return faces(state, position, biomeTint, quads);
        } catch (RuntimeException failure) {
            if (dev.xantha.vss.config.VSSClientConfig.CONFIG.debugLogging && FAILED.add(state))
                dev.xantha.vss.common.VSSLogger.debug("VSS bamboo model unavailable: " + state + ": " + failure);
            return List.of();
        }
    }

    static List<Face> faces(BlockState state, BlockPos position, int biomeTint, List<BakedQuad> quads) {
        var offset = state.getOffset(EmptyBlockGetter.INSTANCE, position);
        List<Face> faces = new ArrayList<>(quads.size());
        int blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getId(state.getBlock());
        for (BakedQuad quad : quads) {
            if (quad.getDirection() == Direction.DOWN) continue;
            int row = VssLodSpriteTable.registerModelFace(quad, blockId);
            // A tint index does not imply a biome leaf tint. Vanilla bamboo
            // has no BlockColors handler; its already-coloured leaves stay white-tinted.
            int tint = quad.isTinted() ? PredictionMaterialPalette.tintForBlock(blockId, biomeTint) : 0xFFFFFF;
            if (tint == 0) tint = 0xFFFFFF;
            int color = VssLodSpriteTable.modelColor(row, tint);
            int[] raw = quad.getVertices();
            int stride = raw.length / 4;
            float[] positions = new float[12];
            for (int c = 0; c < 4; c++) {
                positions[c * 3] = Float.intBitsToFloat(raw[c * stride]) + (float) offset.x;
                positions[c * 3 + 1] = Float.intBitsToFloat(raw[c * stride + 1]) + (float) offset.y;
                positions[c * 3 + 2] = Float.intBitsToFloat(raw[c * stride + 2]) + (float) offset.z;
            }
            var normal = quad.getDirection().getNormal();
            faces.add(new Face(positions, new float[]{normal.getX(), normal.getY(), normal.getZ()}, color));
        }
        return faces;
    }
}
