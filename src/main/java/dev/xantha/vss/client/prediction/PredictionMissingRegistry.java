package dev.xantha.vss.client.prediction;

import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

/** A missing registry cannot recover within an immutable prediction snapshot. */
final class PredictionMissingRegistry {
    private static final java.util.regex.Pattern MESSAGE = java.util.regex.Pattern.compile(
            "Registry ([a-z0-9_.-]+:[a-z0-9/._-]+) not found");

    static boolean permanent(Throwable failure, RegistryAccess access) {
        for (int depth = 0; failure != null && depth < 16; depth++, failure = failure.getCause()) {
            if (!(failure instanceof IllegalStateException) || failure.getMessage() == null) continue;
            var match = MESSAGE.matcher(failure.getMessage());
            if (match.matches() && access.registry(ResourceKey.createRegistryKey(
                    ResourceLocation.parse(match.group(1)))).isEmpty()) return true;
        }
        return false;
    }

    private PredictionMissingRegistry() { }
}
