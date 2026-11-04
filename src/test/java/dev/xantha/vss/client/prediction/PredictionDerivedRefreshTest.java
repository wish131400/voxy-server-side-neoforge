package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import dev.xantha.vss.client.prediction.PredictionTileManager.*;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.*;

class PredictionDerivedRefreshTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @SuppressWarnings("unchecked")
    @Test void livePlannerKeepsNewCoverageAheadOfCoveredParentRefreshAndRealCaptureUrgent() throws Exception {
        var method = PredictionFirstCoverageTest.class.getDeclaredMethod("sampler", AtomicInteger.class,
                java.util.concurrent.CountDownLatch.class, java.util.concurrent.CountDownLatch.class);
        method.setAccessible(true);
        var sampler = (ClientTerrainSampler) method.invoke(null, new AtomicInteger(), null, null);
        try (var manager = new PredictionTileManager(Level.OVERWORLD, sampler,
                new PredictionMemoryBudget(512L * PredictionMemoryBudget.MIB, 0, () -> Long.MAX_VALUE, System::nanoTime, 1), null)) {
            var parent = new PredictionTileKey(Level.OVERWORLD, 0, 0, 1);
            var missing = new PredictionTileKey(Level.OVERWORLD, 1, 0, 1);
            ((Map<PredictionTileKey, PredictionTile>) get(manager, "ready")).put(parent, PredictionParentLodTest.tile(parent, 1, 64));
            ((Set<PredictionTileKey>) get(manager, "desiredKeys")).addAll(List.of(parent, missing));
            set(manager, "scheduledPlan", List.of(parent, missing));
            set(manager, "terrainTargets", Map.of(parent, 64, missing, 64));
            invoke(manager, "markDerivedDirty", new Class[]{PredictionTileKey.class}, parent);
            assertFalse((boolean) invoke(manager, "urgentDirty", new Class[]{PredictionTileKey.class}, parent));
            int refreshPriority = (int) invoke(manager, "workPriority", new Class[]{PredictionTileKey.class, boolean.class}, parent, false);
            int missingPriority = (int) invoke(manager, "workPriority", new Class[]{PredictionTileKey.class, boolean.class}, missing, false);
            assertTrue(missingPriority < refreshPriority);
            var input = (PredictionSchedulePreparation.Input) invoke(manager, "schedulingInput", new Class[]{long.class}, System.nanoTime());
            var work = PredictionSchedulePreparation.build(input).work();
            assertEquals(missing, work.get(0).key(), "worker preparation must apply the same priority as live admission");
            assertTrue(work.stream().anyMatch(r -> r.key().equals(parent)), "covered refresh must remain scheduled");
            invoke(manager, "markDirty", new Class[]{PredictionTileKey.class}, parent);
            invoke(manager, "markDerivedDirty", new Class[]{PredictionTileKey.class}, parent);
            assertTrue((boolean) invoke(manager, "urgentDirty", new Class[]{PredictionTileKey.class}, parent), "child update must not downgrade real capture");
            input = (PredictionSchedulePreparation.Input) invoke(manager, "schedulingInput", new Class[]{long.class}, System.nanoTime());
            assertEquals(parent, PredictionSchedulePreparation.build(input).work().get(0).key());
        }
    }

    private static Object get(Object owner, String name) throws Exception {
        var field = PredictionTileManager.class.getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static void set(Object owner, String name, Object value) throws Exception {
        var field = PredictionTileManager.class.getDeclaredField(name); field.setAccessible(true); field.set(owner, value);
    }
    private static Object invoke(Object owner, String name, Class<?>[] types, Object... args) throws Exception {
        var method = PredictionTileManager.class.getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(owner, args);
    }
}
