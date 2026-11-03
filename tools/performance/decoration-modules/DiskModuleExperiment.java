package dev.xantha.vss.client.prediction;

import com.google.gson.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.*;
import net.minecraft.world.level.levelgen.feature.configurations.DiskConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/** Single-thread isolated experiment. No production source depends on this class. */
final class DiskModuleExperiment extends DiskFeature {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int[][] MASKS = loadMasks();
    private static final DiskModuleExperiment INSTANCE = new DiskModuleExperiment();
    private static final Map<String, Stats> COSTS = new LinkedHashMap<>();
    private static String mode;
    private static Stats active;
    private static long randomTail, randomChecks;

    private static final class Stats {
        long calls, successful, writes, footprintColumns, prepareNanos, totalNanos;
        long batchCalls, batchPoints, nativeNanos, fallbacks;
    }

    private DiskModuleExperiment() { super(DiskConfiguration.CODEC); }

    private static int[][] loadMasks() {
        try (var input = DiskModuleExperiment.class.getResourceAsStream("/vss-experiment/disk-masks.json")) {
            if (input == null) throw new IllegalStateException("Missing precomputed geometry resource");
            var json = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
            int[][] result = new int[7][];
            for (int radius = 2; radius <= 6; radius++) {
                var offsets = json.getAsJsonArray(Integer.toString(radius));
                result[radius] = new int[offsets.size() * 2];
                for (int i = 0; i < offsets.size(); i++) {
                    var xyz = offsets.get(i).getAsJsonArray();
                    result[radius][2*i] = xyz.get(0).getAsInt();
                    result[radius][2*i+1] = xyz.get(2).getAsInt();
                }
            }
            return result;
        } catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }

    static void begin(String selectedMode) {
        mode = selectedMode; COSTS.clear(); active = null; randomTail = 0; randomChecks = 0;
    }

    static void randomTail(long value) {
        randomTail = Long.rotateLeft(randomTail, 7) ^ value;
        randomChecks++;
    }

    static boolean tryPlace(PlacedFeature feature, PredictionDecorationLevel level,
                            ChunkGenerator generator, RandomSource random, BlockPos pos) {
        if (!(mode.equals("templates") || mode.equals("pages") || mode.equals("compact"))) return false;
        var configured = feature.feature().value();
        if (configured.feature() != Feature.DISK || !(configured.config() instanceof DiskConfiguration disk)) return false;
        String id = String.valueOf(level.registryAccess().registryOrThrow(Registries.PLACED_FEATURE).getKey(feature));
        Stats cost = COSTS.computeIfAbsent(id, ignored -> new Stats());
        if (disk.radius().getMinValue() < 2 || disk.radius().getMaxValue() > 6) {
            cost.fallbacks++; return false;
        }
        cost.calls++;
        long start = System.nanoTime();
        int before = level.featureWriteCount();
        active = cost;
        try {
            if (new ConfiguredFeature<>(INSTANCE, disk).place(level, generator, random, pos)) cost.successful++;
        } finally {
            cost.writes += level.featureWriteCount() - before;
            cost.totalNanos += System.nanoTime() - start;
            active = null;
        }
        return true;
    }

    @Override public boolean place(FeaturePlaceContext<DiskConfiguration> context) {
        var config = context.config();
        var level = (PredictionDecorationLevel) context.level();
        var origin = context.origin();
        int radius = config.radius().sample(context.random());
        int[] offsets = MASKS[radius];
        active.footprintColumns += offsets.length / 2;
        if (!mode.equals("templates")) {
            long start = System.nanoTime();
            if (!level.experimentPrepareDisk(origin, offsets, mode.equals("compact"))) active.fallbacks++;
            active.prepareNanos += System.nanoTime() - start;
        }
        boolean placed = false;
        var mutable = new BlockPos.MutableBlockPos();
        int top = origin.getY() + config.halfHeight(), bottom = origin.getY() - config.halfHeight() - 1;
        for (int i = 0; i < offsets.length; i += 2) {
            mutable.set(origin.getX() + offsets[i], origin.getY(), origin.getZ() + offsets[i+1]);
            // Vanilla column method retains descending-Y order, predicate/provider,
            // live edit overlay, random draws, setBlock and postprocessing behavior.
            placed |= placeColumn(config, level, context.random(), top, bottom, mutable);
        }
        return placed;
    }

    static void batch(int points, long nanos) {
        if (active != null) {
            active.batchCalls++; active.batchPoints += points; active.nativeNanos += nanos;
        }
    }

    static void save(Path output, String selectedMode, int round) throws Exception {
        var report = new LinkedHashMap<String, Object>();
        report.put("mode", selectedMode); report.put("round", round);
        report.put("randomTail", Long.toUnsignedString(randomTail)); report.put("randomChecks", randomChecks);
        report.put("features", COSTS);
        Files.writeString(output.resolve("module-costs-"+selectedMode+"-"+round+".json"), JSON.toJson(report));
    }
}
