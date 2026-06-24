package dev.xantha.vss.compat;

import dev.xantha.vss.api.VSSApi;
import dev.xantha.vss.api.VoxelColumnData;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.networking.client.VSSClientNetworking;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.OptionalInt;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;

final class VoxyCompat {
    private static MethodHandle worldIdentifierOf;
    private static MethodHandle rawIngest;
    private static volatile MethodHandle getVoxyConfig;
    private static volatile MethodHandle getSectionRenderDist;
    private static volatile MethodHandle getEnabled;
    private static volatile MethodHandle getEnableRendering;
    private static volatile MethodHandle getIngestEnabled;
    private static boolean voxyStateInitialized;
    private static boolean lastRenderAvailable;
    private static boolean lastIngestAvailable;

    private VoxyCompat() {
    }

    static boolean init() {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            Class<?> worldIdClass = Class.forName("me.cortex.voxy.commonImpl.WorldIdentifier");
            worldIdentifierOf = lookup
                    .findStatic(worldIdClass, "of", MethodType.methodType(worldIdClass, Level.class))
                    .asType(MethodType.methodType(Object.class, Level.class));

            Class<?> ingestClass = Class.forName("me.cortex.voxy.common.world.service.VoxelIngestService");
            rawIngest = lookup.findStatic(
                    ingestClass,
                    "rawIngest",
                    MethodType.methodType(
                            Boolean.TYPE,
                            worldIdClass,
                            LevelChunkSection.class,
                            Integer.TYPE,
                            Integer.TYPE,
                            Integer.TYPE,
                            DataLayer.class,
                            DataLayer.class));

            VSSApi.registerColumnConsumer((level, dimension, chunkX, chunkZ, columnData) -> {
                if (!VSSClientNetworking.isClientLodSessionActive()) {
                    return;
                }
                try {
                    if (!isIngestAvailable()) {
                        VSSClientNetworking.onColumnProcessingFailed(dimension, chunkX, chunkZ);
                        return;
                    }
                    Object worldId = worldIdentifierOf.invoke(level);
                    if (worldId == null) {
                        return;
                    }

                    if (columnData.sections().length == 0) {
                        if (columnData.replaceMissingSections()) {
                            clearMissingSections(worldId, level, chunkX, chunkZ, Set.of());
                        }
                        return;
                    }

                    boolean accepted = true;
                    Set<Integer> presentSections = columnData.replaceMissingSections()
                            ? new HashSet<>()
                            : null;
                    for (VoxelColumnData.SectionData sectionData : columnData.sections()) {
                        if (presentSections != null) {
                            presentSections.add(sectionData.sectionY());
                        }
                        accepted &= ingestSection(worldId, sectionData, chunkX, chunkZ);
                    }
                    if (presentSections != null) {
                        clearMissingSections(worldId, level, chunkX, chunkZ, presentSections);
                    }
                    if (!accepted) {
                        VSSClientNetworking.onColumnProcessingFailed(dimension, chunkX, chunkZ);
                    }
                } catch (Throwable e) {
                    if (e instanceof Error && !(e instanceof LinkageError) && !(e instanceof AssertionError)) {
                        throw (Error) e;
                    }
                    VSSLogger.error("Voxy raw ingest failed", e);
                }
            });
            VSSLogger.info("Voxy detected, registered raw ingest bridge");
            return true;
        } catch (ClassNotFoundException e) {
            VSSLogger.warn("Voxy compat: class not found - " + e.getMessage());
            return false;
        } catch (NoSuchMethodException e) {
            VSSLogger.warn("Voxy compat: method not found - " + e.getMessage());
            return false;
        } catch (Throwable e) {
            VSSLogger.error("Failed to initialize Voxy compat", e);
            return false;
        }
    }

    static void clientTick() {
        boolean renderAvailable = isRenderAvailable();
        boolean ingestAvailable = isIngestAvailable();
        if (!voxyStateInitialized) {
            lastRenderAvailable = renderAvailable;
            lastIngestAvailable = ingestAvailable;
            voxyStateInitialized = true;
            return;
        }

        if ((!lastRenderAvailable && renderAvailable) || (!lastIngestAvailable && ingestAvailable)) {
            VSSClientNetworking.forceLodResync("Voxy became available again");
        }
        lastRenderAvailable = renderAvailable;
        lastIngestAvailable = ingestAvailable;
    }

    private static boolean ingestSection(
            Object worldId,
            VoxelColumnData.SectionData sectionData,
            int chunkX,
            int chunkZ) throws Throwable {
        return (boolean) rawIngest.invoke(
                worldId,
                sectionData.section(),
                chunkX,
                sectionData.sectionY(),
                chunkZ,
                sectionData.blockLight(),
                sectionData.skyLight());
    }

    private static void clearMissingSections(Object worldId, Level level, int chunkX, int chunkZ, Set<Integer> presentSections)
            throws Throwable {
        int minSection = level.getMinSection();
        int maxSection = minSection + level.getSectionsCount();
        for (int sectionY = minSection; sectionY < maxSection; sectionY++) {
            if (presentSections.contains(sectionY)) {
                continue;
            }
            rawIngest.invoke(
                    worldId,
                    new LevelChunkSection(level.registryAccess().registryOrThrow(Registries.BIOME)),
                    chunkX,
                    sectionY,
                    chunkZ,
                    null,
                    null);
        }
    }

    static OptionalInt getViewDistanceChunks() {
        try {
            initConfigHandles();
            Object config = getVoxyConfig.invokeExact();
            if (config == null || !readBoolean(getEnabled, config, true) || !readBoolean(getEnableRendering, config, true)) {
                return OptionalInt.of(0);
            }
            float sectionDist = (float) getSectionRenderDist.invokeExact(config);
            return OptionalInt.of(Math.round(sectionDist * 32.0f));
        } catch (Throwable e) {
            return OptionalInt.empty();
        }
    }

    private static boolean isRenderAvailable() {
        try {
            initConfigHandles();
            Object config = getVoxyConfig.invokeExact();
            return config != null
                    && readBoolean(getEnabled, config, true)
                    && readBoolean(getEnableRendering, config, true);
        } catch (Throwable e) {
            return true;
        }
    }

    private static boolean isIngestAvailable() {
        try {
            initConfigHandles();
            Object config = getVoxyConfig.invokeExact();
            return config != null
                    && readBoolean(getEnabled, config, true)
                    && readBoolean(getIngestEnabled, config, true);
        } catch (Throwable e) {
            return true;
        }
    }

    private static boolean readBoolean(MethodHandle handle, Object config, boolean fallback) {
        if (handle == null) {
            return fallback;
        }
        try {
            return (boolean) handle.invoke(config);
        } catch (Throwable e) {
            return fallback;
        }
    }

    private static void initConfigHandles() throws Throwable {
        if (getVoxyConfig != null) {
            return;
        }
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        Class<?> voxyConfigClass = Class.forName("me.cortex.voxy.client.config.VoxyConfig");
        Field configField = voxyConfigClass.getField("CONFIG");
        getSectionRenderDist = lookup
                .findGetter(voxyConfigClass, "sectionRenderDistance", Float.TYPE)
                .asType(MethodType.methodType(Float.TYPE, Object.class));
        getEnabled = lookup
                .findGetter(voxyConfigClass, "enabled", Boolean.TYPE)
                .asType(MethodType.methodType(Boolean.TYPE, Object.class));
        getEnableRendering = lookup
                .findGetter(voxyConfigClass, "enableRendering", Boolean.TYPE)
                .asType(MethodType.methodType(Boolean.TYPE, Object.class));
        getIngestEnabled = lookup
                .findGetter(voxyConfigClass, "ingestEnabled", Boolean.TYPE)
                .asType(MethodType.methodType(Boolean.TYPE, Object.class));
        getVoxyConfig = lookup.unreflectGetter(configField).asType(MethodType.methodType(Object.class));
    }
}
