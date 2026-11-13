package dev.xantha.vss.networking.server.session;

import static org.junit.jupiter.api.Assertions.*;

import dev.xantha.vss.common.worldgen.WorldgenProfileCacheConfig;
import dev.xantha.vss.common.worldgen.WorldgenSectionCache;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The profile holder keeps payloads by bytes, not four dimensions; with the cache switched off it keeps four. */
class WorldgenProfileCacheTest {
    private static WorldgenProfileS2CPayload payload(int bytes) {
        return new WorldgenProfileS2CPayload(WorldgenProfileS2CPayload.FORMAT_VERSION, 1L, 1L, 0, bytes, new byte[bytes], List.of());
    }

    /** What the holder does after putting a payload: evict the eldest while over its bound. */
    private static void put(LinkedHashMap<Integer, WorldgenProfileS2CPayload> payloads, int key, int bytes) {
        payloads.put(key, payload(bytes));
        while (WorldgenProfileCache.overBound(payloads)) payloads.remove(payloads.keySet().iterator().next());
    }

    @AfterEach
    void reopen() throws Exception {
        var field = WorldgenSectionCache.class.getDeclaredField("refused");
        field.setAccessible(true);
        field.set(null, null);
    }

    @Test
    void fiveAndMoreSmallDimensionsStayCachedUpToOnePerDimension() {
        var payloads = new LinkedHashMap<Integer, WorldgenProfileS2CPayload>();
        for (int i = 0; i < 5; i++) put(payloads, i, 1024);
        assertEquals(5, payloads.size(), "four was the old bound: every fifth dimension was a build");
        for (int i = 5; i < 80; i++) put(payloads, i, 16);
        assertEquals(WorldgenProfileCacheConfig.MAX_PAYLOADS, payloads.size());
        assertEquals(80 - WorldgenProfileCacheConfig.MAX_PAYLOADS, payloads.keySet().iterator().next(), "the eldest go first");
    }

    @Test
    void summedPayloadBytesBoundTheHolderAndTheNewestPayloadIsAlwaysKept() {
        var payloads = new LinkedHashMap<Integer, WorldgenProfileS2CPayload>();
        int large = WorldgenProfileS2CPayload.MAX_REGISTRIES_BYTES;
        for (int i = 0; i < 8; i++) put(payloads, i, large);
        assertTrue(WorldgenProfileCache.bytes(payloads) <= WorldgenProfileCacheConfig.MAX_BYTES);
        assertEquals(7, payloads.size(), "eight payloads of 8 MiB plus their overhead exceed 64 MiB");
        assertEquals(1, payloads.keySet().iterator().next());
        var single = new LinkedHashMap<Integer, WorldgenProfileS2CPayload>();
        single.put(0, payload(large));
        assertFalse(WorldgenProfileCache.overBound(single), "one payload is never evicted");
    }

    @Test
    void withTheCacheSwitchedOffTheHolderKeepsFourPayloads() {
        WorldgenSectionCache.refuse("test: switched off");
        var payloads = new LinkedHashMap<Integer, WorldgenProfileS2CPayload>();
        for (int i = 0; i < 6; i++) put(payloads, i, 16);
        assertEquals(4, payloads.size());
        assertEquals(2, payloads.keySet().iterator().next());
    }
}
