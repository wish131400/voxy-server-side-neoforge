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

    /**
     * Called by the profile decoder once the VSS worldgen registry snapshot is
     * decoded, letting a backend reuse the reconstructed biomes/densities
     * instead of parsing its own copy. {@link ClientWorldgenRegistries} is
     * package-private, so third-party implementors keep using the two-argument
     * form above.
     */
    default Optional<ClientTerrainSampler> open(DimensionProfile profile, long seed,
                                                RegistryAccess registries,
                                                ClientWorldgenRegistries worldgen) {
        return open(profile, seed, registries);
    }
    /** Internal session inputs; existing third-party backend implementations keep their original hook. */
    default Optional<ClientTerrainSampler> open(DimensionProfile profile, long seed,
            RegistryAccess registries, ClientWorldgenRegistries worldgen, RustWorldgenDocument.SharedInputs inputs) {
        return open(profile, seed, registries, worldgen);
    }
}
