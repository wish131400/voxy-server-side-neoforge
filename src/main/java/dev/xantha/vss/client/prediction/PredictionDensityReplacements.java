package dev.xantha.vss.client.prediction;

import dev.xantha.vss.api.worldgen.DensityFunctionReplacements;
import net.minecraft.world.level.levelgen.DensityFunction;

/** Optional graph rewrites are atomic: an opaque runtime retains its original roots. */
final class PredictionDensityReplacements {
    static DensityFunction[] apply(DensityFunctionReplacements.Context context, DensityFunction... roots) {
        if (DensityFunctionReplacements.empty()) return roots;
        try {
            var analysis = new PredictionRawDensity();
            DensityFunction[] changed = roots.clone();
            for (int i = 0; i < changed.length; i++) {
                if (changed[i] != null && !analysis.inspect(changed[i]).stateful())
                    changed[i] = changed[i].mapAll(node -> DensityFunctionReplacements.apply(node, context));
            }
            return changed;
        } catch (java.util.concurrent.CancellationException cancelled) { throw cancelled; }
        catch (RuntimeException failure) {
            dev.xantha.vss.common.VSSLogger.warn("VSS density optimization rejected; retaining original Java functions", failure);
            return roots;
        }
    }
    private PredictionDensityReplacements() { }
}
