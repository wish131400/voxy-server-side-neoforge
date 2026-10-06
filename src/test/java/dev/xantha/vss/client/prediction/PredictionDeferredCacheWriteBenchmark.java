package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Compares the previous eager staging sequence with coalescing, using the identical production encoder. */
class PredictionDeferredCacheWriteBenchmark {
    @TempDir Path directory;
    record Timing(double callerMs, double totalMs, double cpuMs, byte[] finalBytes) { }
    @org.junit.jupiter.api.AfterEach void awaitBackgroundClose() throws Exception {
        PredictionCacheTestFiles.awaitBackgroundClose();
    }

    @Test void compareFourRefinementStagesAndVerifyIdenticalFinalPayload() throws Exception {
        assumeTrue("true".equals(System.getenv("VSS_DEFERRED_CACHE_BENCH")));
        ClientTerrainSamplerTest.bootstrapMinecraft();
        var baselineCaller = new ArrayList<Double>();
        var candidateCaller = new ArrayList<Double>();
        var baselineTotal = new ArrayList<Double>();
        var candidateTotal = new ArrayList<Double>();
        var baselineCpu = new ArrayList<Double>();
        var candidateCpu = new ArrayList<Double>();
        for (int round = 0; round < 11; round++) {
            Timing old, current;
            if ((round & 1) == 0) {
                old = measure(directory.resolve("old-" + round), false);
                current = measure(directory.resolve("new-" + round), true);
            } else {
                current = measure(directory.resolve("new-" + round), true);
                old = measure(directory.resolve("old-" + round), false);
            }
            assertArrayEquals(old.finalBytes, current.finalBytes);
            if (round >= 3) {
                baselineCaller.add(old.callerMs); candidateCaller.add(current.callerMs);
                baselineTotal.add(old.totalMs); candidateTotal.add(current.totalMs);
                baselineCpu.add(old.cpuMs); candidateCpu.add(current.cpuMs);
            }
        }
        System.out.println("deferred-cache 16 tiles, four stages each: eager encodes=64, coalesced encodes=16, identical final compressed bytes");
        System.out.printf(java.util.Locale.ROOT,
                "median callerMs %.3f -> %.3f; totalMs %.3f -> %.3f; caller+disk cpuMs %.3f -> %.3f%n",
                median(baselineCaller), median(candidateCaller), median(baselineTotal), median(candidateTotal),
                median(baselineCpu), median(candidateCpu));
        System.out.println("This fixture gates disk admission to test obsolete queued stages; unrelated keys or already encoding stages do not coalesce.");
    }

    private static Timing measure(Path root, boolean deferred) throws Exception {
        var key = PredictionDiskCache.Key.terrain(0, 0, 0);
        var leases = new ArrayList<PredictionDiskCache.Lease>();
        try (var cache = new PredictionDiskCache(root, 77); var gate = new PredictionDeferredCacheWriteTest.DiskGate()) {
            var fixtures = new ArrayList<PredictionDiskCache.TerrainData>();
            for (int axis : new int[]{8, 16, 32, 64}) fixtures.add(fixture(axis));
            ThreadMXBean threads = ManagementFactory.getThreadMXBean();
            if (threads.isThreadCpuTimeSupported() && !threads.isThreadCpuTimeEnabled()) threads.setThreadCpuTimeEnabled(true);
            long diskId = diskThreadId(threads), callerId = Thread.currentThread().getId();
            long callerCpu = threads.getThreadCpuTime(callerId), diskCpu = threads.getThreadCpuTime(diskId);
            var writes = new ArrayList<CompletableFuture<Boolean>>();
            long started = System.nanoTime();
            for (int x = 0; x < 16; x++) for (var terrain : fixtures) {
                    var lease = cache.lease(PredictionDiskCache.Key.terrain(x, 0, 0));
                    leases.add(lease);
                    writes.add(deferred ? cache.writeTerrainLater(lease, terrain) : stageOldSequence(cache, lease, terrain));
                }
            double callerMs = (System.nanoTime() - started) / 1e6;
            gate.release();
            for (int index = 0; index < writes.size(); index++)
                assertEquals(!deferred || index % 4 == 3, writes.get(index).get(10, TimeUnit.SECONDS));
            cache.flush();
            double totalMs = (System.nanoTime() - started) / 1e6;
            double cpuMs = ((threads.getThreadCpuTime(callerId) - callerCpu)
                    + (threads.getThreadCpuTime(diskId) - diskCpu)) / 1e6;
            byte[] bytes = PredictionCacheTestFiles.read(cache, key);
            for (var lease : leases) lease.close();
            return new Timing(callerMs, totalMs, cpuMs, bytes);
        } finally { for (var lease : leases) lease.close(); }
    }

    private static PredictionDiskCache.TerrainData fixture(int axis) {
        var result = PredictionDeferredCacheWriteTest.terrain(axis, 64);
        for (int index = 0; index < result.samples().length; index++) {
            result.samples()[index] = PredictionDeferredCacheWriteTest.column(64 + index % 13);
            result.surfaceTints()[index] += index % 7;
        }
        return result;
    }

    private static CompletableFuture<Boolean> stageOldSequence(PredictionDiskCache cache,
            PredictionDiskCache.Lease lease, PredictionDiskCache.TerrainData terrain) throws Exception {
        Method encoder = PredictionDiskCache.class.getDeclaredMethod("terrainEncoder", PredictionDiskCache.TerrainData.class);
        encoder.setAccessible(true);
        Object payload = encoder.invoke(cache, terrain);
        Method stage = null;
        for (Method method : PredictionDiskCache.class.getDeclaredMethods()) if (method.getName().equals("encodeTemporary")) stage = method;
        assertNotNull(stage);
        stage.setAccessible(true);
        Path temporary = (Path) stage.invoke(cache, lease, 2, payload);
        Method commit = PredictionDiskCache.class.getDeclaredMethod("commit", PredictionDiskCache.Lease.class, Path.class);
        commit.setAccessible(true);
        var field = PredictionDiskCache.class.getDeclaredField("COMMITS"); field.setAccessible(true);
        var result = new CompletableFuture<Boolean>();
        ((ExecutorService) field.get(null)).execute(() -> {
            try { result.complete((boolean) commit.invoke(cache, lease, temporary)); }
            catch (Exception failure) { result.completeExceptionally(failure); }
            finally { try { Files.deleteIfExists(temporary); } catch (Exception failure) { throw new RuntimeException(failure); } }
        });
        return result;
    }

    private static long diskThreadId(ThreadMXBean threads) {
        for (long id : threads.getAllThreadIds()) {
            var info = threads.getThreadInfo(id);
            if (info != null && info.getThreadName().equals("vss-prediction-disk")) return id;
        }
        throw new IllegalStateException("disk worker is missing");
    }

    private static double median(List<Double> values) {
        Collections.sort(values);
        return (values.get(3) + values.get(4)) / 2;
    }
}
