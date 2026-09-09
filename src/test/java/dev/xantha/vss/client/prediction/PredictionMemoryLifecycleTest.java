package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import dev.xantha.vss.config.VSSClientConfig;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PredictionMemoryLifecycleTest {
    private static final DimensionProfile PROFILE = new DimensionProfile(
            ResourceLocation.withDefaultNamespace("overworld"), 42L, -64, 384,
            "noise", "minecraft:overworld", 1L);

    @BeforeAll
    static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test
    void constrainedBudgetKeepsCoarseCoverageAndClosesWithoutLeakingReservations() throws Exception {
        boolean remember = VSSClientConfig.CONFIG.rememberTerrain;
        int distance = VSSClientConfig.CONFIG.predictionDistanceBlocks;
        boolean trees = VSSClientConfig.CONFIG.predictionTrees;
        VSSClientConfig.CONFIG.rememberTerrain = false;
        VSSClientConfig.CONFIG.predictionDistanceBlocks = 1024;
        VSSClientConfig.CONFIG.predictionTrees = false;
        PredictionMemoryBudget budget = new PredictionMemoryBudget(144L * PredictionMemoryBudget.MIB,
                0, () -> Long.MAX_VALUE, System::nanoTime);
        try (PredictionTileManager manager = new PredictionTileManager(PROFILE.levelKey(), plain(), budget)) {
            var leaves = PredictionLodPlanner.plan(PROFILE.levelKey(), 2261, 123, 3901,
                    manager.layout(), null, 1500);
            var plan = PredictionTileManager.withCoarseCoverage(leaves, manager.layout());
            var roots = plan.stream().filter(key -> key.lod() == manager.layout().levelCount() - 1).toList();
            var previouslyCovered = new HashSet<PredictionTileManager.PredictionTileKey>();
            for (int cycle = 0; cycle < 150; cycle++) {
                replan(manager);
                await(() -> manager.pendingCount() == 0);
                assertTrue(budget.usedBytes() <= budget.limitBytes());
                var current = manager.readyTiles().stream().map(PredictionTileManager.PredictionTile::key)
                        .collect(java.util.stream.Collectors.toSet());
                for (var key : leaves) {
                    boolean covered = current.contains(key) || PredictionTileManager.coveredByAncestor(
                            current, java.util.Set.of(), PROFILE.levelKey(), manager.layout(), key);
                    if (previouslyCovered.contains(key)) assertTrue(covered, "memory pressure cannot open previously covered ground: " + key);
                    if (covered) previouslyCovered.add(key);
                }
                if (budget.exhausted()) break;
            }
            assertTrue(budget.exhausted(), "test must actually reach the byte ceiling");
            var resident = new HashSet<PredictionTileManager.PredictionTileKey>();
            manager.readyTiles().forEach(tile -> resident.add(tile.key()));
            assertFalse(previouslyCovered.isEmpty(), "the constrained view must establish coverage");
            assertNotNull(manager.coveringTile(2261 >> 4, 3901 >> 4, 0), "near coverage must survive exhaustion");
            for (var key : resident) {
                if (key.lod() == manager.layout().levelCount() - 1) continue;
                assertTrue(resident.contains(new PredictionTileManager.PredictionTileKey(key.dimension(),
                        key.tileX() >> 1, key.tileZ() >> 1, key.lod() + 1)),
                        "each refined region retains its own fallback parent: " + key);
            }
            // The first pressure event may replace less urgent covered detail.
            // Verify convergence rather than mistaking one useful upgrade for a loop.
            int stable = 0;
            for (int i = 0; i < 60 && stable < 5; i++) {
                long builds = manager.builtTileCount();
                replan(manager);
                await(() -> manager.pendingCount() == 0);
                stable = manager.builtTileCount() == builds ? stable + 1 : 0;
                assertTrue(budget.usedBytes() <= budget.limitBytes());
            }
            assertEquals(5,stable,"a full static view must converge without rebuilding in a loop");
            assertTrue(manager.readyCount() < 100, "byte cap must act before the old 2048-tile ceiling");
            System.out.println("bounded manager: tiles=" + manager.readyCount() + ", roots=" + roots.size()
                    + ", residentBytes=" + budget.usedBytes() + ", limit=" + budget.limitBytes());
        } finally {
            VSSClientConfig.CONFIG.rememberTerrain = remember;
            VSSClientConfig.CONFIG.predictionDistanceBlocks = distance;
            VSSClientConfig.CONFIG.predictionTrees = trees;
        }
        await(() -> budget.usedBytes() == 0);
    }

    @Test
    void fullyIngestedRegionKeepsSelectedDetailAndRebuildsItsFallback() throws Exception {
        boolean remember = VSSClientConfig.CONFIG.rememberTerrain;
        int distance = VSSClientConfig.CONFIG.predictionDistanceBlocks;
        boolean trees = VSSClientConfig.CONFIG.predictionTrees;
        VSSClientConfig.CONFIG.rememberTerrain = false;
        VSSClientConfig.CONFIG.predictionDistanceBlocks = 1024;
        VSSClientConfig.CONFIG.predictionTrees = false;
        PredictionMemoryBudget budget = new PredictionMemoryBudget(256L * PredictionMemoryBudget.MIB,
                0, () -> Long.MAX_VALUE, System::nanoTime);
        try (PredictionTileManager manager = new PredictionTileManager(PROFILE.levelKey(), plain(), budget)) {
            int top = manager.layout().levelCount() - 1;
            var leaves = PredictionLodPlanner.plan(PROFILE.levelKey(), 2261, 123, 3901,
                    manager.layout(), null, 1500);
            var roots = PredictionTileManager.withCoarseCoverage(leaves, manager.layout())
                    .stream().filter(key -> key.lod() == top
                            && PredictionWorkOrder.distanceSquared(key, manager.layout(), 2261, 3901) == 0).toList();
            // Ingest/reload concerns the loaded nearby region. Faraway roots
            // need not finish before nearby detail under a constrained budget.
            assertFalse(roots.isEmpty());
            for (int cycle = 0; cycle < 100; cycle++) {
                replan(manager);
                await(() -> manager.pendingCount() == 0);
                var resident = manager.readyTiles().stream().map(PredictionTileManager.PredictionTile::key)
                        .collect(java.util.stream.Collectors.toSet());
                if (resident.containsAll(roots) && resident.stream().anyMatch(key -> key.lod() < top)) break;
            }
            assertTrue(manager.readyTiles().stream().map(PredictionTileManager.PredictionTile::key)
                    .collect(java.util.stream.Collectors.toSet()).containsAll(roots));
            var fineBefore = manager.readyTiles().stream().filter(tile -> tile.key().lod() < top)
                    .map(PredictionTileManager.PredictionTile::key).toList();
            assertFalse(fineBefore.isEmpty(), "test needs resident detail before ingest");
            int span = manager.layout().tileBlocks(top) / 16;
            for (var key : roots) {
                for (int z = 0; z < span; z++) for (int x = 0; x < span; x++) {
                    manager.invalidate(key.tileX() * span + x, key.tileZ() * span + z);
                }
                assertTrue(manager.fullyAuthoritative(key));
            }
            replan(manager);
            await(() -> manager.pendingCount() == 0);
            assertTrue(manager.readyCount() > roots.size(), "ingest must retain fine surfaces instead of exposing coarse slabs");
            assertTrue(manager.readyTiles().stream().map(PredictionTileManager.PredictionTile::key)
                    .collect(java.util.stream.Collectors.toSet()).containsAll(fineBefore),
                    "ingest cannot discard already selected nearby detail");
            for (var key : roots) {
                assertNotNull(manager.coveringTile(key.tileX() * span, key.tileZ() * span, 0),
                        "ingest must retain drawable coverage");
            }
            manager.invalidateAppearance();
            for (int cycle = 0; cycle < 100; cycle++) {
                replan(manager);
                await(() -> manager.pendingCount() == 0);
                var resident = manager.readyTiles().stream().map(PredictionTileManager.PredictionTile::key)
                        .collect(java.util.stream.Collectors.toSet());
                if (resident.containsAll(roots)) break;
            }
            for (var key : roots) {
                assertTrue(manager.readyTiles().stream().anyMatch(tile -> tile.key().equals(key)),
                        "fully ingested roots must rebuild after resource reload");
            }
        } finally {
            VSSClientConfig.CONFIG.rememberTerrain = remember;
            VSSClientConfig.CONFIG.predictionDistanceBlocks = distance;
            VSSClientConfig.CONFIG.predictionTrees = trees;
        }
        await(() -> budget.usedBytes() == 0);
    }

    @Test
    void closingDuringSamplingCannotPublishOrKeepAWorkingReservation() throws Exception {
        boolean remember = VSSClientConfig.CONFIG.rememberTerrain;
        VSSClientConfig.CONFIG.rememberTerrain = false;
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        PredictionMemoryBudget budget = new PredictionMemoryBudget(256L * PredictionMemoryBudget.MIB,
                0, () -> Long.MAX_VALUE, System::nanoTime);
        ClientTerrainSampler sampler = new ClientTerrainSampler(42, PROFILE) {
            @Override public ClientColumnSample sample(int x, int z) {
                entered.countDown();
                try { release.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                return plainColumn();
            }
            @Override public ClientColumnSample sampleForLod(int x, int z, int step) { return sample(x, z); }
        };
        PredictionTileManager manager = new PredictionTileManager(PROFILE.levelKey(), sampler, budget);
        try {
            replan(manager);
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertTrue(budget.usedBytes() > 0);
            manager.close();
            release.countDown();
            await(() -> budget.usedBytes() == 0);
            assertEquals(0, manager.readyCount());
            assertEquals(0, manager.builtTileCount());
        } finally {
            release.countDown();
            manager.close();
            VSSClientConfig.CONFIG.rememberTerrain = remember;
        }
    }

    private static void replan(PredictionTileManager manager) {
        for (int tick = 0; tick < 5; tick++) manager.tick(2261, 123, 3901, 1500, null);
    }

    @Test
    void pauseCancelsQueuedWorkAndPreventsStalePublicationThenResumes() throws Exception {
        boolean remember = VSSClientConfig.CONFIG.rememberTerrain;
        VSSClientConfig.CONFIG.rememberTerrain = false;
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        PredictionMemoryBudget budget = new PredictionMemoryBudget(144L * PredictionMemoryBudget.MIB,
                0, () -> Long.MAX_VALUE, System::nanoTime);
        ClientTerrainSampler sampler = new ClientTerrainSampler(42, PROFILE) {
            @Override public ClientColumnSample sample(int x, int z) {
                entered.countDown();
                try { release.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                return plainColumn();
            }
            @Override public ClientColumnSample sampleForLod(int x, int z, int step) { return sample(x, z); }
        };
        try (PredictionTileManager manager = new PredictionTileManager(PROFILE.levelKey(), sampler, budget)) {
            replan(manager);
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertTimeout(java.time.Duration.ofMillis(250), () -> manager.setPaused(true));
            release.countDown();
            await(() -> manager.pendingCount() == 0);
            assertEquals(0, manager.readyCount());
            replan(manager);
            assertEquals(0, manager.pendingCount());
            manager.setPaused(false);
            replan(manager);
            await(() -> manager.pendingCount() == 0);
            assertTrue(manager.readyCount() > 0);
        } finally {
            release.countDown();
            VSSClientConfig.CONFIG.rememberTerrain = remember;
        }
        await(() -> budget.usedBytes() == 0);
    }

    @Test
    void atlasReloadRejectsInFlightMeshesAndReleasesResidentMemory() throws Exception {
        boolean remember = VSSClientConfig.CONFIG.rememberTerrain;
        VSSClientConfig.CONFIG.rememberTerrain = false;
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        PredictionMemoryBudget budget = new PredictionMemoryBudget(256L * PredictionMemoryBudget.MIB,
                0, () -> Long.MAX_VALUE, System::nanoTime);
        ClientTerrainSampler sampler = new ClientTerrainSampler(42, PROFILE) {
            @Override public ClientColumnSample sample(int x, int z) {
                entered.countDown();
                try { release.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                return plainColumn();
            }
            @Override public ClientColumnSample sampleForLod(int x, int z, int step) { return sample(x, z); }
        };
        try (PredictionTileManager manager = new PredictionTileManager(PROFILE.levelKey(), sampler, budget)) {
            replan(manager);
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            manager.invalidateAppearance();
            release.countDown();
            await(() -> manager.pendingCount() == 0);
            assertEquals(0, manager.readyCount(), "old sprite rows must not be published");
            assertEquals(0, budget.usedBytes());
            replan(manager);
            await(() -> manager.pendingCount() == 0);
            assertTrue(manager.readyCount() > 0, "prediction must resume after the reload");
            assertTrue(manager.readyTiles().stream().allMatch(tile -> tile.revision() > 0));
            assertEquals(manager.readyCount(), manager.readyTiles().stream()
                    .map(PredictionTileManager.PredictionTile::revision).distinct().count());
            manager.invalidateAppearance();
            assertEquals(0, manager.readyCount());
            assertEquals(0, budget.usedBytes(), "invalidated resident meshes release reservations");
        } finally {
            release.countDown();
            VSSClientConfig.CONFIG.rememberTerrain = remember;
        }
    }

    private static ClientTerrainSampler plain() {
        return new ClientTerrainSampler(42, PROFILE) {
            @Override public ClientColumnSample sample(int x, int z) { return plainColumn(); }
            @Override public ClientColumnSample sampleForLod(int x, int z, int step) { return plainColumn(); }
            @Override public int surfaceColor(int x, int y, int z) { return 0xFF7FB238; }
            @Override public int foliageColor(int x, int y, int z) { return 0xFF7FB238; }
        };
    }

    @Test
    void authoritativeCaptureReplacesResidentMeshOnceAndKeepsOldCoverageUntilReady() throws Exception {
        boolean remember = VSSClientConfig.CONFIG.rememberTerrain;
        int distance = VSSClientConfig.CONFIG.predictionDistanceBlocks;
        VSSClientConfig.CONFIG.rememberTerrain = false;
        VSSClientConfig.CONFIG.predictionDistanceBlocks = 1024;
        var budget = new PredictionMemoryBudget(512L * PredictionMemoryBudget.MIB,
                0, () -> Long.MAX_VALUE, System::nanoTime);
        try (var manager = new PredictionTileManager(PROFILE.levelKey(), plain(), budget)) {
            for (int i = 0; i < 8; i++) {
                coarsePlan(manager);
                await(() -> manager.pendingCount() == 0);
            }
            var old = manager.readyTiles().stream().findFirst().orElseThrow();
            int span = manager.layout().tileBlocks(old.key().lod());
            int chunkX = old.key().tileX() * span / 16, chunkZ = old.key().tileZ() * span / 16;
            manager.captureExactColumn(chunkX, chunkZ, new dev.xantha.vss.api.VoxelColumnData(
                    new dev.xantha.vss.api.VoxelColumnData.SectionData[0], 1L, true));
            var captures = PredictionTileManager.class.getDeclaredField("pendingCaptures");
            captures.setAccessible(true);
            var pendingCaptures = (java.util.Set<?>) captures.get(manager);
            await(pendingCaptures::isEmpty);
            assertSame(old, manager.readyTiles().stream().filter(tile -> tile.key().equals(old.key()))
                    .findFirst().orElseThrow(), "capture cannot remove coverage before the replacement exists");
            // Capture completion and workspace release are separate worker
            // steps. Keep issuing ticks until publication, as the game does;
            // one admission attempt can legitimately find every slot occupied.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            do {
                coarsePlan(manager);
                await(() -> manager.pendingCount() == 0);
            } while (manager.readyTiles().stream().filter(tile -> tile.key().equals(old.key()))
                    .findFirst().orElseThrow().revision() == old.revision() && System.nanoTime() < deadline);
            var replacement = manager.readyTiles().stream().filter(tile -> tile.key().equals(old.key()))
                    .findFirst().orElseThrow();
            assertNotEquals(old.revision(), replacement.revision(), "GPU upload key must change");
            assertTrue(replacement.samples()[0].captured());
            assertFalse(replacement.samples()[0].hasSurface(), "captured air must replace old ground");
            long built = manager.builtTileCount();
            for (int i = 0; i < 5; i++) coarsePlan(manager);
            await(() -> manager.pendingCount() == 0);
            assertEquals(replacement.revision(), manager.readyTiles().stream()
                    .filter(tile -> tile.key().equals(old.key())).findFirst().orElseThrow().revision(),
                    "one capture must not rebuild the same tile again while neighboring stages finish");
        } finally {
            VSSClientConfig.CONFIG.rememberTerrain = remember;
            VSSClientConfig.CONFIG.predictionDistanceBlocks = distance;
        }
        await(() -> budget.usedBytes() == 0);
    }

    @Test
    void captureDuringBuildRejectsStaleMeshAndUnloadedCapturesKeepNoDirtyMetadata() throws Exception {
        boolean remember = VSSClientConfig.CONFIG.rememberTerrain;
        int distance = VSSClientConfig.CONFIG.predictionDistanceBlocks;
        VSSClientConfig.CONFIG.rememberTerrain = false;
        VSSClientConfig.CONFIG.predictionDistanceBlocks = 1024;
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var budget = new PredictionMemoryBudget(512L * PredictionMemoryBudget.MIB,
                0, () -> Long.MAX_VALUE, System::nanoTime);
        var sampler = new ClientTerrainSampler(42, PROFILE) {
            @Override public ClientColumnSample sample(int x, int z) {
                entered.countDown();
                try { release.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                return plainColumn();
            }
            @Override public ClientColumnSample sampleForLod(int x, int z, int step) { return sample(x, z); }
        };
        try (var manager = new PredictionTileManager(PROFILE.levelKey(), sampler, budget)) {
            for (int i = 0; i < 1000; i++) manager.capturedTerrainChanged(i * 3, i * -7);
            var epochs = PredictionTileManager.class.getDeclaredField("captureEpochs");
            epochs.setAccessible(true);
            assertTrue(((java.util.Map<?, ?>) epochs.get(manager)).isEmpty());
            coarsePlan(manager);
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            var plan = PredictionTileManager.withCoarseCoverage(PredictionLodPlanner.plan(
                    PROFILE.levelKey(), 64, 100, 64, manager.layout(), null, .01,
                    PROFILE.minY(), PROFILE.minY() + PROFILE.height()), manager.layout());
            for (var key : plan) {
                int span = manager.layout().tileBlocks(key.lod()) / 16;
                manager.capturedTerrainChanged(key.tileX() * span, key.tileZ() * span);
            }
            release.countDown();
            await(() -> manager.pendingCount() == 0);
            assertEquals(0, manager.readyCount(), "pre-capture mesh cannot publish after capture");
            coarsePlan(manager);
            await(() -> manager.pendingCount() == 0);
            assertTrue(manager.readyCount() > 0, "fresh builds resume after stale work is discarded");
        } finally {
            release.countDown();
            VSSClientConfig.CONFIG.rememberTerrain = remember;
            VSSClientConfig.CONFIG.predictionDistanceBlocks = distance;
        }
        await(() -> budget.usedBytes() == 0);
    }

    private static void coarsePlan(PredictionTileManager manager) {
        for (int i = 0; i < 5; i++) manager.tick(64, 100, 64, .01, null);
    }

    private static ClientColumnSample plainColumn() {
        return new ClientColumnSample(64, 64, 0, PredictionMaterialPalette.grassBlockIndex(),
                0, 0, 0, 0, 0, 0, 0, ClientColumnSample.NO_BLOCK, ClientColumnSample.NO_BLOCK,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
    }

    private static void await(BooleanSupplier done) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!done.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(done.getAsBoolean(), "worker condition timed out");
    }
}
