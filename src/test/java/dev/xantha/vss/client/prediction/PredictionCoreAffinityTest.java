package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.config.VSSClientConfig;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class PredictionCoreAffinityTest {
    private static List<PredictionCoreAffinity.Cpu> hybrid() {
        var cpus = new ArrayList<PredictionCoreAffinity.Cpu>();
        for (int core = 0; core < 8; core++) for (int smt = 0; smt < 2; smt++)
            cpus.add(new PredictionCoreAffinity.Cpu(256 + core * 2 + smt, 0, core * 2 + smt, core, 8));
        for (int core = 8; core < 24; core++)
            cpus.add(new PredictionCoreAffinity.Cpu(256 + core + 8, 0, core + 8, core, 0));
        return cpus;
    }

    @Test void reserveExcludesBothSmtSiblingsAndRetainsAllEfficientCores() {
        var selection = PredictionCoreAffinity.select(hybrid(), PredictionCoreAffinity.Mode.RESERVE, 2);
        assertTrue(selection.available()); assertEquals(2, selection.reserved());
        assertEquals(28, selection.cpus().size());
        assertTrue(selection.cpus().stream().noneMatch(c -> c.core() < 2));
        assertEquals(16, selection.cpus().stream().filter(c -> c.efficiency() == 0).count());
    }

    @Test void performanceAndEfficiencySelectionsAreDisjointAndDoNotGuessByLogicalIndex() {
        var p = PredictionCoreAffinity.select(hybrid(), PredictionCoreAffinity.Mode.PERFORMANCE, 2);
        var e = PredictionCoreAffinity.select(hybrid(), PredictionCoreAffinity.Mode.EFFICIENCY, 2);
        assertEquals(12, p.cpus().size()); assertEquals(16, e.cpus().size());
        assertTrue(p.cpus().stream().allMatch(c -> c.efficiency() == 8));
        assertTrue(e.cpus().stream().allMatch(c -> c.efficiency() == 0));
        assertEquals(16, PredictionCoreAffinity.select(hybrid(), PredictionCoreAffinity.Mode.PERFORMANCE, 0).cpus().size());
    }

    @Test void identicalCoreIndexesInDifferentProcessorGroupsAreDifferentPhysicalCores() {
        var cpus = List.of(new PredictionCoreAffinity.Cpu(1000, 0, 63, 0, 5),
                new PredictionCoreAffinity.Cpu(1001, 1, 0, 0, 5),
                new PredictionCoreAffinity.Cpu(1002, 1, 1, 1, 0));
        var selected = PredictionCoreAffinity.select(cpus, PredictionCoreAffinity.Mode.RESERVE, 1);
        assertArrayEquals(new int[]{1001, 1002}, selected.ids());
    }

    @Test void uniformSingleCoreAndExcessiveReservationsKeepUsableWorkers() {
        var uniform = hybrid().stream().filter(c -> c.efficiency() == 8).toList();
        assertFalse(PredictionCoreAffinity.select(uniform, PredictionCoreAffinity.Mode.EFFICIENCY, 2).available());
        var selected = PredictionCoreAffinity.select(uniform, PredictionCoreAffinity.Mode.RESERVE, 999);
        assertEquals(7, selected.reserved()); assertEquals(2, selected.cpus().size());
        var single = List.of(uniform.get(0));
        assertEquals(single, PredictionCoreAffinity.select(single, PredictionCoreAffinity.Mode.RESERVE, 2).cpus());
        assertFalse(PredictionCoreAffinity.select(List.of(), PredictionCoreAffinity.Mode.PERFORMANCE, 2).available());
    }

    @Test void policyParsingPreservesDefaultSchedulingForUnknownInput() {
        assertEquals(PredictionCoreAffinity.Mode.SYSTEM, PredictionCoreAffinity.Mode.fromName(null));
        assertEquals(PredictionCoreAffinity.Mode.SYSTEM, PredictionCoreAffinity.Mode.fromName("invalid"));
        assertEquals(PredictionCoreAffinity.Mode.EFFICIENCY, PredictionCoreAffinity.Mode.fromName("EFFICIENCY"));
    }

    @Test void liveWindowsWorkerAppliesAndRestoresSetsWithoutChangingCallingThread() throws Exception {
        if (!System.getProperty("os.name", "").startsWith("Windows")) return;
        var access = new PredictionWindowsCpuSets();
        var originalCaller = access.selected();
        var config = VSSClientConfig.CONFIG;
        String originalMode = config.predictionCoreMode; int originalReserve = config.predictionReservedCores;
        config.predictionCoreMode = "reserve"; config.predictionReservedCores = 2;
        var originalWorker = new java.util.concurrent.atomic.AtomicReference<int[]>();
        var pool = new PredictionWorkerPool(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task ->
                PredictionCoreAffinity.newWorker(() -> {
                    originalWorker.set(access.selected()); task.run();
                }, "vss-core-selection-test"));
        try {
            PredictionCoreAffinity.applyCurrentWorker();
            assertArrayEquals(originalCaller, access.selected(), "ordinary/game threads must be untouched");
            pool.submit(() -> {
                var expected = PredictionCoreAffinity.select(access.cpus(), PredictionCoreAffinity.Mode.RESERVE, 2);
                int[] expectedIds = expected.ids();
                if (originalWorker.get().length > 0) expectedIds = Arrays.stream(expectedIds)
                        .filter(id -> Arrays.stream(originalWorker.get()).anyMatch(old -> old == id)).toArray();
                int[] actual = access.selected(); Arrays.sort(expectedIds); Arrays.sort(actual);
                assertArrayEquals(expectedIds, actual);
                assertEquals(Math.min(1024, expected.cpus().size()), PredictionCoreAffinity.capWorkers(1024));
            }).get(15, TimeUnit.SECONDS);
            config.predictionCoreMode = "system";
            pool.submit(() -> assertArrayEquals(originalWorker.get(), access.selected())).get(15, TimeUnit.SECONDS);
            assertArrayEquals(originalCaller, access.selected());
            assertFalse(Boolean.TRUE.equals(PredictionCoreAffinity.diagnostics().get("applyFailed")));
        } finally {
            pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
            config.predictionCoreMode = originalMode; config.predictionReservedCores = originalReserve;
        }
    }
}
