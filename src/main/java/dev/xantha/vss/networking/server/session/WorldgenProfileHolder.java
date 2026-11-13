package dev.xantha.vss.networking.server.session;

import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.common.worldgen.WorldgenSectionCache;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload;
import net.minecraft.server.MinecraftServer;

/**
 * Server-side cache equivalent to the WorldgenSyncHolder.  Registry
 * encoding is cached per requested dimension and worldgen lifetime, not once
 * per player login or refresh broadcast.
 */
public final class WorldgenProfileHolder {
    private static MinecraftServer owner;
    private static final java.util.LinkedHashMap<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>, WorldgenProfileS2CPayload>
            payloads = new java.util.LinkedHashMap<>(8, .75f, true);

    private WorldgenProfileHolder() {
    }

    public static synchronized WorldgenProfileS2CPayload payloadFor(
            MinecraftServer server, long revision) {
        return payloadFor(server, revision, null);
    }
    public static synchronized WorldgenProfileS2CPayload payloadFor(MinecraftServer server, long revision,
            net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension) {
        if (server == null) {
            throw new IllegalArgumentException("server cannot be null");
        }
        if (owner != server) { payloads.clear(); owner = server; }
        WorldgenProfileS2CPayload cached = payloads.get(dimension);
        if (cached != null) {
            if (cached.revision() != revision) {
                // Rate/generation toggles change revision; only reload/clear changes worldgen inputs.
                cached = new WorldgenProfileS2CPayload(cached.formatVersion(), cached.seed(), revision,
                        cached.registriesCompression(), cached.registriesRawSize(), cached.registries(), cached.dimensions());
                payloads.put(dimension, cached);
            }
            return cached;
        }
        long started = System.nanoTime();
        WorldgenProfileS2CPayload built = WorldgenProfileCache.build(server, revision, dimension);
        long millis = (System.nanoTime() - started) / 1_000_000L;
        owner = server;
        payloads.put(dimension, built);
        // Bounded by payload bytes, not by four dimensions: with more dimensions in rotation every arrival was a build.
        while (WorldgenProfileCache.overBound(payloads)) payloads.remove(payloads.keySet().iterator().next());
        WorldgenProfileCache.summary(dimension, millis, built, payloads);
        VSSLogger.info("VSS built worldgen profile for " + built.dimensions().size()
                + " dimensions in " + ((System.nanoTime() - started) / 1_000_000L) + " ms");
        return built;
    }

    public static synchronized void clear() {
        WorldgenSectionCache.clear();
        owner = null;
        payloads.clear();
        StructureTemplateService.clear();
        WorldgenTemplateCatalog.clear();
    }
}
