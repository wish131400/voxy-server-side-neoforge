package dev.xantha.vss.networking.client;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.common.VSSConstants;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PredictionCapabilitySyncTest {
    private static final int BASE = VSSConstants.CAPABILITY_VOXEL_COLUMNS;
    private static final int PREDICTION = BASE | VSSConstants.CAPABILITY_PREDICTIVE_WORLDGEN;

    @Test void joiningDisabledThenEnablingRequestsTheMissingProfileOnce() {
        var sync = new PredictionCapabilitySync();
        var sent = new ArrayList<Integer>();
        sync.sent(BASE);
        for (int i=0;i<40;i++) sync.tick(true,false,()->{fail("disabled login must stay idle");return false;});
        for (int i=0;i<100;i++) sync.tick(true,true,()->{sent.add(PREDICTION);return true;});
        assertEquals(List.of(PREDICTION),sent);
    }

    @Test void repeatedOffOnCyclesUpdateTheSubscriptionWithoutPerTickHandshakes() {
        var sync = new PredictionCapabilitySync();
        var sent = new ArrayList<Boolean>();
        sync.sent(PREDICTION);
        for (boolean enabled : new boolean[]{true,false,false,true,true,false,true}) {
            sync.tick(true,enabled,()->{sent.add(enabled);return true;});
        }
        assertEquals(List.of(false,true,false,true),sent);
    }

    @Test void failedSendRetriesAndDoesNotMarkTheMissingProfileRequested() {
        var sync = new PredictionCapabilitySync();
        var attempts = new AtomicInteger();
        sync.sent(BASE);
        sync.tick(true,true,()->{attempts.incrementAndGet();return false;});
        for(int i=0;i<5;i++) sync.tick(true,true,()->{fail("retry must be delayed");return false;});
        sync.tick(true,true,()->{attempts.incrementAndGet();return true;});
        for(int i=0;i<40;i++) sync.tick(true,true,()->{fail("successful update must stay idle");return false;});
        assertEquals(2,attempts.get());
    }

    @Test void disablingAgainCancelsAnUnsentEnableAndItsRetry() {
        var sync = new PredictionCapabilitySync();
        sync.sent(BASE);
        sync.tick(true,true,()->false);
        sync.tick(true,false,()->{fail("server already knows disabled");return false;});
        var attempts = new AtomicInteger();
        sync.tick(true,true,()->{attempts.incrementAndGet();return true;});
        assertEquals(1,attempts.get());
    }

    @Test void initialLoginDisconnectedAndDisabledServerDoNotTriggerRefresh() {
        var sync = new PredictionCapabilitySync();
        sync.tick(true,true,()->{fail("initial handshake owns first negotiation");return false;});
        sync.sent(BASE);
        for(int i=0;i<40;i++) sync.tick(false,true,()->{fail("no active session");return false;});
        var attempts = new AtomicInteger();
        sync.tick(true,true,()->{attempts.incrementAndGet();return true;});
        assertEquals(1,attempts.get());
        sync.reset();
        sync.tick(true,false,()->{fail("old connection state must not leak");return false;});
        sync.sent(BASE);
        sync.tick(true,true,()->{attempts.incrementAndGet();return true;});
        assertEquals(2,attempts.get());
    }

    @Test void ordinaryHandshakeWithLatestOptionAvoidsAnExtraRefresh() {
        var sync = new PredictionCapabilitySync();
        sync.sent(BASE);
        sync.sent(PREDICTION);
        sync.tick(true,true,()->{fail("latest handshake already subscribed");return false;});
    }
}
