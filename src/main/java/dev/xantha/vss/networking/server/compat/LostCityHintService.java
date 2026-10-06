package dev.xantha.vss.networking.server.compat;

import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.common.worldgen.LostCityPreview;
import dev.xantha.vss.config.VSSServerConfig;
import dev.xantha.vss.networking.VSSNetworking;
import dev.xantha.vss.networking.payloads.LostCityHintsC2SPayload;
import dev.xantha.vss.networking.payloads.LostCityHintsS2CPayload;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;

/** Queries Lost Cities planning data without asking Minecraft to generate chunks. */
public final class LostCityHintService {
    private static final int MAX_DISTANCE_CHUNKS = 256;
    private static final int MAX_QUEUED = 8;
    private static final Map<UUID, Long> LAST_REQUEST = new java.util.concurrent.ConcurrentHashMap<>();
    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicLong LIFECYCLE = new java.util.concurrent.atomic.AtomicLong();
    private record Region(net.minecraft.resources.ResourceKey<Level> dimension, int x, int z) { }
    private static final Map<Region, List<LostCityPreview.Chunk>> SUMMARIES = new java.util.LinkedHashMap<>(64, .75F, true);
    private static long summaryBytes;
    private static volatile ThreadPoolExecutor executor;
    private static volatile boolean loggedFailure;

    private LostCityHintService() { }

    public static void handle(ServerPlayer player, LostCityHintsC2SPayload request) {
        if (!VSSServerConfig.CONFIG.enabled || !VSSServerConfig.CONFIG.enablePredictionSync
                || !player.serverLevel().dimension().location().equals(request.dimension())) return;
        long x = (long) request.regionX() * LostCityHintsS2CPayload.REGION_CHUNKS + 4;
        long z = (long) request.regionZ() * LostCityHintsS2CPayload.REGION_CHUNKS + 4;
        if (Math.abs(x - Math.floorDiv(player.getBlockX(), 16)) > MAX_DISTANCE_CHUNKS
                || Math.abs(z - Math.floorDiv(player.getBlockZ(), 16)) > MAX_DISTANCE_CHUNKS) return;
        long now = System.nanoTime();
        UUID playerId = player.getUUID();
        Long last = LAST_REQUEST.get(playerId);
        if (last != null && now - last < 150_000_000L) return;
        LAST_REQUEST.put(playerId, now);
        if (!ModList.get().isLoaded("lostcities")) {
            send(player, request, false, List.of());
            return;
        }
        Object info;
        try {
            LostCityPlannerAccess.lock(LostCityHintService.class.getClassLoader(), player.serverLevel().dimension());
            Class<?> mod = Class.forName("mcjty.lostcities.LostCities");
            Object api = mod.getField("lostCitiesImp").get(null);
            info = api.getClass().getMethod("getLostInfo", Level.class).invoke(api, player.serverLevel());
        } catch (ReflectiveOperationException | RuntimeException failure) {
            logFailure(failure);
            send(player, request, false, List.of());
            return;
        }
        if (info == null) {
            send(player, request, false, List.of());
            return;
        }
        Region region = new Region(player.serverLevel().dimension(), request.regionX(), request.regionZ());
        synchronized (SUMMARIES) {
            List<LostCityPreview.Chunk> cached = SUMMARIES.get(region);
            if (cached != null) { send(player, request, true, cached); return; }
        }
        if (IN_FLIGHT.incrementAndGet() > MAX_QUEUED) {
            IN_FLIGHT.decrementAndGet();
            return;
        }
        ServerLevel level = player.serverLevel();
        long lifecycle = LIFECYCLE.get();
        try {
            executor().execute(() -> {
                try {
                    List<LostCityPreview.Chunk> chunks = LostCityPlanningReader.query(info, level, request.regionX(), request.regionZ());
                    synchronized (SUMMARIES) {
                        if (lifecycle != LIFECYCLE.get()) return;
                        List<LostCityPreview.Chunk> old = SUMMARIES.put(region, chunks);
                        summaryBytes += LostCityPreview.retainedBytes(chunks) - (old == null ? 0 : LostCityPreview.retainedBytes(old));
                        while (SUMMARIES.size() > 128 || summaryBytes > 32L * 1024 * 1024) {
                            var iterator = SUMMARIES.values().iterator();
                            summaryBytes -= LostCityPreview.retainedBytes(iterator.next()); iterator.remove();
                        }
                    }
                    level.getServer().execute(() -> {
                        if (lifecycle == LIFECYCLE.get() && player.isAlive() && player.serverLevel() == level)
                            send(player, request, true, chunks);
                    });
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    if (lifecycle != LIFECYCLE.get() || Thread.currentThread().isInterrupted()) return;
                    logFailure(failure);
                    level.getServer().execute(() -> {
                        if (lifecycle == LIFECYCLE.get() && player.isAlive() && player.serverLevel() == level)
                            send(player, request, false, List.of());
                    });
                } finally {
                    IN_FLIGHT.updateAndGet(count -> Math.max(0, count - 1));
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            IN_FLIGHT.decrementAndGet();
        }
    }

    static List<LostCityPreview.Chunk> query(Object info, int regionX, int regionZ) throws ReflectiveOperationException {
        return LostCityPlanningReader.query(info, null, regionX, regionZ);
    }

    private static void send(ServerPlayer player, LostCityHintsC2SPayload request,
                             boolean active, List<LostCityPreview.Chunk> chunks) {
        VSSNetworking.sendToPlayer(player, new LostCityHintsS2CPayload(request.dimension(),
                request.regionX(), request.regionZ(), request.session(), active, chunks));
    }

    private static synchronized ThreadPoolExecutor executor() {
        if (executor == null || executor.isShutdown()) {
            executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(MAX_QUEUED), task -> {
                        Thread thread = new Thread(task, "VSS-LostCities-hints");
                        thread.setDaemon(true);
                        return thread;
                    });
        }
        return executor;
    }

    public static synchronized void stop() {
        LIFECYCLE.incrementAndGet();
        synchronized (SUMMARIES) { SUMMARIES.clear(); summaryBytes = 0; }
        if (executor != null) {
            int cancelled = executor.shutdownNow().size();
            IN_FLIGHT.updateAndGet(count -> Math.max(0, count - cancelled));
        }
        executor = null;
        LAST_REQUEST.clear();
        loggedFailure = false;
        LostCityPlanningReader.clear();
    }

    public static void forgetPlayer(UUID playerId) { LAST_REQUEST.remove(playerId); }

    private static void logFailure(Exception failure) {
        if (!loggedFailure) {
            loggedFailure = true;
            VSSLogger.warn("VSS Lost Cities planning unavailable; city preview query failed; client will retry", failure);
        }
    }
}
