package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RustDecorationReuseTest {
    @BeforeAll static void bootstrap() {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        assertTrue(RustTerrainSampler.available());
    }

    private static RustTerrainSampler sampler() throws Exception {
        return sampler(false);
    }

    private static RustTerrainSampler sampler(boolean orientedSurface) throws Exception {
        var doc = LithostitchedNativeTest.document();
        doc.add("possible_biomes", new JsonArray());
        if (orientedSurface) {
            doc.getAsJsonObject("settings").add("surface_rule", com.google.gson.JsonParser.parseString("""
                    {"type":"minecraft:block","result_state":{"Name":"minecraft:oak_log","Properties":{"axis":"x"}}}
                    """));
        }
        var profile = new DimensionProfile(ResourceLocation.withDefaultNamespace("overworld"),
                -64, 384, "noise", "minecraft:overworld", 1L);
        return new RustTerrainSampler(RustWorldgenBackend.create(1, 0, doc.toString()), profile,
                new ClientTerrainSampler(1, profile));
    }

    @SuppressWarnings("unchecked")
    private static Map<Long, int[]> sharedPoints(RustTerrainSampler sampler) throws Exception {
        var field = RustTerrainSampler.class.getDeclaredField("points");
        field.setAccessible(true);
        return (Map<Long, int[]>) field.get(sampler);
    }

    @Test void decorationRetainsRefinedColumnAfterSharedCacheEviction() throws Exception {
        try (var sampler = sampler()) {
            // Use real precise refinement output before decoration starts.
            var refined = sampler.sampleGrid(-16, -16, 1, 8);
            var level = new PredictionDecorationLevel(sampler, sampler, RegistryAccess.EMPTY, -1, -1);
            assertEquals(refined[0], level.column(-16, -16));
            var expected = new BlockState[384];
            for (int y = -64; y < 320; y++) expected[y + 64] = sampler.proxyBlock(-16, y, -16);
            var shared = sharedPoints(sampler);
            synchronized (shared) { shared.clear(); }
            var p = new BlockPos.MutableBlockPos(-16, 0, -16);
            for (int round = 0; round < 3; round++) {
                for (int y = -64; y < 320; y++) {
                    assertEquals(expected[y + 64], level.getBlockState(p.setY(y)));
                }
            }
            assertTrue(shared.isEmpty(), "an active decoration job must not re-request its retained native column");
        }
    }

    @Test void displayDecorationSharesWarmTerrainWithoutEnteringExactJavaCache() throws Exception {
        try (var sampler = sampler(true)) {
            var display=sampler.sampleDisplayGrid(-16,-16,1,8,8,new ClientColumnSample[64]);
            var level=new PredictionDecorationLevel(sampler,sampler,RegistryAccess.EMPTY,-1,-1);
            int exactSize=sharedPoints(sampler).size();
            level.useDisplayTerrain(true);
            assertEquals(display[0],level.column(-16,-16));
            var stats=com.google.gson.JsonParser.parseString(RustWorldgenBackend.decorationQueryStats(sampler.handle())).getAsJsonObject();
            assertEquals(16,stats.get("columns").getAsInt());
            assertEquals(16,stats.get("cached").getAsInt());
            assertEquals(exactSize,sharedPoints(sampler).size());
            var ground=new BlockPos(-16,display[0].surfaceY()-1,-16);
            assertEquals(net.minecraft.core.Direction.Axis.X,level.getBlockState(ground)
                .getValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS));
            level.beginFeature();level.setBlock(ground.above(),Blocks.OAK_LOG.defaultBlockState(),0,0);level.endFeature(true);
            assertEquals(display[0],level.exteriorColumn(-16,-16));
            level.useDisplayTerrain(false);
            assertEquals(sampler.sample(-16,-16),level.column(-16,-16));
            level.beginStructure();level.setBlock(ground.above(),Blocks.OAK_PLANKS.defaultBlockState(),0,0);level.endFeature(true);
            level.useDisplayTerrain(true);
            assertEquals(sampler.sample(-16,-16),level.exteriorColumn(-16,-16));
            assertTrue(level.getBlockState(ground.above()).is(Blocks.OAK_PLANKS));
        }
    }

    @Test void pagedNativeProxyRejectsInvalidPolicyAndRetainsStructureEdits() throws Exception {
        try (var sampler=sampler()) {
            assertThrows(IllegalArgumentException.class,()->RustWorldgenBackend.decorationProxy(sampler.handle(),0,0,3));
            for(int policy=0;policy<3;policy++) {
                long volume=RustWorldgenBackend.decorationProxy(sampler.handle(),0,0,policy);
                try {
                    var buffer=java.nio.ByteBuffer.allocateDirect(16).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                    int state=sampler.stateId(Blocks.STONE.defaultBlockState());
                    buffer.putInt(0,0).putInt(4,100).putInt(8,0).putInt(12,state);
                    assertEquals(1,RustWorldgenBackend.applyEdits(volume,buffer,1));
                    var description=com.google.gson.JsonParser.parseString(RustWorldgenBackend.describe(volume)).getAsJsonObject();
                    assertEquals(80,description.getAsJsonArray("size").get(0).getAsInt());
                } finally {RustWorldgenBackend.close(volume);}
            }
        }
    }

    @Test void retainedBaseStillHonorsEditsRollbackBoundsAndCancellation() throws Exception {
        try (var sampler = sampler()) {
            var level = new PredictionDecorationLevel(sampler, sampler, RegistryAccess.EMPTY, 0, 0);
            int y = Math.max(level.column(0, 0).surfaceY(), level.column(0, 0).fluidY());
            var position = new BlockPos(0, y, 0);
            var original = level.getBlockState(position);
            level.beginFeature();
            level.setBlock(position, Blocks.OAK_LOG.defaultBlockState(), 0, 0);
            assertEquals(Blocks.OAK_LOG.defaultBlockState(), level.getBlockState(position));
            level.endFeature(false);
            assertEquals(original, level.getBlockState(position));
            assertThrows(UnsupportedOperationException.class, () -> level.getBlockState(new BlockPos(48, 64, 0)));
            sampler.close();
            assertThrows(java.util.concurrent.CancellationException.class, () -> level.getBlockState(position));
        }
    }

    @Test void reusePreservesNativeBlockPropertiesInsteadOfRebuildingDefaultStates() throws Exception {
        try (var sampler = sampler(true)) {
            var level = new PredictionDecorationLevel(sampler, sampler, RegistryAccess.EMPTY, 0, 0);
            var sample = level.column(0, 0);
            assertTrue(sample.hasSurface());
            var state = level.getBlockState(new BlockPos(0, sample.surfaceY() - 1, 0));
            assertTrue(state.is(Blocks.OAK_LOG));
            assertEquals(net.minecraft.core.Direction.Axis.X, state.getValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS));
        }
    }

    @Test void concurrentReadersMatchSharedLookupAndReportCost() throws Exception {
        try (var sampler = sampler()) {
            sampler.sampleGrid(0, 0, 1, 8);
            for (int workers : new int[]{1, 8}) {
                var levels = new PredictionDecorationLevel[workers];
                for (int n = 0; n < workers; n++) {
                    levels[n] = new PredictionDecorationLevel(sampler, sampler, RegistryAccess.EMPTY, 0, 0);
                    for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) levels[n].column(x, z);
                }
                try (var executor = java.util.concurrent.Executors.newFixedThreadPool(workers)) {
                    var before = new java.util.ArrayList<Long>();
                    var after = new java.util.ArrayList<Long>();
                    Long expected = null;
                    for (int round = -2; round < 7; round++) {
                        for (boolean retained : round % 2 == 0 ? new boolean[]{false, true} : new boolean[]{true, false}) {
                            var ready = new java.util.concurrent.CountDownLatch(workers);
                            var start = new java.util.concurrent.CountDownLatch(1);
                            var results = new java.util.ArrayList<java.util.concurrent.Future<Long>>();
                            for (var level : levels) results.add(executor.submit(() -> {
                                ready.countDown(); start.await();
                                var p = new BlockPos.MutableBlockPos();
                                long checksum = 0;
                                for (int i = 0; i < 100_000; i++) {
                                    p.set(i & 7, 40 + i % 96, (i >>> 3) & 7);
                                    BlockState state;
                                    if (retained) state = level.getBlockState(p);
                                    else {
                                        // Previous production path, including the placed overlay.
                                        state = level.placed().get(p);
                                        if (state == null) {
                                            level.column(p.getX(), p.getZ());
                                            state = sampler.proxyBlock(p.getX(), p.getY(), p.getZ());
                                        }
                                    }
                                    checksum = checksum * 31 + state.hashCode();
                                }
                                return checksum;
                            }));
                            assertTrue(ready.await(30, java.util.concurrent.TimeUnit.SECONDS));
                            long started = System.nanoTime(); start.countDown();
                            for (var result : results) {
                                long checksum = result.get(30, java.util.concurrent.TimeUnit.SECONDS);
                                if (expected == null) expected = checksum;
                                assertEquals(expected.longValue(), checksum);
                            }
                            long nanos = System.nanoTime() - started;
                            if (round >= 0) (retained ? after : before).add(nanos);
                        }
                    }
                    java.util.Collections.sort(before); java.util.Collections.sort(after);
                    System.out.printf(java.util.Locale.ROOT,
                            "DECORATION_REUSE workers=%d reads=%d sharedMs=%.3f retainedMs=%.3f speedup=%.3f%n",
                            workers, workers * 100_000, before.get(3) / 1e6, after.get(3) / 1e6,
                            before.get(3) / (double) after.get(3));
                }
            }
        }
    }
}
