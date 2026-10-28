package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.Executor;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;

class PredictionTurnMainTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    private static final class ManualExecutor implements Executor {
        final ArrayDeque<Runnable> jobs = new ArrayDeque<>();
        @Override public void execute(Runnable job) { jobs.add(job); }
        void finish() { jobs.removeFirst().run(); }
    }





    @Test void viewClassificationRunsOffThreadAndIncludesAncestorAndNeighbourCoverage() {
        var executor = new ManualExecutor();
        var calculation = new PredictionDemandPreparation();
        var preparation = new PredictionClientPreparation<PredictionDemandPreparation.Input, PredictionDemandPreparation.Result>(executor, calculation::build);
        var layout = VssLodLayout.of(4096, 6, true, false);
        var front = new PredictionTileKey(Level.OVERWORLD, 4, 0, 1);
        var parent = new PredictionTileKey(Level.OVERWORLD, 2, 0, 2);
        var neighbour = new PredictionTileKey(Level.OVERWORLD, 3, 0, 1);
        var back = new PredictionTileKey(Level.OVERWORLD, -4, 0, 1);
        var view = PredictionWorkView.of(0, 80, 0, 1, 0, 0, 70, 1);
        var desired = Set.of(front, parent, neighbour, back);
        var input = new PredictionDemandPreparation.Input(layout, view, null, desired, Map.of(), Map.of(), Map.of(),
                Set.of(), Set.of(), Map.of(), -64, 384, 10);
        preparation.request(input); preparation.request(input);
        assertEquals(1, executor.jobs.size()); assertNull(preparation.poll());
        executor.finish(); var result = preparation.poll(); assertNotNull(result);
        for (var key : desired) assertEquals(view.band(key, layout, null, -64, 384), result.bands().get(key));
        assertTrue(result.dependencies().containsAll(List.of(front, parent, neighbour)));
        assertFalse(result.dependencies().contains(back));
        assertThrows(UnsupportedOperationException.class, () -> result.bands().put(front, 5));
        preparation.request(input); preparation.clear(); executor.finish(); assertNull(preparation.poll());
    }








}
