package dev.xantha.vss.client.prediction;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.function.LongSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/** Resource identity for the snapshot Java biome tint resolver, shared across dimensions. */
final class PredictionColorCache {
    static final PredictionColorCache RESOURCES = new PredictionColorCache(PredictionColorCache::readResources);
    private final LongSupplier loader;
    private boolean loaded;
    private long fingerprint;

    PredictionColorCache(LongSupplier loader) { this.loader = loader; }

    synchronized long fingerprint() {
        if (!loaded) {
            fingerprint = loader.getAsLong();
            loaded = true;
        }
        return fingerprint;
    }

    synchronized void invalidate() { loaded = false; }

    private static long readResources() {
        try {
            var resources = Minecraft.getInstance().getResourceManager();
            try (var grass = resources.open(ResourceLocation.withDefaultNamespace("textures/colormap/grass.png"));
                 var foliage = resources.open(ResourceLocation.withDefaultNamespace("textures/colormap/foliage.png"))) {
                return fingerprint(grass.readAllBytes(), foliage.readAllBytes());
            }
        } catch (IOException | RuntimeException unavailable) {
            // An unavailable resource identity must never validate stored tints.
            // Retry after resource reload, not once per tile during startup.
            return Long.MIN_VALUE;
        }
    }

    static long fingerprint(byte[] grass, byte[] foliage) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update((byte) 1); // Java snapshot tint semantics; separate from native tints.
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(grass.length).array());
            digest.update(grass);
            digest.update(foliage);
            long value = java.nio.ByteBuffer.wrap(digest.digest()).getLong();
            return value == Long.MIN_VALUE ? 0 : value;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
