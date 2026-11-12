package dev.xantha.vss.client.prediction.feature;

import dev.xantha.vss.common.VSSLogger;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * Third-party worldgen hooks that call level.getLevel() only to learn the dimension. A prediction level knows the
 * dimension it predicts ({@link FeatureStampLevel#predictionDimension()}) but has no server level, so such a hook
 * throws on every predicted feature.
 */
public final class PredictionLevelDimension {
    private static final Set<ResourceLocation> REPORTED = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean FAILED = new AtomicBoolean();

    private PredictionLevelDimension() { }

    /** The dimension a prediction level predicts; null for any other level and for a prediction level without one. */
    public static ResourceLocation of(Object level) {
        if (!(level instanceof FeatureStampLevel prediction)) return null;
        try {
            return prediction.predictionDimension();
        } catch (RuntimeException | LinkageError unavailable) {
            // A capture level without a context or profile has no known dimension.
            return null;
        }
    }

    /**
     * RoadWeaver's road query: true only for a prediction level whose dimension is known and is not the Overworld.
     * RoadWeaver itself answers false for every such dimension before it reads its configuration or road data; real
     * levels, the Overworld and prediction levels without a dimension keep RoadWeaver's own path, and so does any
     * failure here.
     */
    public static boolean knownNonOverworldRoadQuery(Object level) {
        try {
            ResourceLocation dimension = of(level);
            if (dimension == null || Level.OVERWORLD.location().equals(dimension)) return false;
            if (REPORTED.add(dimension)) {
                VSSLogger.info("RoadWeaver road query on a VSS prediction level of " + dimension
                        + " answered false without calling getLevel (first for this dimension; later ones are not logged)");
            }
            return true;
        } catch (RuntimeException | LinkageError failure) {
            if (FAILED.compareAndSet(false, true)) {
                VSSLogger.error("RoadWeaver road query on VSS prediction levels keeps RoadWeaver's own path: the decision failed on "
                        + (level == null ? "null" : level.getClass().getName()), failure);
            }
            return false;
        }
    }
}
