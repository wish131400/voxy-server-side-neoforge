package dev.xantha.vss.client.prediction;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Loopback read-only snapshot service. Never calls Minecraft, GL, scheduling or workload setters. */
final class PredictionPerformanceService implements AutoCloseable {
    private static final Gson JSON = new Gson();
    private final ScheduledExecutorService control = Executors.newSingleThreadScheduledExecutor(task -> {
        var thread = new Thread(task, "vss-performance-service"); thread.setDaemon(true); return thread;
    });
    private volatile Map<String, Object> document = Map.of("capturing", false, "samples", List.of());
    private volatile Map<String, Object> system = Map.of();
    private volatile String status = "stopped";
    private volatile long publishedAt = System.nanoTime();
    private HttpServer server;
    private ThreadPoolExecutor readers;
    private ScheduledFuture<?> metrics;
    private Path discovery;
    private volatile String token;
    private Map<Long, Long> previousThreadCpu = Map.of();
    private long previousThreadSample;

    Map<String, Object> system() { return system; }
    Map<String, Object> reportSnapshot() { return document; }
    String status() { return status; }
    void publish(Map<String, Object> snapshot) { document = snapshot; publishedAt = System.nanoTime(); }

    CompletableFuture<Path> start(Path directory) {
        status = "starting";
        var result = new CompletableFuture<Path>();
        control.execute(() -> {
            try {
                stopInternal();
                token = UUID.randomUUID().toString();
                server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 8);
                readers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
                        new java.util.concurrent.ArrayBlockingQueue<>(16), task -> {
                    var thread = new Thread(task, "vss-performance-reader"); thread.setDaemon(true); return thread;
                });
                server.setExecutor(readers); server.createContext("/", this::serve); server.start();
                Files.createDirectories(directory); discovery = directory.resolve("session.json");
                var session = Map.of("schema", 1, "host", "127.0.0.1", "port", server.getAddress().getPort(),
                        "token", token, "pid", ProcessHandle.current().pid(), "started", java.time.Instant.now().toString());
                Path temporary = Files.createTempFile(directory, "session-", ".tmp");
                try {
                    Files.writeString(temporary, JSON.toJson(session), StandardCharsets.UTF_8);
                    Files.move(temporary, discovery, StandardCopyOption.REPLACE_EXISTING);
                } finally { Files.deleteIfExists(temporary); }
                metrics = control.scheduleWithFixedDelay(this::sampleSystem, 0, 1, TimeUnit.SECONDS);
                status = "ready"; result.complete(discovery);
            } catch (Exception | LinkageError failure) {
                stopInternal(); status = "failed: " + failure.getClass().getSimpleName();
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    void stop() { status = "stopping"; control.execute(() -> { stopInternal(); status = "stopped"; }); }
    private void stopInternal() {
        if (metrics != null) { metrics.cancel(false); metrics = null; }
        if (server != null) { server.stop(0); server = null; }
        if (readers != null) { readers.shutdownNow(); readers = null; }
        // A second game in the same directory may have replaced the discovery file.
        if (discovery != null && token != null) try {
            if (Files.exists(discovery) && token.equals(JSON.fromJson(Files.readString(discovery), com.google.gson.JsonObject.class)
                    .get("token").getAsString())) Files.deleteIfExists(discovery);
        } catch (Exception ignored) { }
        discovery = null; token = null;
        previousThreadCpu = Map.of(); previousThreadSample = 0;
    }

    private void sampleSystem() {
        try {
            var result = new java.util.LinkedHashMap<String, Object>();
            var os = ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean extended) {
                result.put("systemCpuLoad", extended.getCpuLoad()); result.put("processCpuLoad", extended.getProcessCpuLoad());
                result.put("processCpuNanos", extended.getProcessCpuTime());
            }
            long gcMillis = 0, gcCount = 0;
            for (var bean : ManagementFactory.getGarbageCollectorMXBeans()) {
                gcMillis += Math.max(0, bean.getCollectionTime()); gcCount += Math.max(0, bean.getCollectionCount());
            }
            result.put("gcMillis", gcMillis); result.put("gcCount", gcCount);
            result.put("sampledAt", java.time.Instant.now().toString());
            result.put("processors", Runtime.getRuntime().availableProcessors());
            result.put("threads", sampleThreads());
            system = Map.copyOf(result);
        } catch (RuntimeException ignored) { /* Timestamp exposes staleness of the last good sample. */ }
    }

    /** Bounded 1 Hz thread attribution includes long-running tasks before their counters finish. */
    private List<Map<String, Object>> sampleThreads() {
        var bean = ManagementFactory.getThreadMXBean();
        if (!bean.isThreadCpuTimeSupported() || !bean.isThreadCpuTimeEnabled()) return List.of();
        long now = System.nanoTime();
        double elapsed = previousThreadSample == 0 ? 0 : (now - previousThreadSample) / 1e9;
        var next = new java.util.HashMap<Long, Long>();
        var rows = new java.util.ArrayList<Map<String, Object>>();
        for (var info : bean.getThreadInfo(bean.getAllThreadIds(), 0)) {
            if (info == null) continue;
            String name = info.getThreadName();
            if (!(name.equals("Render thread") || name.equals("Client thread") || name.equals("Server thread")
                    || name.startsWith("vss-"))) continue;
            long id = info.getThreadId(), cpu = bean.getThreadCpuTime(id);
            if (cpu < 0) continue;
            next.put(id, cpu);
            Long before = previousThreadCpu.get(id);
            if (before == null || elapsed <= 0) continue;
            var row = new java.util.LinkedHashMap<String, Object>();
            row.put("name", name); row.put("id", id); row.put("state", info.getThreadState().name());
            row.put("cpuCores", Math.max(0, cpu - before) / (elapsed * 1e9));
            rows.add(row);
        }
        rows.sort(java.util.Comparator.comparingDouble(row -> -((Number) row.get("cpuCores")).doubleValue()));
        // At most 12 short stack samples per second. A sample is a location clue,
        // not a statistical CPU profile or a proof that the sampled method is hot.
        for (var row : rows.subList(0, Math.min(12, rows.size()))) {
            var info = bean.getThreadInfo(((Number) row.get("id")).longValue(), 12);
            if (info != null) {
                row.put("stack", java.util.Arrays.stream(info.getStackTrace()).map(Object::toString).toList());
                row.put("lock", info.getLockName() == null ? "" : info.getLockName());
            }
        }
        previousThreadCpu = next; previousThreadSample = now;
        return rows.stream().limit(12).map(Map::copyOf).toList();
    }

    @SuppressWarnings("unchecked")
    private void serve(HttpExchange request) throws IOException {
        try {
            if (!"GET".equals(request.getRequestMethod())) { respond(request, 405, Map.of("error", "read-only")); return; }
            if (!("Bearer " + token).equals(request.getRequestHeaders().getFirst("Authorization"))) {
                respond(request, 401, Map.of("error", "unauthorized")); return;
            }
            Map<String, Object> snapshot = document; Object payload;
            switch (request.getRequestURI().getPath()) {
                case "/status" -> payload = Map.of("capturing", snapshot.getOrDefault("capturing", false),
                        "sampling", snapshot.getOrDefault("sampling", false), "latest", snapshot.getOrDefault("latest", Map.of()),
                        "snapshotAgeSeconds", Math.max(0, (System.nanoTime() - publishedAt) / 1e9));
                case "/hotspots" -> payload = Map.of("latest", snapshot.getOrDefault("latest", Map.of()),
                        "summary", snapshot.getOrDefault("summary", List.of()));
                case "/report" -> payload = snapshot;
                case "/samples" -> {
                    int limit = 60; String query = request.getRequestURI().getRawQuery();
                    if (query != null) {
                        if (!query.matches("limit=[0-9]{1,3}")) { respond(request, 400, Map.of("error", "limit must be 1..600")); return; }
                        limit = Integer.parseInt(query.substring(6));
                        if (limit < 1 || limit > 600) { respond(request, 400, Map.of("error", "limit must be 1..600")); return; }
                    }
                    var rows = (List<Map<String, Object>>) snapshot.getOrDefault("samples", List.of());
                    payload = Map.of("samples", rows.subList(Math.max(0, rows.size() - limit), rows.size()),
                            "capturing", snapshot.getOrDefault("capturing", false));
                }
                default -> { respond(request, 404, Map.of("error", "unknown query")); return; }
            }
            respond(request, 200, payload);
        } finally { request.close(); }
    }

    private static void respond(HttpExchange exchange, int status, Object payload) throws IOException {
        byte[] bytes = JSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store"); exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    /** Test/process shutdown only; normal capture stop keeps the reusable daemon controller. */
    @Override public void close() { control.execute(this::stopInternal); control.shutdown(); }
}
