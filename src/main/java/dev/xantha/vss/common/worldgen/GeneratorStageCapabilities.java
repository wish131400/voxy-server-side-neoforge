package dev.xantha.vss.common.worldgen;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.server.level.WorldGenRegion;

/** Output contracts, independent of mod IDs and mapped method names. */
public record GeneratorStageCapabilities(boolean columns, boolean terrain, boolean surface,
                                         boolean region, boolean biomes, boolean decoration) {
    public boolean originalStages() { return columns || terrain || surface; }
    public boolean isolated() { return !region && !biomes && !decoration; }

    public static GeneratorStageCapabilities inspect(Class<?> actual) {
        if (!NoiseBasedChunkGenerator.class.isAssignableFrom(actual))
            return new GeneratorStageCapabilities(false, false, false, true, false, false);
        boolean columns = false, terrain = false, surface = false, region = false, biomes = false, decoration = false;
        for (Class<?> type = actual; type != NoiseBasedChunkGenerator.class; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.isBridge() || Modifier.isStatic(method.getModifiers()) || Modifier.isPrivate(method.getModifiers())) continue;
                Method inherited = inherited(method);
                if (inherited == null) continue;
                Class<?>[] args = inherited.getParameterTypes();
                var parameters = Arrays.asList(args);
                if (inherited.getReturnType() == NoiseColumn.class || inherited.getReturnType() == java.util.OptionalInt.class)
                    columns = true;
                if (CompletableFuture.class.isAssignableFrom(inherited.getReturnType()) && parameters.contains(ChunkAccess.class)) {
                    if (args[0] == RandomState.class || args[0] == java.util.concurrent.Executor.class
                            && args.length > 1 && args[1] == RandomState.class) biomes = true;
                    else terrain = true;
                }
                if (parameters.contains(WorldGenerationContext.class)) surface = true;
                // Region surface/merged terrain entry points cannot be reproduced by a point router.
                // Carvers and structures remain the existing independent prediction stages.
                if (parameters.contains(WorldGenRegion.class) && parameters.contains(ChunkAccess.class)
                        && (args.length == 4 || !parameters.contains(long.class))) region = true;
                if (parameters.contains(net.minecraft.world.level.WorldGenLevel.class)
                        && parameters.contains(ChunkAccess.class) && args.length == 3) decoration = true;
            }
        }
        return new GeneratorStageCapabilities(columns, terrain, surface, region, biomes, decoration);
    }

    private static Method inherited(Method candidate) {
        for (Class<?> type = NoiseBasedChunkGenerator.class; type != null; type = type.getSuperclass()) {
            try {
                Method method = type.getDeclaredMethod(candidate.getName(), candidate.getParameterTypes());
                if (!Modifier.isPrivate(method.getModifiers()) && !Modifier.isStatic(method.getModifiers())) return method;
            } catch (NoSuchMethodException ignored) { }
        }
        return null;
    }
}
