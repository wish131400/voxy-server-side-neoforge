package dev.xantha.vss.client.prediction;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/** Isolated full-path attribution. This class is copied only into benchmark snapshots. */
final class ModernFeatureProbe {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, Cost> COSTS = new LinkedHashMap<>();
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private static final class Cost {
        String id, type, modifiers;
        long calls, nanos, journalWrites, withoutWrites, withoutWriteNanos;
        long javaCalls, javaNanos, nativeCalls, nativeNanos, treeCalls, treeNanos;
        long exactCalls, exactPoints, exactNanos, displayCalls, displayPoints, displayNanos;
        long biomeCalls, biomeNanos, nativeBiomeCalls, nativeBiomePoints, nativeBiomeNanos;
    }

    static final class Scope implements AutoCloseable {
        private final Cost cost;
        private final long start = System.nanoTime();
        private long writes;
        private String route = "precheck";
        private Scope(Cost cost) { this.cost = cost; }
        @Override public void close() {
            long elapsed = System.nanoTime() - start;
            cost.calls++; cost.nanos += elapsed; cost.journalWrites += writes;
            if (writes == 0) { cost.withoutWrites++; cost.withoutWriteNanos += elapsed; }
            switch (route) {
                case "native" -> { cost.nativeCalls++; cost.nativeNanos += elapsed; }
                case "java" -> { cost.javaCalls++; cost.javaNanos += elapsed; }
                case "tree" -> { cost.treeCalls++; cost.treeNanos += elapsed; }
            }
            CURRENT.remove();
        }
    }

    static Scope begin(String id, String type, String modifiers) {
        if (CURRENT.get() != null) throw new IllegalStateException("Overlapping top-level feature probes");
        Cost cost = COSTS.computeIfAbsent(id, ignored -> {
            var value = new Cost(); value.id = id; value.type = type; value.modifiers = modifiers; return value;
        });
        var scope = new Scope(cost); CURRENT.set(scope); return scope;
    }

    static Scope feature(ClientTerrainSampler context, PlacedFeature feature) {
        var id = context.decorationAccess().lookupOrThrow(Registries.PLACED_FEATURE).getKey(feature);
        return begin(String.valueOf(id), feature.feature().value().getClass().getSimpleName(),
                feature.placement().stream().map(p -> p.getClass().getSimpleName()).toList().toString());
    }

    static void route(String route) { var scope = CURRENT.get(); if (scope != null) scope.route = route; }
    static void write() { var scope = CURRENT.get(); if (scope != null) scope.writes++; }

    static int surface(long handle, ByteBuffer input, ByteBuffer output, int count) {
        var scope = CURRENT.get();
        if (scope == null) return RustWorldgenBackend.surfacePoints(handle, input, output, count);
        long start = System.nanoTime();
        try { return RustWorldgenBackend.surfacePoints(handle, input, output, count); }
        finally { scope.cost.exactCalls++; scope.cost.exactPoints += count; scope.cost.exactNanos += System.nanoTime() - start; }
    }

    static int display(long handle, ByteBuffer input, ByteBuffer output, int count) {
        var scope = CURRENT.get();
        if (scope == null) return RustWorldgenBackend.decorationPoints(handle, input, output, count);
        long start = System.nanoTime();
        try { return RustWorldgenBackend.decorationPoints(handle, input, output, count); }
        finally { scope.cost.displayCalls++; scope.cost.displayPoints += count; scope.cost.displayNanos += System.nanoTime() - start; }
    }

    static Holder<Biome> biome(ClientTerrainSampler terrain, int x, int y, int z) {
        var scope = CURRENT.get();
        if (scope == null) return terrain.noiseBiome(x, y, z);
        long start = System.nanoTime();
        try { return terrain.noiseBiome(x, y, z); }
        finally { scope.cost.biomeCalls++; scope.cost.biomeNanos += System.nanoTime() - start; }
    }

    static int nativeBiome(long handle, ByteBuffer input, ByteBuffer output, int count) {
        var scope = CURRENT.get();
        if (scope == null) return RustWorldgenBackend.biomeIndices(handle, input, output, count);
        long start = System.nanoTime();
        try { return RustWorldgenBackend.biomeIndices(handle, input, output, count); }
        finally { scope.cost.nativeBiomeCalls++; scope.cost.nativeBiomePoints += count; scope.cost.nativeBiomeNanos += System.nanoTime() - start; }
    }

    static void reset() {
        if (CURRENT.get() != null) throw new IllegalStateException("Leaked feature probe");
        COSTS.clear();
    }

    static void save(Path output, int round) throws java.io.IOException {
        if (CURRENT.get() != null) throw new IllegalStateException("Leaked feature probe");
        Files.writeString(output.resolve("feature-costs-" + round + ".json"), JSON.toJson(Map.of(
                "round", round,
                "scope", "Inclusive top-level feature times; exact/display/biome times are nested. Journal writes include rollback. No-write attempts are not proof that work can be skipped. Instrumented runs are attribution data, not final timing results.",
                "features", COSTS.values().stream().sorted(Comparator.comparingLong((Cost c) -> c.nanos).reversed()).toList())));
    }
}
