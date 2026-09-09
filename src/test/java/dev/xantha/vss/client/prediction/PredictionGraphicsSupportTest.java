package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PredictionGraphicsSupportTest {
    @Test void ordinaryAndIrisHaveDistinctApiRequirements() {
        assertNull(PredictionGraphicsSupport.unsupportedReason(false, true, false, 16, 16));
        assertNotNull(PredictionGraphicsSupport.unsupportedReason(true, true, false, 16, 16));
        assertNull(PredictionGraphicsSupport.unsupportedReason(true, true, true, 16, 16));
        assertNotNull(PredictionGraphicsSupport.unsupportedReason(false, false, false, 16, 16));
        assertNotNull(PredictionGraphicsSupport.unsupportedReason(true, true, true, 7, 16));
        assertNotNull(PredictionGraphicsSupport.unsupportedReason(false, true, true, 16, 1));
    }
}
