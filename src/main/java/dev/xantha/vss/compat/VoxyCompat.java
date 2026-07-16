package dev.xantha.vss.compat;

import dev.xantha.vss.api.VSSApi;
import dev.xantha.vss.api.VoxelColumnData;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.networking.client.VSSClientNetworking;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongConsumer;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;

final class VoxyCompat {
    private static final int VOXY_BASE_LOD_LEVEL = 0;
    private static final long LOCAL_INDEX_RETRY_NANOS = 30_000_000_000L;

    private static MethodHandle worldIdentifierOf;
    private static MethodHandle rawIngest;
    private static MethodHandle worldEngineNullable;
    private static MethodHandle getStorage;
    private static MethodHandle iterateStoredSectionPositions;
    private static volatile MethodHandle getVoxyConfig;
    private static volatile MethodHandle getSectionRenderDist;
    private static volatile MethodHandle getEnabled;
    private static volatile MethodHandle getEnableRendering;
    private static volatile MethodHandle getIngestEnabled;
    private static boolean voxyStateInitialized;
    private static boolean lastRenderAvailable;
    private static boolean lastIngestAvailable;
    private static boolean localIndexWarningLogged;
    private static final Map<Object, LocalSectionIndex> localIndexes =
            Collections.synchronizedMap(new WeakHashMap<>());

    private VoxyCompat() {
    }

    static boolean init() {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            Class<?> worldIdClass = Class.forName("me.cortex.voxy.commonImpl.WorldIdentifier");
            worldIdentifierOf = lookup
                    .findStatic(worldIdClass, "of", MethodType.methodType(worldIdClass, Level.class))
                    .asType(MethodType.methodType(Object.class, Level.class));
            initLocalIndexHandles(lookup, worldIdClass);

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

            VSSApi.registerColumnProcessingConsumer((level, dimension, chunkX, chunkZ, columnData) -> {
                if (!VSSClientNetworking.isClientLodSessionActive()) {
                    return false;
                }
                try {
                    if (!isIngestAvailable()) {
                        return false;
                    }
                    Object worldId = worldIdentifierOf.invoke(level);
                    if (worldId == null) {
                        return false;
                    }

                    if (columnData.sections().length == 0) {
                        if (columnData.replaceMissingSections()) {
                            if (!clearMissingSections(worldId, level, chunkX, chunkZ, Set.of())) {
                                return false;
                            }
                        }
                        markLocalColumnPresent(level, chunkX, chunkZ);
                        return true;
                    }

                    boolean accepted = true;
                    for (VoxelColumnData.SectionData sectionData : columnData.sections()) {
                        accepted &= ingestSection(worldId, sectionData, chunkX, chunkZ);
                    }
                    if (!accepted) {
                        return false;
                    }
                    if (columnData.replaceMissingSections()) {
                        Set<Integer> presentSections = replacementSectionSet(columnData);
                        if (!clearMissingSections(worldId, level, chunkX, chunkZ, presentSections)) {
                            return false;
                        }
                    }
                    markLocalColumnPresent(level, chunkX, chunkZ);
                    return true;
                } catch (Throwable e) {
                    if (e instanceof Error && !(e instanceof LinkageError) && !(e instanceof AssertionError)) {
                        throw (Error) e;
                    }
                    VSSLogger.error("Voxy raw ingest failed", e);
                    return false;
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

    private static void initLocalIndexHandles(MethodHandles.Lookup lookup, Class<?> worldIdClass) {
        try {
            Class<?> worldEngineClass = Class.forName("me.cortex.voxy.common.world.WorldEngine");
            Class<?> sectionStorageClass = Class.forName("me.cortex.voxy.common.config.section.SectionStorage");
            worldEngineNullable = lookup
                    .findStatic(worldIdClass, "ofEngineNullable", MethodType.methodType(worldEngineClass, Level.class))
                    .asType(MethodType.methodType(Object.class, Level.class));
            getStorage = lookup
                    .findGetter(worldEngineClass, "storage", sectionStorageClass)
                    .asType(MethodType.methodType(Object.class, Object.class));
        } catch (Throwable e) {
            VSSLogger.debug("Voxy local section index query unavailable: " + e.getMessage());
            worldEngineNullable = null;
            getStorage = null;
            iterateStoredSectionPositions = null;
            return;
        }

        try {
            iterateStoredSectionPositions = findStoredSectionIterator(lookup);
        } catch (Throwable e) {
            VSSLogger.debug("Voxy local section iterator unavailable: " + e.getMessage());
            iterateStoredSectionPositions = null;
        }
    }

    private static MethodHandle findStoredSectionIterator(MethodHandles.Lookup lookup) throws Throwable {
        Throwable currentApiFailure;
        try {
            Class<?> iteratorClass = Class.forName("me.cortex.voxy.common.config.IStoredSectionPositionIterator");
            MethodHandle currentIterator = lookup
                    .findVirtual(iteratorClass, "iteratePositions",
                            MethodType.methodType(Void.TYPE, Integer.TYPE, LongConsumer.class))
                    .asType(MethodType.methodType(Void.TYPE, Object.class, Integer.TYPE, LongConsumer.class));
            return MethodHandles.insertArguments(currentIterator, 1, VOXY_BASE_LOD_LEVEL);
        } catch (Throwable e) {
            currentApiFailure = e;
        }

        try {
            Class<?> mappingStorageClass = Class.forName("me.cortex.voxy.common.config.IMappingStorage");
            return lookup
                    .findVirtual(mappingStorageClass, "iterateStoredSectionPositions",
                            MethodType.methodType(Void.TYPE, LongConsumer.class))
                    .asType(MethodType.methodType(Void.TYPE, Object.class, LongConsumer.class));
        } catch (Throwable legacyApiFailure) {
            legacyApiFailure.addSuppressed(currentApiFailure);
            throw legacyApiFailure;
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

    private static Set<Integer> replacementSectionSet(VoxelColumnData columnData) {
        HashSet<Integer> presentSections = new HashSet<>();
        for (int sectionY : columnData.replacementSectionYs()) {
            presentSections.add(sectionY);
        }
        return presentSections;
    }

    private static boolean clearMissingSections(
            Object worldId,
            Level level,
            int chunkX,
            int chunkZ,
            Set<Integer> presentSections)
            throws Throwable {
        boolean accepted = true;
        int minSection = level.getMinSection();
        int maxSection = minSection + level.getSectionsCount();
        for (int sectionY = minSection; sectionY < maxSection; sectionY++) {
            if (!presentSections.contains(sectionY)) {
                accepted &= (boolean) rawIngest.invoke(
                        worldId,
                        new LevelChunkSection(level.registryAccess().registryOrThrow(Registries.BIOME)),
                        chunkX,
                        sectionY,
                        chunkZ,
                        null,
                        null);
            }
        }
        return accepted;
    }

    static ModCompat.LocalColumnState getLocalColumnState(Level level, int chunkX, int chunkZ) {
        if (level == null || worldEngineNullable == null) {
            return ModCompat.LocalColumnState.UNKNOWN;
        }
        try {
            Object engine = worldEngineNullable.invoke(level);
            if (engine == null) {
                return ModCompat.LocalColumnState.UNKNOWN;
            }
            LocalSectionIndex index = localIndex(engine);
            if (index.hasConfirmed(chunkX, chunkZ)) {
                return ModCompat.LocalColumnState.PRESENT;
            }
            if (getStorage == null || iterateStoredSectionPositions == null || index.unavailable) {
                return ModCompat.LocalColumnState.UNKNOWN;
            }
            if (!index.ready) {
                startLocalIndexBuild(engine, index);
            }
            if (!index.ready) {
                return ModCompat.LocalColumnState.UNKNOWN;
            }
            return resolveLocalIndexState(false, true, index.hasStored(chunkX, chunkZ));
        } catch (Throwable e) {
            if (!localIndexWarningLogged) {
                localIndexWarningLogged = true;
                VSSLogger.debug("Voxy local section index query failed: " + e.getMessage());
            }
            return ModCompat.LocalColumnState.UNKNOWN;
        }
    }

    static ModCompat.LocalColumnState resolveLocalIndexState(boolean confirmed, boolean ready, boolean stored) {
        if (confirmed) {
            return ModCompat.LocalColumnState.PRESENT;
        }
        if (!ready) {
            return ModCompat.LocalColumnState.UNKNOWN;
        }
        return stored ? ModCompat.LocalColumnState.PRESENT : ModCompat.LocalColumnState.MISSING;
    }

    private static void markLocalColumnPresent(Level level, int chunkX, int chunkZ) {
        if (level == null || worldEngineNullable == null) {
            return;
        }
        try {
            Object engine = worldEngineNullable.invoke(level);
            if (engine == null) {
                return;
            }
            LocalSectionIndex index = localIndex(engine);
            index.markConfirmed(chunkX, chunkZ);
        } catch (Throwable ignored) {
        }
    }

    private static LocalSectionIndex localIndex(Object engine) {
        synchronized (localIndexes) {
            return localIndexes.computeIfAbsent(engine, ignored -> new LocalSectionIndex());
        }
    }

    private static void startLocalIndexBuild(Object engine, LocalSectionIndex index) {
        long now = System.nanoTime();
        if (now - index.nextBuildAttemptNanos < 0L) {
            return;
        }
        if (!index.buildStarted.compareAndSet(false, true)) {
            return;
        }
        Thread thread = new Thread(() -> {
            try {
                index.storedRegions.clear();
                Object storage = getStorage.invoke(engine);
                if (storage == null) {
                    index.nextBuildAttemptNanos = System.nanoTime() + LOCAL_INDEX_RETRY_NANOS;
                    return;
                }
                LongConsumer consumer = sectionKey -> {
                    int level = (int) ((sectionKey >>> 60) & 15L);
                    if (level == VOXY_BASE_LOD_LEVEL) {
                        index.markStoredWorldSection(unpackSectionX(sectionKey), unpackSectionZ(sectionKey));
                    }
                };
                iterateStoredSectionPositions.invoke(storage, consumer);
                index.ready = true;
            } catch (Throwable e) {
                index.ready = false;
                if (isUnsupportedLocalIndexQuery(e)) {
                    index.unavailable = true;
                } else {
                    index.nextBuildAttemptNanos = System.nanoTime() + LOCAL_INDEX_RETRY_NANOS;
                }
                if (!localIndexWarningLogged) {
                    localIndexWarningLogged = true;
                    VSSLogger.debug("Voxy local section index build failed: " + e.getMessage());
                }
            } finally {
                index.buildStarted.set(false);
            }
        }, "VSS Voxy local index");
        thread.setDaemon(true);
        thread.start();
    }

    private static long regionKey(int regionX, int regionZ) {
        return ((long) regionX << 32) ^ (regionZ & 0xFFFFFFFFL);
    }

    private static int unpackSectionX(long sectionKey) {
        return (int) (sectionKey << 36 >> 40);
    }

    private static int unpackSectionZ(long sectionKey) {
        return (int) (sectionKey << 12 >> 40);
    }

    static int chunkCoordinateForWorldSection(int worldSectionCoordinate, int localChunk) {
        if (localChunk < 0 || localChunk > 1) {
            throw new IllegalArgumentException("Voxy local chunk must be 0 or 1");
        }
        return (worldSectionCoordinate << 1) + localChunk;
    }

    private static boolean isUnsupportedLocalIndexQuery(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof UnsupportedOperationException
                    || "Not yet implemented".equalsIgnoreCase(current.getMessage())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static final class LocalSectionIndex {
        private final ConcurrentHashMap<Long, long[]> confirmedRegions = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Long, long[]> storedRegions = new ConcurrentHashMap<>();
        private final AtomicBoolean buildStarted = new AtomicBoolean();
        private volatile boolean ready;
        private volatile boolean unavailable;
        private volatile long nextBuildAttemptNanos;

        private void markConfirmed(int chunkX, int chunkZ) {
            mark(confirmedRegions, chunkX, chunkZ);
        }

        private void markStoredWorldSection(int worldSectionX, int worldSectionZ) {
            for (int localZ = 0; localZ < 2; localZ++) {
                for (int localX = 0; localX < 2; localX++) {
                    mark(
                            storedRegions,
                            chunkCoordinateForWorldSection(worldSectionX, localX),
                            chunkCoordinateForWorldSection(worldSectionZ, localZ));
                }
            }
        }

        private static void mark(ConcurrentHashMap<Long, long[]> regions, int chunkX, int chunkZ) {
            int regionX = Math.floorDiv(chunkX, 32);
            int regionZ = Math.floorDiv(chunkZ, 32);
            int slot = (chunkX & 31) | ((chunkZ & 31) << 5);
            regions.compute(regionKey(regionX, regionZ), (key, bitmap) -> {
                long[] target = bitmap != null ? bitmap : new long[16];
                target[slot >>> 6] |= 1L << (slot & 63);
                return target;
            });
        }

        private boolean hasConfirmed(int chunkX, int chunkZ) {
            return has(confirmedRegions, chunkX, chunkZ);
        }

        private boolean hasStored(int chunkX, int chunkZ) {
            return has(storedRegions, chunkX, chunkZ);
        }

        private static boolean has(ConcurrentHashMap<Long, long[]> regions, int chunkX, int chunkZ) {
            int regionX = Math.floorDiv(chunkX, 32);
            int regionZ = Math.floorDiv(chunkZ, 32);
            int slot = (chunkX & 31) | ((chunkZ & 31) << 5);
            long[] bitmap = regions.get(regionKey(regionX, regionZ));
            return bitmap != null && (bitmap[slot >>> 6] & (1L << (slot & 63))) != 0L;
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
