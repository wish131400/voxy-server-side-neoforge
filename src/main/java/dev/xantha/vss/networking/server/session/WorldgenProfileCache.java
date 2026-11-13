package dev.xantha.vss.networking.server.session;

import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.common.worldgen.WorldgenProfileCacheConfig;
import dev.xantha.vss.common.worldgen.WorldgenSectionCache;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

/**
 * The byte bound of the worldgen profile holder and the builds that use the registry section cache.
 *
 * The holder keeps its payloads while their summed bytes stay within a bound (64 MiB by default) and at most one per
 * dimension, instead of four: with five or more dimensions in rotation every arrival used to be a build on the server
 * thread. Builds go through WorldgenSectionCache, which hands out the registry sections the same server already encoded
 * since the holder was last cleared. The payload bytes are the same with and without the cache.
 */
final class WorldgenProfileCache {
    private static final Map<WorldgenProfileS2CPayload, Long> WEIGHTS = new WeakHashMap<>();
    private static long evictions;
    private static boolean announced;

    private WorldgenProfileCache() { }

    /** True while the eldest payload must go: VSS's own four payloads when the cache is off, else the byte bound. */
    static synchronized boolean overBound(LinkedHashMap<?, WorldgenProfileS2CPayload> payloads) {
        int count = payloads.size();
        if (!WorldgenSectionCache.open()) return count > 4;
        if (count <= 1) return false;
        long bytes = bytes(payloads);
        if (bytes > WorldgenProfileCacheConfig.MAX_BYTES || count > WorldgenProfileCacheConfig.MAX_PAYLOADS) {
            evictions++;
            return true;
        }
        return false;
    }

    static synchronized long bytes(LinkedHashMap<?, WorldgenProfileS2CPayload> payloads) {
        long bytes = 0;
        for (WorldgenProfileS2CPayload value : payloads.values()) bytes += weight(value);
        return bytes;
    }

    /** The payload's accessors copy their arrays, so each payload is measured once. */
    private static long weight(WorldgenProfileS2CPayload payload) {
        if (payload == null) return 0;
        Long known = WEIGHTS.get(payload);
        if (known != null) return known;
        long bytes = 512L + payload.registries().length;
        for (var dimension : payload.dimensions()) bytes += 256L + dimension.generatorData().length;
        WEIGHTS.put(payload, bytes);
        return bytes;
    }

    /** The holder's builder call. Only a build that comes through here uses the section cache. */
    static WorldgenProfileS2CPayload build(MinecraftServer server, long revision, ResourceKey<Level> dimension) {
        synchronized (WorldgenProfileCache.class) {
            if (!announced) {
                announced = true;
                VSSLogger.info("Worldgen profile cache: " + WorldgenProfileCacheConfig.describe());
            }
        }
        if (!WorldgenSectionCache.begin(server, server.registryAccess())) return WorldgenProfileBuilder.build(server, revision, dimension);
        WorldgenProfileS2CPayload built;
        try {
            built = WorldgenProfileBuilder.build(server, revision, dimension);
        } finally {
            WorldgenSectionCache.end();
        }
        if (WorldgenProfileCacheConfig.VERIFY) built = verified(server, revision, dimension, built);
        return built;
    }

    /** One line after every build, once the holder applied its bound; and the payload hashes, unless switched off. */
    static synchronized void summary(ResourceKey<Level> dimension, long millis, WorldgenProfileS2CPayload built,
                                     LinkedHashMap<?, WorldgenProfileS2CPayload> payloads) {
        if (!WorldgenSectionCache.open()) return;
        try {
            String name = dimension == null ? "(all levels)" : dimension.location().toString();
            var counts = WorldgenSectionCache.counts();
            VSSLogger.info("Worldgen profile " + name + " built in " + millis + " ms: registry sections reused " + counts.sectionHits()
                    + " encoded " + counts.sectionMisses() + ", custom registries reused " + counts.customHits() + " encoded "
                    + counts.customMisses() + ", tag sets reused " + counts.tagHits() + " encoded " + counts.tagMisses()
                    + ", section cache " + counts.nodes() + " JSON values, about " + counts.bytes() + " B estimated ("
                    + counts.declined() + " sections declined); holder keeps " + payloads.size() + " payloads, " + bytes(payloads)
                    + " B of " + WorldgenProfileCacheConfig.MAX_BYTES + " B, " + evictions + " evicted");
            if (WorldgenProfileCacheConfig.DIAGNOSE) {
                String digests = digests(built);
                if (!digests.isEmpty()) VSSLogger.info("Worldgen profile " + name + " bytes: " + digests);
            }
        } catch (RuntimeException | LinkageError unavailable) {
            // Diagnostic only.
        }
    }

    private static WorldgenProfileS2CPayload verified(MinecraftServer server, long revision, ResourceKey<Level> dimension,
            WorldgenProfileS2CPayload built) {
        WorldgenProfileS2CPayload reference;
        WorldgenSectionCache.bypass(true);
        try {
            reference = WorldgenProfileBuilder.build(server, revision, dimension);
        } finally {
            WorldgenSectionCache.bypass(false);
        }
        boolean registries = built.registriesCompression() == reference.registriesCompression()
                && built.registriesRawSize() == reference.registriesRawSize() && Arrays.equals(built.registries(), reference.registries());
        boolean generators = built.dimensions().size() == reference.dimensions().size();
        for (int i = 0; generators && i < built.dimensions().size(); i++) {
            var a = built.dimensions().get(i);
            var b = reference.dimensions().get(i);
            generators = a.dimension().equals(b.dimension()) && a.fingerprint() == b.fingerprint()
                    && a.generatorRawSize() == b.generatorRawSize() && Arrays.equals(a.generatorData(), b.generatorData());
        }
        String name = dimension == null ? "(all levels)" : dimension.location().toString();
        if (!registries) {
            WorldgenSectionCache.refuse("Switched the worldgen profile cache off: the registries of " + name + " differ from an uncached build ("
                    + built.registries().length + " B raw " + built.registriesRawSize() + " against " + reference.registries().length
                    + " B raw " + reference.registriesRawSize() + "); the uncached build is used");
            return reference;
        }
        VSSLogger.info("Worldgen profile " + name + " verified: registries identical to an uncached build (" + built.registries().length
                + " B, sha256 " + sha256(built.registries()) + "), generators " + (generators ? "identical" : "DIFFERENT between two builds"));
        return built;
    }

    /** Short hashes of the bytes sent, to compare two sessions and two launches. */
    private static String digests(WorldgenProfileS2CPayload built) {
        try {
            var text = new StringBuilder("registries sha256 ").append(sha256(built.registries()), 0, 12).append(" (").append(built.registries().length).append(" B)");
            for (var dimension : built.dimensions()) {
                byte[] generator = dimension.generatorData();
                text.append(", generator ").append(dimension.dimension()).append(" sha256 ").append(sha256(generator), 0, 12).append(" (").append(generator.length).append(" B)");
            }
            return text.toString();
        } catch (RuntimeException | LinkageError unavailable) {
            return "";
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
