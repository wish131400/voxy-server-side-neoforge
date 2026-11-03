package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RustDecorationHeightTest {
    @BeforeAll static void bootstrap() {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        assertTrue(RustTerrainSampler.available());
    }

    private static RustTerrainSampler sampler(boolean interior) throws Exception {
        var doc = interior ? PredictionNetherDecorationTest.nativeDocument("minecraft:nether_wastes")
                : LithostitchedNativeTest.document();
        doc.add("possible_biomes", new JsonArray());
        if (!interior) {
            // Include every test material in the native state table; the first
            // rule keeps the fixture terrain stone while later bands are synthetic.
            var rules = new JsonArray();
            for (var block : new net.minecraft.world.level.block.Block[]{Blocks.STONE, Blocks.AIR,
                    Blocks.OAK_LEAVES, Blocks.GLASS, Blocks.FERN, Blocks.WATER}) {
                var rule = new com.google.gson.JsonObject();
                rule.addProperty("type", "minecraft:block");
                rule.add("result_state", net.minecraft.world.level.block.state.BlockState.CODEC
                        .encodeStart(com.mojang.serialization.JsonOps.INSTANCE, block.defaultBlockState()).result().orElseThrow());
                rules.add(rule);
            }
            var sequence = new com.google.gson.JsonObject();
            sequence.addProperty("type", "minecraft:sequence");
            sequence.add("sequence", rules);
            doc.getAsJsonObject("settings").add("surface_rule", sequence);
        }
        var profile = new DimensionProfile(ResourceLocation.withDefaultNamespace(interior ? "the_nether" : "overworld"),
                interior ? 0 : -64, interior ? 256 : 384, "noise", interior ? "minecraft:nether" : "minecraft:overworld", 1L);
        return new RustTerrainSampler(RustWorldgenBackend.create(1, 0, doc.toString()), profile,
                new ClientTerrainSampler(1, profile));
    }

    private static Object field(PredictionDecorationLevel level, String name) throws Exception {
        var field = PredictionDecorationLevel.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(level);
    }

    private static int[] row(RustTerrainSampler sampler, int floor, int fluid) {
        int stone = sampler.stateId(Blocks.STONE.defaultBlockState());
        assertTrue(stone >= 0);
        return new int[]{floor, fluid, 1, 0, stone, stone, stone, 0, 0, 0};
    }

    private static PredictionDecorationLevel retainedLevel(RustTerrainSampler sampler) throws Exception {
        var level = new PredictionDecorationLevel(sampler, sampler, RegistryAccess.EMPTY, -1, -1);
        var slotMethod = PredictionDecorationLevel.class.getDeclaredMethod("columnSlot", int.class, int.class);
        slotMethod.setAccessible(true);
        int slot = (int) slotMethod.invoke(level, -16, -16);
        for (String name : new String[]{"nativeColumns", "displayColumns"}) {
            var records = new int[80 * 80][];
            records[slot] = row(sampler, name.equals("nativeColumns") ? 70 : 80, 75);
            var field = PredictionDecorationLevel.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(level, records);
        }
        return level;
    }

    @Test void materialBandsMatchBlockByBlockForAllHeightmapsAndBuildLimits() throws Exception {
        try (var sampler = sampler(false)) {
            var materials = new net.minecraft.world.level.block.Block[]{
                    Blocks.AIR, Blocks.STONE, Blocks.OAK_LEAVES, Blocks.GLASS, Blocks.FERN, Blocks.WATER};
            int[] ids = new int[materials.length];
            for (int i = 0; i < ids.length; i++) {
                ids[i] = sampler.stateId(materials[i].defaultBlockState());
                assertTrue(ids[i] >= 0, materials[i].toString());
            }
            int[][] patterns = {{0, 0, 0}, {1, 1, 1}, {2, 0, 1}, {4, 2, 1},
                    {0, 0, 3}, {5, 5, 1}, {0, 2, 0}, {3, 4, 2}};
            int[] heights = {-68, -64, -63, -61, 0, 63, 319, 320, 324};
            for (int floor : heights) for (int fluid : heights) for (int kind = 0; kind < 3; kind++)
            for (int flags : new int[]{0, 2, 1 << 29, (1 << 29) | 2}) for (int[] pattern : patterns) {
                int[] row = {floor, fluid, kind, flags, ids[pattern[0]], ids[pattern[1]], ids[pattern[2]], 0, 0, 0};
                for (var type : Heightmap.Types.values()) {
                    int expected = -64;
                    for (int y = Math.min(Math.max(floor, fluid) - 1, 319); y >= -64; y--) {
                        if (type.isOpaque().test(sampler.proxyBlock(row, y))) { expected = y + 1; break; }
                    }
                    assertEquals(expected, sampler.proxyHeight(row, type),
                            () -> type + " " + java.util.Arrays.toString(row));
                }
            }
        }
    }

    private static void assertHeightOracle(PredictionDecorationLevel level) {
        int x = -16, z = -16;
        var sample = level.column(x, z);
        int top = Math.max(sample.surfaceY(), sample.fluidY());
        for (var p : level.placed().keySet()) if (p.getX() == x && p.getZ() == z) top = Math.max(top, p.getY() + 1);
        var pos = new BlockPos.MutableBlockPos(x, 0, z);
        for (var type : Heightmap.Types.values()) {
            int expected = level.getMinBuildHeight();
            for (int y = Math.min(top - 1, level.getMaxBuildHeight() - 1); y >= level.getMinBuildHeight(); y--) {
                if (type.isOpaque().test(level.getBlockState(pos.setY(y)))) { expected = y + 1; break; }
            }
            assertEquals(expected, level.getHeight(type, x, z), type.toString());
        }
    }

    @Test void heightOnlyReadsDoNotBuildSamplesOrVersionArraysAndViewsStaySeparate() throws Exception {
        try (var sampler = sampler(false)) {
            var level = retainedLevel(sampler);
            assertEquals(75, level.getHeight(Heightmap.Types.WORLD_SURFACE, -16, -16));
            assertEquals(70, level.getHeight(Heightmap.Types.OCEAN_FLOOR, -16, -16));
            assertNull(field(level, "columns"));
            assertNull(field(level, "columnQueryVersions"));
            level.useDisplayTerrain(true);
            assertEquals(80, level.getHeight(Heightmap.Types.WORLD_SURFACE, -16, -16));
            assertNull(field(level, "displaySamples"));
            level.useDisplayTerrain(false);
            assertEquals(75, level.getHeight(Heightmap.Types.WORLD_SURFACE, -16, -16));
            assertHeightOracle(level);
            assertThrows(PredictionDecorationLevel.LocalPlacementFailure.class,
                    () -> level.getHeight(Heightmap.Types.WORLD_SURFACE, 32, -16));
        }
    }

    @Test void writesBeforeQueriesRestorationRollbackAndCutsKeepTheirHeights() throws Exception {
        try (var sampler = sampler(false)) {
            var level = retainedLevel(sampler);
            // Disk restoration does not set rendering policy flags. It still changes height.
            level.restoreSurface(Map.of(new BlockPos(-16, 110, -16), Blocks.OAK_LEAVES.defaultBlockState()));
            assertHeightOracle(level);
            level.useDisplayTerrain(true);
            assertHeightOracle(level);
            level.beginFeature();
            level.setBlock(new BlockPos(-16, 130, -16), Blocks.STONE.defaultBlockState(), 0, 0);
            assertHeightOracle(level);
            level.endFeature(false);
            assertHeightOracle(level);
            level.beginFeature();
            level.setBlock(new BlockPos(-16, 110, -16), Blocks.AIR.defaultBlockState(), 0, 0);
            level.endFeature(true);
            assertHeightOracle(level);
            level.useDisplayTerrain(false);
            for (int y = 69; y < 75; y++) level.setBlock(new BlockPos(-16, y, -16), Blocks.AIR.defaultBlockState(), 0, 0);
            assertHeightOracle(level);

            var early = retainedLevel(sampler);
            early.setBlock(new BlockPos(-16, 100, -16), Blocks.STONE.defaultBlockState(), 0, 0);
            assertEquals(101, early.getHeight(Heightmap.Types.WORLD_SURFACE, -16, -16));
            // Version zero is reserved for columns with no writes, even on wraparound.
            var versions = (int[]) field(early, "columnQueryVersions");
            java.util.Arrays.fill(versions, -1);
            early.setBlock(new BlockPos(-16, 100, -16), Blocks.AIR.defaultBlockState(), 0, 0);
            assertHeightOracle(early);
        }
    }

    @Test void interiorColumnsKeepVolumeHeightsAndWarmCachesHonorCancellation() throws Exception {
        try (var sampler = sampler(true)) {
            var level = new PredictionDecorationLevel(sampler, sampler, RegistryAccess.EMPTY, -1, -1);
            assertHeightOracle(level);
            assertNotNull(field(level, "columns"));
        }
        for (String stop : new String[]{"close", "cancel", "interrupt"}) {
            try (var sampler = sampler(false)) {
                var level = retainedLevel(sampler);
                level.getHeight(Heightmap.Types.WORLD_SURFACE, -16, -16);
                if (stop.equals("close")) sampler.close();
                else if (stop.equals("cancel")) sampler.cancelWork();
                else Thread.currentThread().interrupt();
                assertThrows(java.util.concurrent.CancellationException.class,
                        () -> level.getHeight(Heightmap.Types.WORLD_SURFACE, -16, -16), stop);
            } finally { Thread.interrupted(); }
        }
    }
}
