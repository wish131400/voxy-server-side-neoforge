package dev.xantha.vss.networking.server.generation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static dev.xantha.vss.networking.server.generation.GenerationTickBudget.SnapshotSource.*;

import dev.xantha.vss.config.VSSServerConfig;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.*;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class GenerationBudgetIntegrationTest {
    @BeforeAll
    static void bootstrap() {
        net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(java.nio.file.Path.of("build", "tmp", "generation-tests"));
        net.neoforged.fml.loading.LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void readyGeneratedChunkDoesNotRejectNormalOrPriorityLiveWorkWith128UnusedSlots() throws Exception {
        try (Fixture f = new Fixture()) {
            f.config.generationSnapshotsPerTickLimit = 128;
            f.addReady(false);
            assertTrue(f.live(1, false));
            assertTrue(f.live(2, true));
            assertTrue(f.service.diagnostics().contains("liveSnapshotsThisTick=2"));
        }
    }

    @Test
    void unusedTimeBudgetAdmitsLiveWorkDespiteReadyGeneration() throws Exception {
        try (Fixture f = new Fixture()) {
            f.config.generationSnapshotBudgetMillis = 50;
            f.addReady(false);
            assertTrue(f.live(1, true));
        }
    }

    @Test
    void lastOfTwoSlotsIsReservedForAnActuallyReadyGeneratedResult() throws Exception {
        try (Fixture f = new Fixture()) {
            f.config.generationSnapshotsPerTickLimit = 2;
            f.addReady(false);
            assertTrue(f.live(1, true));
            assertFalse(f.live(2, true));
            assertTrue(f.budget.canSnapshot(GENERATED, 2, 0));
        }
    }

    @Test
    void packingBlockedGenerationDoesNotReservePriorityLiveSlot() throws Exception {
        try (Fixture f = new Fixture()) {
            f.config.generationSnapshotsPerTickLimit = 2;
            f.addReady(false);
            f.budget.recordSnapshot(LIVE, 100);
            ThreadPoolExecutor executor = mock(ThreadPoolExecutor.class);
            BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
            for (int i = 0; i < f.config.automaticGenerationPackingQueueLimit(); i++) queue.add(() -> {});
            when(executor.getQueue()).thenReturn(queue);
            when(executor.getCorePoolSize()).thenReturn(1);
            when(executor.getActiveCount()).thenReturn(1);
            when(executor.shutdownNow()).thenReturn(List.of());
            field("packingExecutor").set(f.service, executor);
            assertFalse((boolean) method("shouldReserveGeneratedSnapshot").invoke(f.service));
            assertTrue(f.live(1, true));
        }
    }

    @Test
    void lastSlotIsNotReservedForChunkyResults() throws Exception {
        try (Fixture f = new Fixture()) {
            f.config.generationSnapshotsPerTickLimit = 2;
            f.addReady(true);
            assertTrue(f.live(1, false));
            assertTrue(f.live(2, true));
        }
    }

    @Test
    void lastSlotIsNotReservedForChunksStillGenerating() throws Exception {
        try (Fixture f = new Fixture()) {
            f.config.generationSnapshotsPerTickLimit = 2;
            f.addReady(false);
            when(f.chunks.getChunkNow(anyInt(), anyInt())).thenReturn(null);
            assertTrue(f.live(1, false));
            assertTrue(f.live(2, true));
        }
    }

    @Test
    void oneSlotServiceBudgetAlternatesAfterDeferredGeneratedHandoff() throws Exception {
        try (Fixture f = new Fixture()) {
            f.config.generationSnapshotsPerTickLimit = 1;
            f.addReady(false);
            f.budget.recordSnapshot(LIVE, 100);
            f.service.tick(f.server);
            assertTrue(f.service.diagnostics().contains("generatedSnapshotDeferred=true"));
            f.tick.incrementAndGet();
            assertFalse(f.live(1, true));
            f.service.tick(f.server);
            assertTrue(f.service.diagnostics().contains("generatedSnapshotsThisTick=1"));
            f.tick.incrementAndGet();
            assertTrue(f.live(2, true));
        }
    }

    @Test
    void explicitStartsBypassMsptPauseWithoutUnpausingAutomaticWork() throws Exception {
        try (Fixture f = new Fixture()) {
            f.config.generationPauseAboveMspt = 1;
            Object regular = f.pending(false, 10, false);
            Object chunky = f.pending(true, 20, false);
            assertFalse(f.start(regular));
            for (int i = 0; i < 10; i++) assertTrue(f.start(chunky));
            assertFalse(f.start(regular));
            assertEquals(0, field("startsThisTick").getInt(f.service));
        }
    }

    @Test
    void explicitStartsDoNotSpendOrDisableAutomaticCountLimit() throws Exception {
        try (Fixture f = new Fixture()) {
            f.config.generationConcurrencyLimitGlobal = 3;
            int automaticLimit = f.config.automaticGenerationStartsPerTick();
            Object regular = f.pending(false, 10, false);
            Object chunky = f.pending(true, 20, false);
            for (int i = 0; i < automaticLimit; i++) assertTrue(f.start(regular));
            for (int i = 0; i < 10; i++) assertTrue(f.start(chunky));
            assertFalse(f.start(regular));
            assertEquals(automaticLimit, field("startsThisTick").getInt(f.service));
            f.service.tick(f.server);
            assertFalse(f.start(regular));
            f.tick.incrementAndGet();
            assertTrue(f.start(regular));
            assertEquals(1, field("startsThisTick").getInt(f.service));
        }
    }

    @Test
    void pausedAutomaticQueueHeadDoesNotBlockChunkyBehindIt() throws Exception {
        try (Fixture f = new Fixture()) {
            f.config.generationPauseAboveMspt = 1;
            f.budget.beginTick(1, 10, 1);
            Object regular = f.pending(false, 10, true);
            Object chunky = f.pending(true, 20, false);
            f.enqueue(regular, 10);
            f.enqueue(chunky, 20);
            List<Object> blocked = new ArrayList<>();
            Object selection = method("selectNearestStartableQueuedGeneration", List.class).invoke(f.service, blocked);
            assertNotNull(selection);
            Method getter = selection.getClass().getDeclaredMethod("generation");
            getter.setAccessible(true);
            assertSame(chunky, getter.invoke(selection));
            assertEquals(1, blocked.size());
        }
    }

    @Test
    void explicitHandoffBypassesExhaustedAutomaticSnapshotAndPackingBudgets() throws Exception {
        try (Fixture f = new Fixture()) {
            f.config.generationSnapshotsPerTickLimit = 1;
            f.config.generationSnapshotBudgetMillis = 1;
            f.config.generationPauseAboveMspt = 1;
            f.addReady(true);
            f.budget.recordSnapshot(LIVE, 2_000_000);
            ((java.util.concurrent.atomic.AtomicLong) field("packingSnapshotBytes").get(f.service)).set(1_000_000_000);
            f.service.tick(f.server);
            assertTrue(f.service.diagnostics().contains("chunkySnapshotsThisTick=1"));
            assertTrue(f.service.diagnostics().contains("snapshotsThisTick=1"));
            assertFalse(f.budget.canSnapshot(GENERATED, 1, 1));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void automaticActiveAndQueuedCapsSelectIntegratedOrDedicatedSettings() throws Exception {
        try(Fixture f=new Fixture()) {
            var activeCounts=(Map<UUID,Integer>)field("perPlayerActiveCount").get(f.service);
            var queuedCounts=(Map<UUID,Integer>)field("perPlayerQueuedCount").get(f.service);
            var work=f.pending(false,10,false);
            activeCounts.put(f.regular,2);queuedCounts.put(f.regular,2);
            when(f.server.isDedicatedServer()).thenReturn(false);
            assertFalse((boolean)method("canStart",nested("PendingGeneration")).invoke(f.service,work));
            assertFalse((boolean)method("canQueue",UUID.class,ServerLevel.class).invoke(f.service,f.regular,f.level));
            when(f.server.isDedicatedServer()).thenReturn(true);
            assertTrue((boolean)method("canStart",nested("PendingGeneration")).invoke(f.service,work));
            assertTrue((boolean)method("canQueue",UUID.class,ServerLevel.class).invoke(f.service,f.regular,f.level));
            activeCounts.put(f.regular,4);queuedCounts.put(f.regular,4);
            assertFalse((boolean)method("canStart",nested("PendingGeneration")).invoke(f.service,work));
            assertFalse((boolean)method("canQueue",UUID.class,ServerLevel.class).invoke(f.service,f.regular,f.level));
            f.config.singleplayerGenerationConcurrencyLimit=5;
            when(f.server.isDedicatedServer()).thenReturn(false);
            assertTrue((boolean)method("canStart",nested("PendingGeneration")).invoke(f.service,work));
            assertTrue((boolean)method("canQueue",UUID.class,ServerLevel.class).invoke(f.service,f.regular,f.level));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void attachingToActiveGenerationUsesTheSameLocalLimitAndKeepsChunkyExempt() throws Exception {
        try(Fixture f=new Fixture()) {
            f.addReady(false);
            var counts=(Map<UUID,Integer>)field("perPlayerActiveCount").get(f.service);
            counts.put(f.regular,2);
            when(f.server.isDedicatedServer()).thenReturn(false);
            assertFalse(f.service.submitGeneration(f.regular,null,31,f.level,10,20,0));
            when(f.server.isDedicatedServer()).thenReturn(true);
            assertTrue(f.service.submitGeneration(f.regular,null,32,f.level,10,20,0));
            assertEquals(3,counts.get(f.regular));
            when(f.server.isDedicatedServer()).thenReturn(false);
            counts.put(f.chunky,100);
            assertTrue(f.service.submitGeneration(f.chunky,null,33,f.level,10,20,0));
        }
    }

    private static Class<?> nested(String name) {
        return Arrays.stream(ChunkGenerationService.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals(name)).findFirst().orElseThrow();
    }
    private static Field field(String name) throws Exception {
        Field field = ChunkGenerationService.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
    private static Method method(String name, Class<?>... types) throws Exception {
        Method method = ChunkGenerationService.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method;
    }

    private static final class Fixture implements AutoCloseable {
        final VSSServerConfig config = new VSSServerConfig();
        final MinecraftServer server = mock(MinecraftServer.class);
        final ServerLevel level = mock(ServerLevel.class);
        final ServerChunkCache chunks = mock(ServerChunkCache.class);
        final LevelChunk chunk = mock(LevelChunk.class);
        final UUID regular = UUID.randomUUID();
        final UUID chunky = UUID.randomUUID();
        final AtomicInteger tick = new AtomicInteger(1);
        final ChunkGenerationService service = new ChunkGenerationService(config);
        final GenerationTickBudget budget;
        final Map<Object, Object> active;
        final Map<Object, Object> queued;

        @SuppressWarnings("unchecked")
        Fixture() throws Exception {
            config.enableChunkGeneration = true;
            config.generationConcurrencyLimitGlobal = 32;
            when(server.getTickCount()).thenAnswer(ignored -> tick.get());
            when(server.getAverageTickTimeNanos()).thenReturn(10_000_000L);
            when(server.getPlayerList()).thenReturn(mock(PlayerList.class));
            when(level.getServer()).thenReturn(server);
            var registries = mock(net.minecraft.core.RegistryAccess.class);
            when(level.registryAccess()).thenReturn(registries);
            when(registries.registryOrThrow(net.minecraft.core.registries.Registries.BIOME))
                    .thenReturn(mock(net.minecraft.core.Registry.class));
            when(level.dimension()).thenReturn(Level.OVERWORLD);
            when(level.getChunkSource()).thenReturn(chunks);
            when(level.dimensionType()).thenReturn(mock(DimensionType.class));
            when(level.getLightEngine()).thenReturn(mock(LevelLightEngine.class));
            when(chunk.getSections()).thenReturn(new LevelChunkSection[0]);
            when(chunks.getChunkNow(anyInt(), anyInt())).thenReturn(chunk);
            budget = (GenerationTickBudget) field("tickBudget").get(service);
            budget.beginTick(1, 10, 0);
            active = (Map<Object, Object>) field("active").get(service);
            queued = (Map<Object, Object>) field("queued").get(service);
            service.registerBackgroundOwner(chunky);
        }
        boolean live(int request, boolean priority) {
            return service.submitLoadedColumn(regular, null, request, level, chunk, 30 + request, 40, 0L, priority);
        }
        Object pending(boolean explicit, int x, boolean priority) throws Exception {
            Constructor<?> constructor = nested("PendingGeneration").getDeclaredConstructor(ChunkPos.class, ServerLevel.class, long.class);
            constructor.setAccessible(true);
            Object pending = constructor.newInstance(new ChunkPos(x, 20), level, 0L);
            Field callbacks = nested("PendingGeneration").getDeclaredField("callbacks");
            callbacks.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<ChunkGenerationService.GenerationCallback> list = (List<ChunkGenerationService.GenerationCallback>) callbacks.get(pending);
            list.add(new ChunkGenerationService.GenerationCallback(explicit ? chunky : regular, null, x, priority));
            return pending;
        }
        Object key(int x) throws Exception {
            Constructor<?> constructor = nested("PendingGenerationKey").getDeclaredConstructor(net.minecraft.resources.ResourceKey.class, int.class, int.class);
            constructor.setAccessible(true);
            return constructor.newInstance(Level.OVERWORLD, x, 20);
        }
        void addReady(boolean explicit) throws Exception {
            active.put(key(10), pending(explicit, 10, false));
        }
        void enqueue(Object pending, int x) throws Exception {
            method("enqueueGeneration", nested("PendingGenerationKey"), nested("PendingGeneration")).invoke(service, key(x), pending);
        }
        boolean start(Object pending) throws Exception {
            return (boolean) method("tryStartThisTick", nested("PendingGeneration")).invoke(service, pending);
        }
        public void close() {
            // The mocked world owns no real tickets.
            active.clear();
            queued.clear();
            service.shutdown();
        }
    }
}
