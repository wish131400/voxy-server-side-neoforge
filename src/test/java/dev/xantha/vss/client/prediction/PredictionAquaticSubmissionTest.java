package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.*;
import java.util.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

class PredictionAquaticSubmissionTest {
    @Test void landGrassSharesStableWholeStalkPrefixes() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        Field field = VssLodSpriteTable.class.getDeclaredField("INDEX_BY_SPRITE"); field.setAccessible(true);
        @SuppressWarnings("unchecked") var sprites = (Map<ResourceLocation, Integer>) field.get(null);
        var key = ResourceLocation.tryParse("minecraft:block/tall_grass_bottom");
        Integer previous = sprites.put(key, 253);
        try {
            var mesh = plants();
            assertTrue(mesh.hasDisplayLod());
            assertEquals(64, mesh.drawRanges(false, VssLodFaceGroup.ALL, 0).quads);
            assertEquals(16, mesh.drawRanges(false, VssLodFaceGroup.ALL, 1).quads);
            assertEquals(4, mesh.drawRanges(false, VssLodFaceGroup.ALL, 2).quads);
            assertEquals(1, mesh.drawRanges(false, VssLodFaceGroup.ALL, 3).quads);
        } finally { if (previous == null) sprites.remove(key); else sprites.put(key, previous); }
    }

    @Test void stationaryReplacementScopeAndNearViewSelectActualSubmissionRanges() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        Field spritesField = VssLodSpriteTable.class.getDeclaredField("INDEX_BY_SPRITE");
        spritesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var sprites = (Map<ResourceLocation, Integer>) spritesField.get(null);
        var key = ResourceLocation.tryParse("minecraft:block/kelp");
        Integer previous = sprites.put(key, 253);
        Field scope = PredictionRenderer.class.getDeclaredField("selectionScoping"); scope.setAccessible(true);
        boolean previousScope = scope.getBoolean(null);
        try {
            scope.setBoolean(null, false);
            var tile = PredictionLodSeamsTest.tile(0, 0, 1, 64);
            var gpu = new PredictionGpuTile(tile.key());
            Field payload = PredictionGpuTile.class.getDeclaredField("packed"); payload.setAccessible(true);
            var first = plants(); payload.set(gpu, first);
            var draw = new PredictionRenderer.Draw(tile, gpu, new boolean[4096], false,
                    VssLodFaceGroup.ALL, 0, new AABB(0, 0, 0, 64, 100, 64));
            var draws = List.of(draw);
            Class<?> type = Class.forName(PredictionRenderer.class.getName() + "$PreparedPass");
            Constructor<?> constructor = type.getDeclaredConstructor(boolean.class); constructor.setAccessible(true);
            Object pass = constructor.newInstance(false);
            Method prepare = type.getDeclaredMethod("prepare", List.class, long.class, Vec3.class); prepare.setAccessible(true);
            Method ranges = type.getDeclaredMethod("ranges", PredictionRenderer.Draw.class); ranges.setAccessible(true);
            Vec3 far = new Vec3(100000, 80, 0);
            prepare.invoke(pass, draws, 1L, far);
            assertEquals(1, ((PredictionDrawRanges) ranges.invoke(pass, draw)).quads);
            Object cached = ranges.invoke(pass, draw);
            prepare.invoke(pass, draws, 1L, far);
            assertSame(cached, ranges.invoke(pass, draw));
            // Upload completion can replace the payload without changing the draw list or camera.
            var replacement = plants(); payload.set(gpu, replacement);
            prepare.invoke(pass, draws, 2L, far);
            assertEquals(3, replacement.aquaticLod.tier());
            assertEquals(1, ((PredictionDrawRanges) ranges.invoke(pass, draw)).quads);
            scope.setBoolean(null, true);
            prepare.invoke(pass, draws, 2L, far);
            assertEquals(64, ((PredictionDrawRanges) ranges.invoke(pass, draw)).quads);
            scope.setBoolean(null, false);
            prepare.invoke(pass, draws, 2L, far);
            assertEquals(1, ((PredictionDrawRanges) ranges.invoke(pass, draw)).quads);
            prepare.invoke(pass, draws, 2L, new Vec3(32, 80, 32));
            assertEquals(64, ((PredictionDrawRanges) ranges.invoke(pass, draw)).quads);
        } finally {
            scope.setBoolean(null, previousScope);
            if (previous == null) sprites.remove(key); else sprites.put(key, previous);
        }
    }

    private static PredictionPackedMesh plants() {
        int[] records = new int[64 * 12];
        for (int i = 0; i < 64; i++) PredictionSpatialOrderTest.quad(records, i, i % 8, i / 8,
                50, 253 | PredictionPackedMesh.FLAG_UNSHADED | PredictionPackedMesh.FLAG_CUTOUT);
        return PredictionPackedMesh.terrainRecords(records, 64);
    }
}
