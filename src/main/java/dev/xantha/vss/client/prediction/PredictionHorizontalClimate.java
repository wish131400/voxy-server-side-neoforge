package dev.xantha.vss.client.prediction;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import dev.xantha.vss.api.worldgen.DensityFunctionTraits;
import java.util.IdentityHashMap;
import net.minecraft.core.Holder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

/** Replaces maximal proven horizontal branches without reassociating any arithmetic. */
final class PredictionHorizontalClimate implements AutoCloseable {
    private final PredictionClimateCache cache = new PredictionClimateCache();
    private final JsonElement[] expressions = new JsonElement[PredictionClimateCache.MAX_INPUTS];
    private final DensityFunction[] originals = new DensityFunction[PredictionClimateCache.MAX_INPUTS];
    private final PredictionRawDensity analysis = new PredictionRawDensity();
    private final IdentityHashMap<DensityFunction, DensityFunction> rewritten = new IdentityHashMap<>();
    private int remaining = 65536;

    DensityFunction[] wrap(DensityFunction... roots) {
        if ("off".equals(System.getProperty("vss.horizontalClimate"))) return roots;
        var result = roots.clone();
        for (int i = 0; i < roots.length; i++) result[i] = rewrite(roots[i]);
        rewritten.clear();
        return result;
    }
    private DensityFunction rewrite(DensityFunction function) {
        var known = rewritten.get(function);
        if (known != null) return known;
        if (--remaining < 0) return function;
        var info = analysis.inspect(function);
        if (!info.pure() || info.stateful()) return function;
        if (info.horizontal()) {
            if (function.minValue() == function.maxValue()) return function;
            int lane = cache.reserve();
            if (lane < 0) return function;
            originals[lane] = function;
            var cut = new Cut(function, lane, cache);
            rewritten.put(function, cut);
            return cut;
        }
        if (!function.getClass().isRecord()) return function;
        try {
            var parts = function.getClass().getRecordComponents();
            Object[] values = new Object[parts.length]; Class<?>[] types = new Class<?>[parts.length];
            boolean changed = false;
            for (int i = 0; i < parts.length; i++) {
                types[i] = parts[i].getType();
                Object old = PredictionRawDensity.recordValue(function, parts[i]), next = old;
                if (old instanceof DensityFunction child) next = rewrite(child);
                else if (old instanceof Holder<?> holder && holder.value() instanceof DensityFunction child) {
                    var replacement = rewrite(child);
                    if (replacement != child) next = Holder.direct(replacement);
                }
                values[i] = next; changed |= next != old;
            }
            if (!changed) return function;
            var constructor = function.getClass().getDeclaredConstructor(types); constructor.setAccessible(true);
            var replacement = (DensityFunction) constructor.newInstance(values);
            rewritten.put(function, replacement);
            return replacement;
        } catch (ReflectiveOperationException | RuntimeException unsupported) { return function; }
    }
    private record Cut(DensityFunction original, int lane, PredictionClimateCache cache)
            implements DensityFunction.SimpleFunction, DensityFunctionTraits.Aware {
        @Override public double compute(FunctionContext point) {
            return cache.get(lane, point.blockX(), point.blockZ(), () -> original.compute(point));
        }
        @Override public double minValue() { return original.minValue(); }
        @Override public double maxValue() { return original.maxValue(); }
        @Override public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            throw new UnsupportedOperationException("Runtime climate input cache");
        }
        @Override public DensityFunctionTraits.Traits vssDensityTraits() { return DensityFunctionTraits.Traits.coordinate(true); }
    }
    void bind(long world, net.minecraft.world.level.levelgen.RandomState random, com.google.gson.JsonObject document, long seed) {
        var definitions = document.getAsJsonObject("noises");
        if (definitions == null) return;
        for (int lane = 0; lane < cache.inputCount(); lane++) {
            final var noises = new java.util.HashMap<JsonElement, JsonElement>();
            final boolean[] valid = {true};
            try {
                var inline = originals[lane].mapAll(new DensityFunction.Visitor() {
                    @Override public DensityFunction apply(DensityFunction node) {
                        if (node.getClass().getNestHost() != DensityFunctions.class) valid[0] = false;
                        return node instanceof DensityFunctions.HolderHolder holder ? holder.function().value() : node;
                    }
                    @Override public DensityFunction.NoiseHolder visitNoise(DensityFunction.NoiseHolder noise) {
                        var parameters = net.minecraft.world.level.levelgen.synth.NormalNoise.NoiseParameters.DIRECT_CODEC
                                .encodeStart(JsonOps.INSTANCE, noise.noiseData().value()).result().orElseThrow();
                        String identity = null;
                        for (var entry : definitions.entrySet()) if (entry.getValue().equals(parameters)) {
                            var key = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.NOISE,
                                    net.minecraft.resources.ResourceLocation.parse(entry.getKey()));
                            if (noise.noise() != null && random.getOrCreateNoise(key) == noise.noise()) { identity = entry.getKey(); break; }
                        }
                        if (identity == null) { valid[0] = false; return noise; }
                        var name = new com.google.gson.JsonPrimitive(identity);
                        var previous = noises.putIfAbsent(parameters, name);
                        if (previous != null && !previous.equals(name)) valid[0] = false;
                        return noise;
                    }
                });
                expressions[lane] = valid[0] ? PredictionClimateIdentity.normalize(
                        DensityFunction.DIRECT_CODEC.encodeStart(JsonOps.INSTANCE, inline).result().orElseThrow(), noises, 0) : null;
            } catch (RuntimeException unsupported) { expressions[lane] = null; }
        }
        cache.bind(world, expressions, seed, false);
    }
    void detach() { cache.detach(); }
    String diagnostics() { return cache.diagnostics(); }
    @Override public void close() { cache.close(); }
}
