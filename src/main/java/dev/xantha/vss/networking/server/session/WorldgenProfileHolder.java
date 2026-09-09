package dev.xantha.vss.networking.server.session;

import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload;
import net.minecraft.server.MinecraftServer;

/**
 * Server-side cache equivalent to the WorldgenSyncHolder.  Registry
 * encoding is intentionally done once per worldgen lifetime, not once
 * per player login or refresh broadcast.
 */
public final class WorldgenProfileHolder {
    private static MinecraftServer owner;
    private static WorldgenProfileS2CPayload payload;

    private WorldgenProfileHolder() {
    }

    public static synchronized WorldgenProfileS2CPayload payloadFor(
            MinecraftServer server, long revision) {
        if (server == null) {
            throw new IllegalArgumentException("server cannot be null");
        }
        if (payload != null && owner == server) {
            return payload;
        }
        long started = System.nanoTime();
        WorldgenProfileS2CPayload built = WorldgenProfileBuilder.build(server, revision);
        owner = server;
        payload = built;
        VSSLogger.info("VSS built worldgen profile for " + built.dimensions().size()
                + " dimensions in " + ((System.nanoTime() - started) / 1_000_000L) + " ms");
        return built;
    }

    public static synchronized void clear() {
        owner = null;
        payload = null;
    }
}
