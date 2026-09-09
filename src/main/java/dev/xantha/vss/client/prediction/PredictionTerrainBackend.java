package dev.xantha.vss.client.prediction;

import java.util.Optional;
import net.minecraft.core.RegistryAccess;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;

/** Extension point for terrain generators that are not NoiseBasedChunkGenerator. */
public interface PredictionTerrainBackend {
    String id();

    int priority();

    Optional<ClientTerrainSampler> open(DimensionProfile profile, long seed,
                                        RegistryAccess registries);
}
