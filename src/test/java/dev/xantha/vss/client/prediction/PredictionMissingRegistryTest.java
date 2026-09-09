package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.core.RegistryAccess;
import org.junit.jupiter.api.Test;

class PredictionMissingRegistryTest {
    @Test void onlyConfirmedMissingRegistryIsPermanent() {
        var missing = new IllegalStateException("Registry lithostitched:template_list not found");
        assertTrue(PredictionMissingRegistry.permanent(missing, RegistryAccess.EMPTY));
        assertTrue(PredictionMissingRegistry.permanent(new RuntimeException("wrapped", missing), RegistryAccess.EMPTY));
        assertFalse(PredictionMissingRegistry.permanent(new IllegalStateException("missing template"), RegistryAccess.EMPTY));
        assertFalse(PredictionMissingRegistry.permanent(new java.util.concurrent.CancellationException(), RegistryAccess.EMPTY));
        assertFalse(PredictionMissingRegistry.permanent(new IllegalArgumentException(missing.getMessage()), RegistryAccess.EMPTY));
    }

    @Test void presentRegistryDoesNotDisableStructure() {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        var key = net.minecraft.resources.ResourceKey.<Integer>createRegistryKey(
                net.minecraft.resources.ResourceLocation.parse("lithostitched:template_list"));
        var registry = new net.minecraft.core.MappedRegistry<Integer>(key, com.mojang.serialization.Lifecycle.stable());
        registry.freeze();
        var access = new RegistryAccess.ImmutableRegistryAccess(java.util.List.of(registry));
        assertFalse(PredictionMissingRegistry.permanent(
                new IllegalStateException("Registry lithostitched:template_list not found"), access));
    }
}
