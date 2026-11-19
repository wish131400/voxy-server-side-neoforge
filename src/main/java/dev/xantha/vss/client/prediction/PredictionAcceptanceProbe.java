package dev.xantha.vss.client.prediction;

import com.google.gson.GsonBuilder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** One bounded diagnostic, excluded from performance intervals by the acceptance runner. */
public final class PredictionAcceptanceProbe {
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "vss-acceptance-probe");
        thread.setDaemon(true); thread.setPriority(Thread.MIN_PRIORITY); return thread;
    });
    private PredictionAcceptanceProbe() { }
    public static boolean enabled() { return PredictionAcceptanceSettings.enabled(); }

    /** Explicit acceptance phase transition, equivalent to the normal in-memory options. */
    public static int mode(String value) {
        if (!enabled()) throw new IllegalStateException("Acceptance process was not explicitly enabled");
        if (!Set.of("loading", "resident", "prediction-off").contains(value))
            throw new IllegalArgumentException("Unknown acceptance phase mode");
        var config = dev.xantha.vss.config.VSSClientConfig.CONFIG;
        config.enablePrediction = !value.equals("prediction-off");
        config.enablePredictionLoading = value.equals("loading");
        return 1;
    }

    /** Client-thread entry. Only already-loaded real chunks are read, without scheduling generation. */
    public static CompletableFuture<Path> request(String id, int x, int z, int side, int step) {
        PredictionAcceptanceSettings.validateProbe(id, side, step);
        var mc = Minecraft.getInstance(); var level = mc.level;
        if (level == null || mc.player == null) throw new IllegalStateException("No active world");
        if (Math.abs((long)x - mc.player.getBlockX()) > 32768 || Math.abs((long)z - mc.player.getBlockZ()) > 32768)
            throw new IllegalArgumentException("Probe origin exceeds the 32768-block diagnostic radius");
        var manager = ClientPredictionState.acceptanceManager(level.dimension());
        if (manager == null || !manager.generationReady()) throw new IllegalStateException("Prediction generation is not ready");
        var sampler = manager.sampler(); var profile = sampler.profile();
        if (profile.height() > 4096) throw new IllegalArgumentException("Diagnostic height exceeds 4096 blocks");
        Path output = mc.gameDirectory.toPath().resolve("debug/vss-acceptance")
                .resolve(System.getProperty("vss.acceptance.runId")).resolve(id + ".json");
        if (Files.exists(output)) throw new IllegalArgumentException("Probe ID was already used");
        if (!BUSY.compareAndSet(false, true)) throw new IllegalStateException("An acceptance probe is already running");
        try {
            var rows = new ArrayList<Map<String, Object>>();
            var cursor = new BlockPos.MutableBlockPos();
            var snapshot = manager.renderSnapshot();
            for (int dz = 0; dz < side; dz++) for (int dx = 0; dx < side; dx++) {
                int bx = Math.addExact(x, dx * step), bz = Math.addExact(z, dz * step);
                var row = new LinkedHashMap<String, Object>(); row.put("x", bx); row.put("z", bz);
                var chunk = level.getChunkSource().getChunk(bx >> 4, bz >> 4, ChunkStatus.FULL, false);
                row.put("realLoaded", chunk != null);
                if (chunk != null) {
                    for (int y = profile.minY() + profile.height() - 1; y >= profile.minY(); y--) {
                        var state = chunk.getBlockState(cursor.set(bx, y, bz));
                        if (state.is(BlockTags.LEAVES) && !row.containsKey("realCanopyY")) {
                            row.put("realCanopyY", y + 1);
                            row.put("realCanopyBlock", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
                        }
                        if (state.isAir() || !state.getFluidState().isEmpty() || state.is(BlockTags.LOGS)
                                || state.is(BlockTags.LEAVES) || !state.canOcclude()) continue;
                        row.put("realSurfaceY", y + 1);
                        row.put("realBlock", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
                        row.put("realBiome", chunk.getNoiseBiome(bx >> 2, y >> 2, bz >> 2).unwrapKey()
                                .map(key -> key.location().toString()).orElse("inline"));
                        break;
                    }
                }
                var tile = snapshot.coveringTile(bx >> 4, bz >> 4, 0);
                var featureTile = snapshot.coveringFeatureTileAtDetail(bx >> 4, bz >> 4, 0);
                if (featureTile != null) {
                    var voxels = featureTile.data().voxyTile;
                    int vx = bx - voxels.baseX(), vz = bz - voxels.baseZ();
                    int canopyY = Integer.MIN_VALUE;
                    for (int index : voxels.featureIndex().intersect(vx, profile.minY(), vz,
                            vx + 1, profile.minY() + profile.height(), vz + 1)) {
                        var box = voxels.features().get(index);
                        if (box.maxY() <= canopyY || !box.state().is(BlockTags.LEAVES)) continue;
                        canopyY = box.maxY();
                        row.put("residentCanopyY", canopyY);
                        row.put("residentCanopyBlock", BuiltInRegistries.BLOCK.getKey(box.state().getBlock()).toString());
                    }
                    row.put("residentDecorationComplete", voxels.surfaceComplete());
                    row.put("residentDecorationStep", voxels.decorationStep());
                }
                if (tile != null && tile.samples().length == (tile.cellAxis() + 1) * (tile.cellAxis() + 1)) {
                    int sx = Math.floorDiv(bx - tile.baseBlockX(), tile.spacingBlocks());
                    int sz = Math.floorDiv(bz - tile.baseBlockZ(), tile.spacingBlocks());
                    if (sx >= 0 && sz >= 0 && sx <= tile.cellAxis() && sz <= tile.cellAxis()) {
                        var stored = tile.samples()[sz * (tile.cellAxis() + 1) + sx];
                        if (stored != null) row.put("resident", Map.of("x", tile.baseBlockX() + sx * tile.spacingBlocks(),
                                "z", tile.baseBlockZ() + sz * tile.spacingBlocks(), "step", tile.spacingBlocks(),
                                "lod", tile.key().lod(), "surfaceY", stored.surfaceY(), "flags", stored.flags(),
                                "captured", stored.captured(), "approximate", stored.approximate()));
                    }
                }
                rows.add(row);
            }
            return CompletableFuture.supplyAsync(() -> {
                long started = System.nanoTime();
                var report = new LinkedHashMap<String, Object>();
                report.put("schema", 1); report.put("requestId", id); report.put("time", Instant.now().toString());
                report.put("acceptance", PredictionAcceptanceSettings.metadata());
                report.put("dimension", profile.dimension().toString()); report.put("seed", profile.seed());
                report.put("profileFingerprint", profile.fingerprint()); report.put("backend", sampler.predictionBackendDiagnostics());
                report.put("referenceKind", "loaded-client-blocks; highest occluding solid excluding leaves/logs; structures may affect it");
                report.put("canopyKind", "highest visible leaf in the column; resident decoration may be approximate or pending");
                report.put("farDecorationCatalog", manager.acceptanceFarDecorationCatalog());
                try (var scope = sampler.terrainRequest()) {
                    for (var row : rows) {
                        if (Thread.currentThread().isInterrupted() || manager.sampler() != sampler)
                            throw new CancellationException("Prediction context changed");
                        int bx = (int) row.get("x"), bz = (int) row.get("z");
                        try {
                            var preview = sampler.samplePreview(bx, bz);
                            var exact = sampler.sampleSurface(bx, bz);
                            row.put("previewSurfaceY", preview.surfaceY()); row.put("predictedSurfaceY", exact.surfaceY());
                            row.put("predictedBlock", BuiltInRegistries.BLOCK.getKey(BuiltInRegistries.BLOCK.byId(exact.topBlockIndex())).toString());
                            int y = ((Number) row.getOrDefault("realSurfaceY", exact.surfaceY())).intValue() - 1;
                            var biome = sampler.decorationContext().noiseBiome(bx >> 2, y >> 2, bz >> 2);
                            if (biome == null) row.put("biomeUnavailable", true);
                            else row.put("predictedBiome", biome.unwrapKey()
                                    .map(key -> key.location().toString()).orElse("inline"));
                        } catch (CancellationException cancelled) { throw cancelled; }
                        catch (RuntimeException failure) { row.put("error", failure.getClass().getSimpleName()); }
                    }
                    report.put("status", "complete");
                } catch (RuntimeException failure) { report.put("status", "incomplete"); report.put("error", failure.getClass().getSimpleName()); }
                report.put("points", rows); report.put("durationMs", (System.nanoTime() - started) / 1e6);
                try {
                    // Confirm the same live world and sampler on their owning thread before committing evidence.
                    var valid = new CompletableFuture<Boolean>();
                    mc.execute(() -> valid.complete(mc.level == level && manager.sampler() == sampler));
                    if (!valid.get(5, TimeUnit.SECONDS)) report.put("status", "stale-world");
                    Files.createDirectories(output.getParent());
                    Path temporary = Files.createTempFile(output.getParent(), ".probe-", ".tmp");
                    try {
                        Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(report), StandardCharsets.UTF_8);
                        try { Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE); }
                        catch (AtomicMoveNotSupportedException unavailable) { Files.move(temporary, output); }
                    } finally { Files.deleteIfExists(temporary); }
                    return output;
                } catch (Exception failure) { throw new CompletionException(failure); }
            }, WORKER).whenComplete((path, failure) -> BUSY.set(false));
        } catch (RuntimeException | Error failure) { BUSY.set(false); throw failure; }
    }
}
