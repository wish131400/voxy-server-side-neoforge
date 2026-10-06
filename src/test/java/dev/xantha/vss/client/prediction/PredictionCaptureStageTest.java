package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import dev.xantha.vss.config.VSSClientConfig;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.*;

class PredictionCaptureStageTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void staleNativeOrJavaSamplingStopsBeforeColorDecorationAndPublicationAndCanRetry() throws Exception {
        var config = VSSClientConfig.CONFIG;
        int distance = config.predictionDistanceBlocks;
        boolean trees = config.predictionTrees, structures = config.predictionStructures;
        config.predictionDistanceBlocks = 4096;
        config.predictionTrees = false; config.predictionStructures = false;
        var sampled = new AtomicInteger();
        var colored = new AtomicInteger();
        var invalidated = new AtomicBoolean();
        var current = new AtomicReference<PredictionTileManager>();
        var source = new ClientTerrainSampler(42, PredictionDecorationQueryTest.PROFILE) {
            @Override int initialTerrainCellAxis(int lod) { return 8; }
            @Override public ClientColumnSample samplePreview(int x, int z, int step) {
                sampled.incrementAndGet();
                if (invalidated.compareAndSet(false, true)) current.get().capturedTerrainChanged(0, 0);
                return PredictionSimpleVegetationTest.sample(120);
            }
            @Override public ClientColumnSample sampleSurface(int x, int z) {
                sampled.incrementAndGet(); return PredictionSimpleVegetationTest.sample(120);
            }
            @Override int surfaceColorForLod(int x, int y, int z, boolean preview) {
                colored.incrementAndGet(); return 0xff669944;
            }
        };
        var budget = new PredictionMemoryBudget(2048L * PredictionMemoryBudget.MIB, 0,
                () -> Long.MAX_VALUE, System::nanoTime, 1);
        try (var manager = new PredictionTileManager(Level.OVERWORLD, source, budget, null)) {
            var target = new PredictionTileManager.PredictionTileKey(Level.OVERWORLD, 0, 0, manager.layout().levelCount() - 1);
            current.set(manager);
            var desired = PredictionTileManager.class.getDeclaredField("desiredKeys");
            desired.setAccessible(true); desired.set(manager, new HashSet<>(Set.of(target)));
            var enqueue = PredictionTileManager.class.getDeclaredMethod("enqueue",
                    PredictionTileManager.PredictionTileKey.class, int.class, int.class, boolean.class, boolean.class);
            enqueue.setAccessible(true); enqueue.invoke(manager, target, 0, 0, false, false);
            awaitIdle(manager);
            assertEquals(1, sampled.get(), "abort before scanning the rest of the grid");
            assertEquals(0, colored.get(), "stale samples must never enter tint/decoration work");
            assertEquals(0, manager.readyCount());
            assertEquals(0, budget.usedBytes(), "aborted work must release its reservation");
            enqueue.invoke(manager, target, 0, 0, false, false);
            awaitIdle(manager);
            assertEquals(1, manager.readyCount(), manager.surfaceDiagnostics());
            assertEquals(0, manager.failedTileCount());
        } finally {
            config.predictionDistanceBlocks = distance;
            config.predictionTrees = trees; config.predictionStructures = structures;
        }
        assertEquals(0, budget.usedBytes());
    }

    @Test void meshCancellationIsCheckedInsideRowsAndDoesNotTriggerSimplificationRetries() {
        var samples = new ClientColumnSample[66 * 66];
        Arrays.fill(samples, PredictionSimpleVegetationTest.sample(120));
        var checks = new AtomicInteger();
        assertThrows(CancellationException.class, () -> PredictionMeshBuilder.build(
                samples, null, 63, 0, 1, 66, null, null, 0, 0,
                PredictionVegetation.Tile.EMPTY, PredictionSimpleVegetation.Result.EMPTY, null,
                () -> checks.incrementAndGet() < 6));
        assertEquals(6, checks.get());
    }

    @Test void staleDecorationReleasesBeforeStartingAnyFeatureReplay() {
        var source = new ClientTerrainSampler(42, PredictionDecorationQueryTest.PROFILE);
        var plants = new PredictionVegetation(source);
        assertThrows(CancellationException.class,
                () -> plants.tile(0, 0, 64, 1, true, (x, z) -> false, () -> false));
    }

    private static void awaitIdle(PredictionTileManager manager) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (manager.pendingCount() != 0 && System.nanoTime() < deadline) Thread.sleep(5);
        assertEquals(0, manager.pendingCount(), manager.surfaceDiagnostics());
    }
}
