package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import com.mojang.blaze3d.platform.NativeImage;
import dev.xantha.vss.common.worldgen.LostCityPreview;
import java.util.*;
import net.minecraft.client.renderer.texture.*;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceMetadata;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PredictionCitySurfaceTest {
    @Test void detachedBridgeKeepsWaterAndSeabedAndRendersUndersideAtEveryLod() {
        int state = Block.getId(Blocks.STONE.defaultBlockState());
        var deck = new LostCityPreview.Model(new int[]{
                LostCityPreview.origin(0, 1, 0), LostCityPreview.extent(16, 0, 16, 0), state,
                LostCityPreview.origin(0, 0, 0), LostCityPreview.extent(16, 0, 16, 5), state});
        var hint = LostCityPreview.EMPTY.withOverlays(List.of(new LostCityPreview.Overlay(80, deck, deck, null, null)));
        var cities = new LostCityPreview.Tile(0, 0, 1, List.of(hint));
        assertFalse(PredictionCityGeometry.surface(hint)); assertFalse(PredictionCityGeometry.vegetationOwner(hint));
        for (int step : new int[]{1, 4, 16}) {
            int grid = 16 / step + 1;
            var raw = new ClientColumnSample[grid * grid]; Arrays.fill(raw, sample(40, false, true));
            assertSame(raw, PredictionCityGeometry.ground(raw, cities, 0, 0, step, grid, null).samples());
            var mesh = build(raw, grid, step, cities, PredictionVegetation.Tile.EMPTY, PredictionSimpleVegetation.Result.EMPTY, 0);
            assertEquals(256, topArea(mesh, 81));
            assertTrue(mesh.waterQuadCount() > 0);
            float bottom = 0;
            for (int q = 0; q < mesh.quadCount(); q++) if (mesh.y(q, 0) == 80 && mesh.normalY(q, 0) == -1)
                bottom += Math.abs(mesh.x(q, 1) - mesh.x(q, 0)) * Math.abs(mesh.z(q, 3) - mesh.z(q, 0));
            assertEquals(256, bottom, "bridge is visible from below without a filled skirt");
            Arrays.fill(raw, sample(40, true, true));
            assertEquals(0, topArea(build(raw, grid, step, cities, PredictionVegetation.Tile.EMPTY,
                    PredictionSimpleVegetation.Result.EMPTY, 0), 81), "captured geometry owns the handoff");
        }
    }

    @Test void underwaterGlassUsesAllSixWaterChecksAndRetainsAbsoluteHeight() {
        var normal = base();
        var glass = new LostCityPreview.Model(new int[]{LostCityPreview.origin(0, 5, 0),
                LostCityPreview.extent(16, 0, 16, 0), Block.getId(Blocks.GLASS.defaultBlockState())});
        var overlay = new LostCityPreview.Overlay(38, normal, normal, glass, glass);
        var cities = new LostCityPreview.Tile(0, 0, 1, List.of(LostCityPreview.EMPTY.withOverlays(List.of(overlay))));
        var raw = new ClientColumnSample[17 * 17]; Arrays.fill(raw, sample(40, false, true));
        assertTrue(PredictionCityGeometry.submerged(raw, 17, 1, 0, 0, 38));
        var geometry = new PredictionCityGeometry(cities, 0, 0, 16, 1, raw);
        assertEquals(43, geometry.overlays(0).get(0).y());
        raw[3 * 17 + 12] = sample(42, false, false);
        assertFalse(PredictionCityGeometry.submerged(raw, 17, 1, 0, 0, 38));
        geometry = new PredictionCityGeometry(cities, 0, 0, 16, 1, raw);
        assertEquals(39, geometry.overlays(0).get(0).y());
    }
    @BeforeAll static void bootstrap() {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        // The loader normally bakes these IDs before receiving city models.
        for (Block block : BuiltInRegistries.BLOCK) for (var state : block.getStateDefinition().getPossibleStates())
            if (Block.BLOCK_STATE_REGISTRY.getId(state) == -1) Block.BLOCK_STATE_REGISTRY.add(state);
    }

    private static ClientColumnSample sample(int height, boolean captured, boolean water) {
        int stone = BuiltInRegistries.BLOCK.getId(Blocks.STONE);
        return new ClientColumnSample(height, water ? height + 3 : ClientColumnSample.NO_SPAN, 0,
                stone, 0, 0, 0, 0, water ? 1 : 0, captured ? ClientColumnSample.FLAG_CAPTURED : 0, 0,
                stone, stone, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
    }
    private static ClientColumnSample[] samples(int grid) {
        var result = new ClientColumnSample[grid * grid]; Arrays.fill(result, sample(65, false, false)); return result;
    }
    private static LostCityPreview.Model base() {
        return new LostCityPreview.Model(new int[]{LostCityPreview.origin(0, 1, 0),
                LostCityPreview.extent(16, 0, 16, 0), Block.getId(Blocks.STONE.defaultBlockState())});
    }
    private static LostCityPreview.Chunk chunk(int kind, LostCityPreview.Model model) {
        int stone = Block.getId(Blocks.STONE.defaultBlockState());
        return new LostCityPreview.Chunk(kind, 64, false, stone, stone, 8, 15,
                model == null ? List.of() : List.of(new LostCityPreview.Placement(0, model)), model);
    }
    private static LostCityPreview.Tile tile(int kind, LostCityPreview.Model model) {
        return new LostCityPreview.Tile(0, 0, 1, List.of(chunk(kind, model)));
    }
    private static PredictionQuadMesh build(ClientColumnSample[] samples, int grid, int step,
                                            LostCityPreview.Tile tile, PredictionVegetation.Tile plants,
                                            PredictionSimpleVegetation.Result simple, int tint) {
        int[] colors = new int[samples.length]; Arrays.fill(colors, tint);
        return PredictionMeshBuilder.buildForRenderingWithCityPreview(samples, null, 63, 0xFF397AB2,
                step, grid, colors, null, 0, 0, plants, simple, tile, () -> true).packed();
    }
    private static float topArea(PredictionQuadMesh mesh, float height) {
        float area = 0;
        for (int q = 0; q < mesh.quadCount(); q++) if (mesh.normalY(q, 0) == 1 && mesh.y(q, 0) == height)
            area += Math.abs(mesh.x(q, 1) - mesh.x(q, 0)) * Math.abs(mesh.z(q, 3) - mesh.z(q, 0));
        return area;
    }

    @Test void publicTemplatesIndexAtEveryLodIncludingNegativeChunkBorders() {
        for (int kind : new int[]{LostCityPreview.ROAD, LostCityPreview.PARK}) {
            var cities = new LostCityPreview.Tile(-1, -1, 1, List.of(chunk(kind, base())));
            for (int step : new int[]{1, 4, 16}) {
                int axis = 16 / step, area = 0;
                var geometry = new PredictionCityGeometry(cities, -16, -16, axis, step);
                for (int cell = 0; cell < axis * axis; cell++) for (var face : geometry.faces(cell)) {
                    assertEquals(65, face.y()); assertEquals(0, face.direction());
                    assertTrue(face.x() >= 0 && face.z() >= 0);
                    area += face.dx() * face.dz();
                }
                assertEquals(256, area, "public ground has one owner at step " + step);
            }
        }
    }

    @Test void templateGroundReplacesGenericTopWithoutChangingRawSamples() {
        for (int step : new int[]{1, 4, 16}) {
            int grid = 16 / step + 1;
            var raw = samples(grid); var before = raw.clone();
            var mesh = build(raw, grid, step, tile(LostCityPreview.PARK, base()),
                    PredictionVegetation.Tile.EMPTY, PredictionSimpleVegetation.Result.EMPTY, 0);
            assertEquals(256, topArea(mesh, 65), "no duplicate coplanar city ground");
            assertEquals(0, mesh.waterQuadCount()); assertArrayEquals(before, raw);
        }
    }

    @Test void naturalCityVoxelsAreRemovedButCapturedAndOutsideVoxelsRemain() {
        int grid = 9;
        var raw = samples(grid); raw[grid + 1] = sample(80, true, false);
        var cities = new LostCityPreview.Tile(-1, -1, 2,
                List.of(chunk(LostCityPreview.PARK, base()), LostCityPreview.EMPTY, LostCityPreview.EMPTY, LostCityPreview.EMPTY));
        var inside = new BlockPos(-8, 65, -8); var captured = new BlockPos(-12, 81, -12);
        var outside = new BlockPos(4, 81, 4);
        var blocks = Map.of(inside, Blocks.OAK_LOG.defaultBlockState(), captured, Blocks.OAK_LEAVES.defaultBlockState(),
                outside, Blocks.OAK_LEAVES.defaultBlockState());
        var original = PredictionVegetation.Tile.of(blocks, -16, -16, 32, 4, 1);
        var filtered = PredictionCityGeometry.vegetation(original, cities, raw, -16, -16, 4, grid);
        assertEquals(Set.of(captured, outside), filtered.blocks().keySet());
        assertEquals(3, original.blocks().size());
        var ground = PredictionCityGeometry.ground(raw, cities, -16, -16, 4, grid, null);
        assertEquals(80, ground.samples()[grid + 1].surfaceY());
        assertEquals(65, ground.samples()[0].surfaceY());
        assertSame(raw[5 * grid + 5], ground.samples()[5 * grid + 5]);
        assertSame(original, PredictionCityGeometry.vegetation(original, null, raw, -16, -16, 4, grid));
    }

    @Test void rejectedCityPlantsDoNotCutTheGroundOrOccludeWater() {
        int grid = 17;
        var raw = samples(grid);
        var blocks = Map.of(new BlockPos(4, 64, 4), Blocks.OAK_LOG.defaultBlockState(),
                new BlockPos(5, 65, 4), Blocks.STONE.defaultBlockState());
        var plants = PredictionVegetation.Tile.of(blocks, 0, 0, 16, 1, 1);
        var city = tile(LostCityPreview.PARK, base());
        var empty = build(raw, grid, 1, city, PredictionVegetation.Tile.EMPTY, PredictionSimpleVegetation.Result.EMPTY, 0);
        var filtered = build(raw, grid, 1, city, plants, PredictionSimpleVegetation.Result.EMPTY, 0);
        assertEquals(topArea(empty, 65), topArea(filtered, 65));
        assertEquals(empty.quadCount(), filtered.quadCount());
        Arrays.fill(raw, sample(64, false, true));
        // A non-flattening building marker preserves the underlying fluid;
        // filtering must still happen before calculating its visible area.
        city = tile(LostCityPreview.BUILDING, null);
        empty = build(raw, grid, 1, city, PredictionVegetation.Tile.EMPTY, PredictionSimpleVegetation.Result.EMPTY, 0);
        filtered = build(raw, grid, 1, city, plants, PredictionSimpleVegetation.Result.EMPTY, 0);
        assertTrue(empty.waterQuadCount() > 0);
        assertEquals(empty.waterQuadCount(), filtered.waterQuadCount());
        for (int q = 0; q < empty.waterQuadCount(); q++) for (int c = 0; c < 4; c++) {
            assertEquals(empty.waterX(q, c), filtered.waterX(q, c));
            assertEquals(empty.waterZ(q, c), filtered.waterZ(q, c));
        }
    }

    @Test void simpleTreesAreSuppressedOnlyWhereTheCityOwnsVegetation() {
        var form = new PredictionSimpleVegetation.Form(0, 0, 0, 65, 12,
                new PredictionSimpleVegetation.Species(Blocks.OAK_LOG.defaultBlockState(), Blocks.OAK_LEAVES.defaultBlockState()), 0);
        var simple = new PredictionSimpleVegetation.Result(List.of(form), null, 77);
        var raw = samples(17); var city = tile(LostCityPreview.PARK, base());
        var baseline = build(raw, 17, 1, city, PredictionVegetation.Tile.EMPTY, PredictionSimpleVegetation.Result.EMPTY, 0);
        assertEquals(baseline.quadCount(), build(raw, 17, 1, city, PredictionVegetation.Tile.EMPTY, simple, 0).quadCount());
        assertTrue(build(raw, 17, 1, null, PredictionVegetation.Tile.EMPTY, simple, 0).quadCount()
                > build(raw, 17, 1, null, PredictionVegetation.Tile.EMPTY, PredictionSimpleVegetation.Result.EMPTY, 0).quadCount());
    }

    @Test void templateHedgeKeepsFoliageTintAndItsOwnFaceGroup() throws Exception {
        var tints = field(PredictionMaterialPalette.class, "atlasColors");
        Object previousTints = tints.get(null);
        Object previousReady = field(VssLodSpriteTable.class, "runtimeReady").get(null);
        try {
        // A headless bootstrap does not load the atlas or register BlockColors.
        // Use the real leaf pixels and the same tint snapshot as the game.
        int[] colors = new int[BuiltInRegistries.BLOCK.size()];
        colors[BuiltInRegistries.BLOCK.getId(Blocks.OAK_LEAVES)] = 0xFF55BB22;
        tints.set(null, colors);
        field(VssLodSpriteTable.class, "runtimeReady").set(null, true);
        installLeafTexture();
        int leaves = Block.getId(Blocks.OAK_LEAVES.defaultBlockState());
        var side = new LostCityPreview.Model(new int[]{LostCityPreview.origin(0, 1, 0),
                LostCityPreview.extent(16, 3, 0, 1), leaves});
        var city = tile(LostCityPreview.PARK, side);
        var raw = samples(17);
        var red = build(raw, 17, 1, city, PredictionVegetation.Tile.EMPTY, PredictionSimpleVegetation.Result.EMPTY, 0xFFCC2222);
        var green = build(raw, 17, 1, city, PredictionVegetation.Tile.EMPTY, PredictionSimpleVegetation.Result.EMPTY, 0xFF22CC22);
        assertTrue(red.quadCount() > 0); assertEquals(red.quadCount(), green.quadCount());
        var field = PredictionQuadMesh.class.getDeclaredField("groups"); field.setAccessible(true);
        byte[] groups = (byte[]) field.get(red);
        int sides = 0;
        for (int q = 0; q < red.quadCount(); q++) if (red.normalZ(q, 0) == -1 && red.y(q, 0) >= 65) {
            sides++; assertEquals(VssLodFaceGroup.NORTH, groups[q] & 7);
            assertFalse(red.terrainWall(q), "hedge is not a terrain seam wall");
            assertNotEquals(red.color(q, 0) & 0xFFFFFF, green.color(q, 0) & 0xFFFFFF);
        }
        assertTrue(sides > 0);
        } finally {
            VssLodSpriteTable.close();
            tints.set(null, previousTints);
            field(VssLodSpriteTable.class, "runtimeReady").set(null, previousReady);
        }
    }

    @SuppressWarnings("unchecked")
    private void installLeafTexture() throws Exception {
        try (var input = getClass().getResourceAsStream("/assets/minecraft/textures/block/oak_leaves.png")) {
            assertNotNull(input);
            try (var contents = new SpriteContents(ResourceLocation.withDefaultNamespace("block/oak_leaves"),
                    new FrameSize(16, 16), NativeImage.read(input), ResourceMetadata.EMPTY)) {
                int row = VssLodSpriteTable.registerSprite(new Sprite(contents));
                ((Map<Long, Integer>) field(VssLodSpriteTable.class, "INDEX_BY_STATE_FACE").get(null))
                        .put(((long) Block.getId(Blocks.OAK_LEAVES.defaultBlockState()) << 3) | 1, row);
            }
        }
    }
    private static java.lang.reflect.Field field(Class<?> type, String name) throws Exception {
        var result = type.getDeclaredField(name); result.setAccessible(true); return result;
    }
    private static class Sprite extends TextureAtlasSprite {
        Sprite(SpriteContents contents) { super(TextureAtlas.LOCATION_BLOCKS, contents, 256, 256, 0, 0); }
    }
}
