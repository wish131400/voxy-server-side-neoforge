package dev.xantha.vss.client.prediction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.core.RegistryAccess;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;

/** Registry used by VSS-compatible terrain mods to provide predictive samplers. */
public final class PredictionTerrainBackends {
    private static final List<PredictionTerrainBackend> BACKENDS = new CopyOnWriteArrayList<>();

    private PredictionTerrainBackends() {
    }

    public static void register(PredictionTerrainBackend backend) {
        if (backend == null) throw new IllegalArgumentException("backend is null");
        BACKENDS.removeIf(existing -> existing.id().equals(backend.id()));
        BACKENDS.add(backend);
        BACKENDS.sort(Comparator.comparingInt(PredictionTerrainBackend::priority).reversed());
    }

    public static Optional<ClientTerrainSampler> open(DimensionProfile profile, long seed,
                                                      RegistryAccess registries) {
        for (PredictionTerrainBackend backend : BACKENDS) {
            try {
                Optional<ClientTerrainSampler> result = backend.open(profile, seed, registries);
                if (result.isPresent()) return result;
            } catch (RuntimeException ignored) {
                // A failed optional backend must not disable the vanilla sampler.
            }
        }
        return Optional.empty();
    }

    public static List<PredictionTerrainBackend> snapshot() {
        return List.copyOf(new ArrayList<>(BACKENDS));
    }
}
