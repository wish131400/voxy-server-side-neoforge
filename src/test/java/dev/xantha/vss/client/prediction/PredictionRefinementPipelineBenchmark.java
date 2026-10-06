package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.InflaterInputStream;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.*;

/** Opt-in A/B: identical terrain records and deterministic capture traffic. */
class PredictionRefinementPipelineBenchmark {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    record Fixture(PredictionDiskCache.Key key, PredictionDiskCache.TerrainData data) { }
    record Result(double workersMs, double drainedMs, double p95Ms, double cpuMs, long pendingBytes) { }

    @Test void compareCommitBeforePublicationWithBoundedAsyncCommit() throws Exception {
        String destination = System.getenv("VSS_REFINE_BENCH_OUTPUT");
        assumeTrue(destination != null);
        Path output = Path.of(destination);
        List<Fixture> fixtures = fixtures(output.resolve("input"));
        assertFalse(fixtures.isEmpty());
        long columns = fixtures.stream().mapToLong(f -> f.data.samples().length).sum();
        long volumes = fixtures.stream().flatMap(f -> Arrays.stream(f.data.samples())).filter(s -> s.volume() != null).count();
        System.out.printf("COMMIT_INPUT records=%d columns=%d volumeColumns=%d%n", fixtures.size(), columns, volumes);
        for (int busyMs : new int[]{0, 250}) {
            var sync = new ArrayList<Result>();
            var async = new ArrayList<Result>();
            for (int round = 0; round < 20; round++) {
                for (boolean candidate : round % 2 == 0 ? new boolean[]{false,true} : new boolean[]{true,false}) {
                    Result result = run(fixtures, output.resolve("busy-" + busyMs + "/round-" + round + "-" + candidate), candidate, busyMs);
                    if (round >= 8) (candidate ? async : sync).add(result);
                }
            }
            System.out.printf(Locale.ROOT,
                    "COMMIT_AB records=%d workers=8 busyMs=%d syncWorkersMedianMs=%.3f asyncWorkersMedianMs=%.3f syncP95MedianMs=%.3f asyncP95MedianMs=%.3f syncDrainedMedianMs=%.3f asyncDrainedMedianMs=%.3f syncProcessCpuMedianMs=%.3f asyncProcessCpuMedianMs=%.3f pendingAtWorkersDoneBytes=%d%n",
                    fixtures.size(), busyMs, median(sync,0), median(async,0), median(sync,2), median(async,2),
                    median(sync,1), median(async,1), median(sync,3), median(async,3), async.stream().mapToLong(Result::pendingBytes).max().orElse(0));
        }
    }

    private List<Fixture> fixtures(Path input) throws Exception {
        var result = new ArrayList<Fixture>();
        try (var paths = Files.walk(input); var storage = new PredictionRegionStorage(input)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".vpr")).sorted().toList()) {
                String[] type = input.relativize(path).getName(0).toString().split("-");
                if (!type[0].equals("0")) continue;
                String[] region = path.getFileName().toString().replace(".vpr", "").split("_");
                int rx = Integer.parseInt(region[0]), rz = Integer.parseInt(region[1]), detail = Integer.parseInt(type[1]);
                for (int z = rz * 32; z < rz * 32 + 32; z++) for (int x = rx * 32; x < rx * 32 + 32; x++) {
                    var key = PredictionDiskCache.Key.terrain(x,z,detail);
                    byte[] bytes;
                    var record = storage.read(key);
                    if (record == null) continue;
                    bytes = record.bytes();
                    long fingerprint;
                    try (var header = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(bytes)))) {
                        header.readInt(); header.readInt(); fingerprint = header.readLong();
                    }
                    // Only the copied fixture directory is opened by the writable cache API.
                    try (var cache = new PredictionDiskCache(input, fingerprint); var lease = cache.lease(key)) {
                        var data = cache.readTerrainData(lease,0);
                        assertNotNull(data);
                        result.add(new Fixture(key, data));
                        cache.flush();
                    }
                    if (result.size() == 64) return result;
                }
            }
        }
        return result;
    }

    private Result run(List<Fixture> fixtures, Path root, boolean asynchronous, int busyMs) throws Exception {
        Files.createDirectories(root);
        var pool = Executors.newFixedThreadPool(8);
        var timer = Executors.newSingleThreadScheduledExecutor();
        var times = new ConcurrentLinkedQueue<Long>();
        var persisted = new ConcurrentLinkedQueue<CompletableFuture<Boolean>>();
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        long start, cpuStart;
        double workerMs, drainedMs, processCpuMs;
        long peak = 0;
        try (var cache = new PredictionDiskCache(root,77)) {
            PredictionAsyncTerrainCacheTest.Gate gate = busyMs == 0 ? null : new PredictionAsyncTerrainCacheTest.Gate();
            if (gate != null) timer.schedule(gate.release::countDown, busyMs, TimeUnit.MILLISECONDS);
            cpuStart = os.getProcessCpuTime(); start = System.nanoTime();
            var tasks = new ArrayList<Future<?>>();
            for (var fixture : fixtures) tasks.add(pool.submit(() -> {
                long began = System.nanoTime();
                try (var lease = cache.lease(fixture.key)) {
                    if (asynchronous) persisted.add(cache.writeTerrainLater(lease, fixture.data));
                    else assertTrue(cache.writeTerrain(lease, fixture.data));
                } finally { times.add(System.nanoTime()-began); }
            }));
            for (var task : tasks) task.get(30,TimeUnit.SECONDS);
            workerMs = (System.nanoTime()-start)/1e6;
            String stats = cache.diagnostics();
            String label = "terrainPendingBytes=";
            int i = stats.indexOf(label) + label.length();
            peak = Long.parseLong(stats.substring(i, stats.indexOf(',',i)));
            for (var future : persisted) assertTrue(future.get(30,TimeUnit.SECONDS), "benchmark must not drop cache writes");
            cache.flush();
            drainedMs = (System.nanoTime()-start)/1e6;
            processCpuMs = (os.getProcessCpuTime()-cpuStart)/1e6;
            if (gate != null) gate.close();
            for (var fixture : fixtures) try (var lease = cache.lease(fixture.key)) {
                var actual = cache.readTerrainData(lease,0);
                assertNotNull(actual);
                assertArrayEquals(fixture.data.samples(),actual.samples());
                assertArrayEquals(fixture.data.surfaceTints(),actual.surfaceTints());
                assertArrayEquals(fixture.data.foliageTints(),actual.foliageTints());
                assertArrayEquals(fixture.data.waterTints(),actual.waterTints());
            }
        } finally { pool.shutdownNow(); timer.shutdownNow(); }
        var sorted = new ArrayList<>(times); Collections.sort(sorted);
        return new Result(workerMs, drainedMs, sorted.get((int)Math.ceil(sorted.size()*.95)-1)/1e6,
                processCpuMs, peak);
    }

    private static double median(List<Result> results, int field) {
        return results.stream().mapToDouble(r -> switch(field) { case 0 -> r.workersMs; case 1 -> r.drainedMs;
            case 2 -> r.p95Ms; default -> r.cpuMs; }).sorted().toArray()[results.size()/2];
    }

    @Test void replayQueuedAndInFlightCaptureBursts() {
        assumeTrue(System.getenv("VSS_REFINE_BENCH_OUTPUT") != null);
        for (int duration : new int[]{0,400,1400}) for (int queueMs : new int[]{0,180,500}) {
            long[] old = replay(false,queueMs,duration), candidate = replay(true,queueMs,duration);
            assertEquals(0,candidate[0]);
            assertTrue(candidate[2]<=old[2],"merging queued captures must not increase wasted sampling");
            assertTrue(candidate[3]<=old[3],"queue merging must not delay the final visible update");
            System.out.printf("CAPTURE_REPLAY updateDurationMs=%d queueMs=%d oldStaleBeforeWork=%d newStaleBeforeWork=%d oldBuilds=%d newBuilds=%d oldDiscardedWorkMs=%d newDiscardedWorkMs=%d oldAllCurrentAtMs=%d newAllCurrentAtMs=%d%n",
                    duration,queueMs,old[0],candidate[0],old[1],candidate[1],old[2],candidate[2],old[3],candidate[3]);
        }
    }

    private long[] replay(boolean current, int queueMs, int duration) {
        var refresh = new PredictionCaptureRefresh();
        var key = new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,0,0,0);
        long epoch=0, shown=0, queuedEpoch=0, buildEpoch=0, pick=-1, finish=-1, stale=0, builds=0, discarded=0, last=-1;
        for (int ms=0;ms<=4000;ms++) {
            long now=(ms+1)*1_000_000L;
            if (ms<=duration && ms%20==0) { epoch++; refresh.changed(key,now); }
            if (finish==ms) {
                if(buildEpoch==epoch) { shown=epoch; last=ms; } else discarded+=60;
                finish=-1;
            }
            if(pick==ms) {
                pick=-1;
                if(current) {
                    if(!refresh.defer(key,now)) { buildEpoch=epoch; builds++; finish=ms+60; }
                } else if(queuedEpoch!=epoch) stale++;
                else { buildEpoch=queuedEpoch; builds++; finish=ms+60; }
            }
            if(ms%10==0 && shown!=epoch && pick<0 && finish<0 && !refresh.defer(key,now)) {
                queuedEpoch=epoch;
                refresh.started(key);
                if(queueMs==0) {
                    buildEpoch=epoch; builds++; finish=ms+60;
                } else pick=ms+queueMs;
            }
        }
        assertEquals(epoch,shown,"all policies must display the final capture state");
        assertTrue(last<=duration+queueMs+600,"continuous updates cannot starve refresh");
        return new long[]{stale,builds,discarded,last};
    }
}
