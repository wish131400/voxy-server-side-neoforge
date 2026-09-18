package dev.xantha.vss.networking.client;

import dev.xantha.vss.common.VSSConstants;
import java.util.function.BooleanSupplier;

/** Keeps the server's prediction subscription in step with the runtime option. */
final class PredictionCapabilitySync {
    private boolean negotiated;
    private boolean advertised;
    private int retryTicks;

    void sent(int capabilities) {
        negotiated = true;
        advertised = (capabilities & VSSConstants.CAPABILITY_PREDICTIVE_WORLDGEN) != 0;
        retryTicks = 0;
    }

    void reset() {
        negotiated = false;
        advertised = false;
        retryTicks = 0;
    }

    void tick(boolean sessionReady, boolean enabled, BooleanSupplier sendHandshake) {
        // Initial login uses the ordinary handshake path. A disabled/absent server
        // must not be repeatedly probed just because the local option changed.
        if (!sessionReady || !negotiated || enabled == advertised) {
            retryTicks = 0;
            return;
        }
        if (retryTicks > 0) {
            retryTicks--;
            return;
        }
        if (sendHandshake.getAsBoolean()) {
            advertised = enabled;
        } else {
            retryTicks = 5;
        }
    }
}
