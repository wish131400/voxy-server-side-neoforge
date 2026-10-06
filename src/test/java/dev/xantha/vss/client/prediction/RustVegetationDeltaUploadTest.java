package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RustVegetationDeltaUploadTest {
    static final Heightmap.Types[] HEIGHTS = {Heightmap.Types.WORLD_SURFACE_WG,
            Heightmap.Types.OCEAN_FLOOR_WG, Heightmap.Types.MOTION_BLOCKING, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES};

    @BeforeAll static void bootstrap() {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        var library = System.getProperty("vss.testNativeLibrary");
        if (library != null) RustWorldgenBackend.load(Path.of(library));
        assertTrue(RustTerrainSampler.available());
    }

    /** Real native flat terrain and four feature height predicates, shared by both measured variants. */
    static final class Fixture implements AutoCloseable {
        final RustTerrainSampler sampler;
        final ClientTerrainSampler context;
        final RegistryAccess access;
        final List<PlacedFeature> features;

        Fixture() throws Exception {
            var document = LithostitchedNativeTest.document();
            var settings = document.getAsJsonObject("settings");
            settings.addProperty("aquifers_enabled", false);
            var gradient = JsonParser.parseString("""
                    {"type":"minecraft:y_clamped_gradient","from_y":0,"to_y":128,"from_value":1,"to_value":-1}
                    """);
            settings.getAsJsonObject("noise_router").add("final_density", gradient);
            settings.getAsJsonObject("noise_router").add("initial_density_without_jaggedness", gradient.deepCopy());
            settings.add("surface_rule", JsonParser.parseString("""
                    {"type":"minecraft:block","result_state":{"Name":"minecraft:grass_block","Properties":{"snowy":"false"}}}
                    """));
            document.add("biome_source", JsonParser.parseString("{\"type\":\"minecraft:fixed\",\"biome\":\"minecraft:plains\"}"));
            document.add("possible_biomes", JsonParser.parseString("[\"minecraft:plains\"]"));
            document.add("input_states", JsonParser.parseString("""
                    [{"Name":"minecraft:air"},{"Name":"minecraft:stone"},{"Name":"minecraft:dirt"},
                     {"Name":"minecraft:water","Properties":{"level":"0"}},
                     {"Name":"minecraft:dandelion"}]
                    """));
            var registry = new MappedRegistry<PlacedFeature>(Registries.PLACED_FEATURE, Lifecycle.stable());
            var ops = net.minecraft.data.registries.VanillaRegistries.createLookup().createSerializationContext(JsonOps.INSTANCE);
            var nativeFeatures = new JsonObject();
            var names = new JsonArray();
            var list = new ArrayList<PlacedFeature>();
            for (int kind = 0; kind < HEIGHTS.length; kind++) {
                var json = JsonParser.parseString("""
                        {"feature":{"type":"minecraft:simple_block","config":{"to_place":{
                          "type":"minecraft:simple_state_provider","state":{"Name":"minecraft:dandelion"}}}},
                         "placement":[{"type":"minecraft:in_square"},
                          {"type":"minecraft:heightmap","heightmap":"%s"}]}
                        """.formatted(HEIGHTS[kind].name()));
                var feature = PlacedFeature.DIRECT_CODEC.parse(ops, json).getOrThrow();
                String name = "test:upload_height_" + kind;
                Registry.register(registry, ResourceLocation.tryParse(name), feature);
                nativeFeatures.add(name, json); names.add(name); list.add(feature);
            }
            registry.freeze();
            features = List.copyOf(list);
            access = new RegistryAccess.ImmutableRegistryAccess(List.of(registry));
            var steps = new JsonArray();
            for (int step = 0; step <= 9; step++) steps.add(step == 0 || step == 9 ? names.deepCopy() : new JsonArray());
            document.getAsJsonObject("biomes").getAsJsonObject("minecraft:plains").add("features", steps);
            document.add("placed_features", nativeFeatures);
            var profile = PredictionDecorationQueryTest.PROFILE;
            context = new ClientTerrainSampler(42, profile) {
                @Override RegistryAccess decorationAccess() { return access; }
            };
            sampler = new RustTerrainSampler(RustWorldgenBackend.create(42, 0, document.toString()), profile, context);
            var expectedOrder = new String[features.size()];
            for (int index = 0; index < expectedOrder.length; index++) expectedOrder[index] = names.get(index).getAsString();
            assertArrayEquals(expectedOrder, sampler.featureOrder()[0], "exact fixture must match native global order");
            assertArrayEquals(expectedOrder, sampler.featureOrder()[9], "display fixture must match native global order");
        }

        Run run(boolean display, int blockLayers) {
            var level = new PredictionDecorationLevel(sampler, context, access, 0, 0);
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                for (int y = 64; y < 64 + blockLayers; y++)
                    level.setBlock(new BlockPos(x, y, z), Blocks.DIRT.defaultBlockState(), 19, 0);
            var stage = new RustVegetationStage(sampler, level, 0, 0, display);
            stage.selectStep(features, display ? 9 : 0);
            return new Run(level, stage);
        }
        @Override public void close() { sampler.close(); }
    }

    record Run(PredictionDecorationLevel level, RustVegetationStage stage) implements AutoCloseable {
        @Override public void close() { stage.close(); }
    }

    @Test void firstSnapshotAndProxyRecreationKeepAllCommittedEdits() throws Exception {
        try (var fixture = new Fixture(); var run = fixture.run(true, 2)) {
            run.level.clearPendingUploads();
            long before = editsUp();
            assertNativePlace(fixture, run, 0);
            assertEquals(512, editsUp() - before, "new proxy must receive a full map even if pending is empty");
            var air = new BlockPos(-1, 65, -1);
            run.level.beginStructure();
            run.level.setBlock(air, Blocks.AIR.defaultBlockState(), 19, 0);
            run.level.endFeature(true);
            upload(run.stage);
            var expected = Map.copyOf(run.level.placed());
            run.stage.selectStep(fixture.features, 0);
            assertNativePlace(fixture, run, 0);
            assertTrue(run.level.pendingUploads().isEmpty());
            var snapshot = snapshot(run.stage);
            for (var entry : expected.entrySet())
                assertEquals(fixture.sampler.stateId(entry.getValue()), snapshot.at(entry.getKey()), entry.getKey().toString());
            assertEquals(Blocks.AIR.defaultBlockState(), run.level.placed().get(air));
        }
    }

    @Test void deltaPreservesGroundRollbackExistingOverlayAirAndStructures() throws Exception {
        for (boolean display : new boolean[]{false, true}) {
            try (var fixture = new Fixture(); var run = fixture.run(display, 0)) {
                assertNativePlace(fixture, run, 0);
                var ground = new BlockPos(-3, 63, -3);
                var natural = run.level.getBlockState(ground);
                assertFalse(natural.isAir());
                run.level.beginFeature();
                run.level.setBlock(ground, Blocks.AIR.defaultBlockState(), 19, 0);
                run.level.endFeature(false);
                assertFalse(run.level.placed().containsKey(ground));
                assertTrue(run.level.pendingUploads().containsKey(ground));
                long before = editsUp();
                upload(run.stage);
                assertEquals(before, editsUp(), "a discarded uncommitted write never changed the native ground");
                assertTrue(run.level.pendingUploads().isEmpty());

                var overlay = new BlockPos(4, 70, 4);
                run.level.setBlock(overlay, Blocks.DIRT.defaultBlockState(), 19, 0);
                upload(run.stage);
                run.level.beginFeature();
                run.level.setBlock(overlay, Blocks.AIR.defaultBlockState(), 19, 0);
                run.level.endFeature(false);
                before = editsUp();
                upload(run.stage);
                assertEquals(before + 1, editsUp());
                assertEquals(Blocks.DIRT.defaultBlockState(), run.level.getBlockState(overlay));
                var removed = new BlockPos(5, 72, 5);
                run.level.beginStructure();
                run.level.setBlock(removed, Blocks.AIR.defaultBlockState(), 19, 0);
                run.level.setBlock(overlay.above(), Blocks.STONE.defaultBlockState(), 19, 0);
                run.level.endFeature(true);
                before = editsUp();
                upload(run.stage);
                assertEquals(before + 2, editsUp(), "explicit air and structure writes remain real delta records");
                var snapshot = snapshot(run.stage);
                assertEquals(fixture.sampler.stateId(natural), snapshot.at(ground));
                assertEquals(fixture.sampler.stateId(Blocks.DIRT.defaultBlockState()), snapshot.at(overlay));
                assertEquals(fixture.sampler.stateId(Blocks.AIR.defaultBlockState()), snapshot.at(removed));
                assertEquals(fixture.sampler.stateId(Blocks.STONE.defaultBlockState()), snapshot.at(overlay.above()));
                assertHeightOracle(fixture, run.level, snapshot);
            }
        }
    }

    @Test void rejectedJniUploadRetainsPendingStateForASuccessfulRetry() throws Exception {
        try (var fixture = new Fixture(); var run = fixture.run(false, 0)) {
            assertNativePlace(fixture, run, 0);
            var pos = new BlockPos(7, 75, 7);
            run.level.setBlock(pos, Blocks.STONE.defaultBlockState(), 19, 0);
            var pending = Map.copyOf(run.level.pendingUploads());
            var field = RustVegetationStage.class.getDeclaredField("volume"); field.setAccessible(true);
            long volume = field.getLong(run.stage);
            field.setLong(run.stage, -1);
            try { assertThrows(IllegalArgumentException.class, () -> upload(run.stage)); }
            finally { field.setLong(run.stage, volume); }
            assertEquals(pending, run.level.pendingUploads());
            upload(run.stage);
            assertTrue(run.level.pendingUploads().isEmpty());
            assertEquals(fixture.sampler.stateId(Blocks.STONE.defaultBlockState()), snapshot(run.stage).at(pos));
        }
    }

    static void assertNativePlace(Fixture fixture, Run run, int index) throws Exception {
        boolean placed = run.stage.place(index);
        if (!placed) {
            var field = RustVegetationStage.class.getDeclaredField("features"); field.setAccessible(true);
            var job = (RustVegetationDescriptors.Job) field.get(run.stage);
            fail("native stage fell back: reason=" + job.reason(index) + ",name=" + job.name(index)
                    + ",order=" + Arrays.deepToString(fixture.sampler.featureOrder())
                    + ",support=" + RustWorldgenBackend.support(fixture.sampler.handle(), '"' + job.name(index) + '"', 1));
        }
    }
    static void upload(RustVegetationStage stage) throws Exception {
        var method = RustVegetationStage.class.getDeclaredMethod("upload"); method.setAccessible(true);
        try { method.invoke(stage); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException cause) throw cause;
            throw failure;
        }
    }
    static void legacyFullUploadOnNextPendingChange(RustVegetationStage stage) throws Exception {
        var field = RustVegetationStage.class.getDeclaredField("synced"); field.setAccessible(true); field.setBoolean(stage, false);
    }
    static long editsUp() throws Exception {
        var field = RustVegetationStage.class.getDeclaredField("EDITS_UP"); field.setAccessible(true);
        return ((LongAdder) field.get(null)).sum();
    }
    static Snapshot snapshot(RustVegetationStage stage) throws Exception {
        var field = RustVegetationStage.class.getDeclaredField("volume"); field.setAccessible(true);
        long volume = field.getLong(stage);
        var description = JsonParser.parseString(RustWorldgenBackend.describe(volume)).getAsJsonObject();
        int[] origin = ints(description.getAsJsonArray("origin")), size = ints(description.getAsJsonArray("size"));
        int count = size[0] * size[1] * size[2];
        var buffer = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(count, RustWorldgenBackend.readVolume(volume, buffer));
        int[] blocks = new int[count]; buffer.asIntBuffer().get(blocks);
        return new Snapshot(origin, size, blocks);
    }
    static int[] assertHeightOracle(Fixture fixture, PredictionDecorationLevel level, Snapshot snapshot) {
        int[] heights = new int[16 * 16 * HEIGHTS.length];
        var states = fixture.sampler.states();
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) for (int kind = 0; kind < HEIGHTS.length; kind++) {
            int expected = snapshot.origin[1];
            for (int y = snapshot.origin[1] + snapshot.size[1] - 1; y >= snapshot.origin[1]; y--)
                if (HEIGHTS[kind].isOpaque().test(states[snapshot.at(new BlockPos(x, y, z))])) { expected = y + 1; break; }
            heights[(z * 16 + x) * HEIGHTS.length + kind] = expected;
            assertEquals(expected, level.getHeight(HEIGHTS[kind], x, z), "native/Java height " + x + "," + z + ":" + HEIGHTS[kind]);
        }
        return heights;
    }
    private static int[] ints(JsonArray values) {
        int[] result = new int[values.size()];
        for (int i = 0; i < result.length; i++) result[i] = values.get(i).getAsInt();
        return result;
    }
    record Snapshot(int[] origin, int[] size, int[] blocks) {
        int at(BlockPos position) {
            int x = position.getX() - origin[0], y = position.getY() - origin[1], z = position.getZ() - origin[2];
            return blocks[(x * size[2] + z) * size[1] + y];
        }
    }
}
