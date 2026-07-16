package dev.xantha.vss.networking.server.storage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SectionSerializerLightCompletenessTest {
    @Test
    void skyLightDimensionRejectsMissingLightData() {
        assertFalse(SectionSerializer.hasCompleteRequiredLighting(true, true));
    }

    @Test
    void skyLightDimensionAcceptsPresentLightData() {
        assertTrue(SectionSerializer.hasCompleteRequiredLighting(true, false));
    }

    @Test
    void dimensionWithoutSkyLightDoesNotRequireLightData() {
        assertTrue(SectionSerializer.hasCompleteRequiredLighting(false, true));
    }
}
