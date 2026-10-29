package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.xantha.vss.compat.VoxyPredictionBridge;
import dev.xantha.vss.config.VSSClientConfig;
import dev.xantha.vss.networking.client.VSSClientNetworking;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.*;

class PredictionDimensionLifecycleTest {
    private boolean enabled,loading,remember;
    private final BlockingQueue<Runnable> clientTasks=new LinkedBlockingQueue<>();
    private static DimensionProfile PROFILE;

    @BeforeAll static void bootstrap(){ClientTerrainSamplerTest.bootstrapMinecraft();
        PROFILE=new DimensionProfile(Level.OVERWORLD.location(),42,-64,384,"noise","minecraft:overworld",1);}
    @BeforeEach void setup() throws Exception {
        enabled=VSSClientConfig.CONFIG.enablePrediction;loading=VSSClientConfig.CONFIG.enablePredictionLoading;
        remember=VSSClientConfig.CONFIG.rememberTerrain;
        VSSClientConfig.CONFIG.enablePrediction=true;VSSClientConfig.CONFIG.enablePredictionLoading=true;
        VSSClientConfig.CONFIG.rememberTerrain=false;ClientPredictionState.clear();drainDecoder();
    }
    @AfterEach void cleanup() throws Exception {
        runClientTasks();ClientPredictionState.clear();drainDecoder();PredictionResources.awaitRetired();
        VSSClientConfig.CONFIG.enablePrediction=enabled;VSSClientConfig.CONFIG.enablePredictionLoading=loading;
        VSSClientConfig.CONFIG.rememberTerrain=remember;
    }

    @Test void clientUnloadClosesManagersButPreservesTheProfileNeededForReturning() throws Exception {
        var world=level(Level.OVERWORLD);var manager=mock(PredictionTileManager.class);
        managers().put(Level.OVERWORLD,manager);set("profileReady",true);
        var payload=profile();set("acceptedProfile",payload);
        var profiles=(PredictionDimensionProfiles)get("dimensionProfiles");profiles.remember(payload);
        VSSClientNetworking.onClientLevelUnload(new net.neoforged.neoforge.event.level.LevelEvent.Unload(mock(Level.class)));
        verify(manager,never()).close();assertEquals(1,managers().size(),"Server-level unload cannot retire client prediction");
        unload(world);
        verify(manager).close();assertTrue(managers().isEmpty());assertEquals(false,get("profileReady"));
        assertSame(payload,get("acceptedProfile"));assertSame(payload,profiles.restore(Level.OVERWORLD,null));
        assertFalse(VoxyPredictionBridge.isLevelActive(world));
        assertTrue(VoxyPredictionBridge.isLevelActive(level(Level.OVERWORLD)),"A new ClientLevel for the same dimension is a new visit");
    }

    @Test void unloadCancelsTheOldDecodeAndItsCompletionCannotClearANewInitialization() throws Exception {
        var minecraft=minecraft(level(Level.OVERWORLD));var decoder=prepare();
        var oldStarted=new CountDownLatch(1);var oldResume=new CountDownLatch(1);
        var newStarted=new CountDownLatch(1);var newResume=new CountDownLatch(1);
        var calls=new AtomicInteger();var oldActive=new AtomicReference<BooleanSupplier>();
        doAnswer(call->{
            BooleanSupplier active=call.getArgument(1);
            if(calls.incrementAndGet()==1){oldActive.set(active);oldStarted.countDown();await(oldResume);}
            else {assertTrue(active.getAsBoolean());newStarted.countDown();await(newResume);}
            return null;
        }).when(decoder).decode(any(),any(),any(),any());
        try {
            request(minecraft);assertTrue(oldStarted.await(5,TimeUnit.SECONDS));
            unload(minecraft.level);assertFalse(oldActive.get().getAsBoolean());
            request(minecraft);assertEquals(1,calls.get(),"Forced loading-screen ticks cannot restart the unloaded level");
            minecraft.level=level(Level.NETHER);request(minecraft);
            oldResume.countDown();assertTrue(newStarted.await(5,TimeUnit.SECONDS));runClientTasks();
            assertEquals(true,get("dimensionInitializing"),"Old finally callback cannot unlock the new visit");
            newResume.countDown();drainDecoder();runClientTasks();
            assertEquals(false,get("dimensionInitializing"));assertEquals(2,calls.get());
        } finally {oldResume.countDown();newResume.countDown();drainDecoder();}
    }

    @Test void aQueuedInstallFromTheOldVisitIsDiscardedAndReturningCanInitializeAgain() throws Exception {
        var minecraft=minecraft(level(Level.OVERWORLD));var decoder=prepare();
        var closed=new AtomicInteger();
        doAnswer(call->{
            BiConsumer<ResourceKey<Level>,ClientTerrainSampler> ready=call.getArgument(2);
            ready.accept(Level.OVERWORLD,new ClientTerrainSampler(42,PROFILE){
                @Override public void close(){super.close();closed.incrementAndGet();}
            });return null;
        }).when(decoder).decode(any(),any(),any(),any());
        request(minecraft);drainDecoder();assertTrue(clientTasks.size()>=2);
        unload(minecraft.level);minecraft.level=level(Level.OVERWORLD);
        runClientTasks();PredictionResources.awaitRetired();
        assertTrue(managers().isEmpty(),"Matching dimension keys cannot revive an install from a previous visit");
        assertEquals(1,closed.get());
        request(minecraft);drainDecoder();runClientTasks();assertEquals(1,managers().size());
        assertEquals(true,get("profileReady"));
        unload(minecraft.level);PredictionResources.awaitRetired();assertEquals(2,closed.get());
    }

    @Test void cacheInstallRunsWhileGenerationIsBlockedAndAnOldVisitCannotPromoteOnReturn(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        VSSClientConfig.CONFIG.rememberTerrain=true;
        var storage=new PredictionCacheStorage(directory,null);
        try(var disk=storage.open(new ClientTerrainSampler(42,PROFILE))) {assertNotNull(disk);}
        var minecraft=minecraft(level(Level.OVERWORLD));var decoder=prepare(storage);
        var cacheReady=new CountDownLatch(1);var resume=new CountDownLatch(1);var fullClosed=new AtomicInteger();
        doAnswer(call->{
            BiConsumer<ResourceKey<Level>,ClientTerrainSampler> cached=call.getArgument(3);
            cached.accept(Level.OVERWORLD,PredictionCacheStartupTest.cacheSampler(PROFILE));cacheReady.countDown();await(resume);
            BiConsumer<ResourceKey<Level>,ClientTerrainSampler> ready=call.getArgument(2);
            ready.accept(Level.OVERWORLD,new ClientTerrainSampler(42,PROFILE) {
                @Override public void close(){super.close();fullClosed.incrementAndGet();}
            });return null;
        }).when(decoder).decode(any(),any(),any(),any());
        try {
            request(minecraft);assertTrue(cacheReady.await(5,TimeUnit.SECONDS));runClientTasks();
            var early=managers().get(Level.OVERWORLD);assertNotNull(early);assertFalse(early.generationReady());
            assertEquals(true,get("profileReady"),"Cache display must not wait for full generation initialization");
            unload(minecraft.level);minecraft.level=level(Level.OVERWORLD);
            resume.countDown();drainDecoder();runClientTasks();drainDecoder();PredictionResources.awaitRetired();
            assertTrue(managers().isEmpty(),"A completed old sampler cannot attach to a new visit to the same dimension");
            assertEquals(1,fullClosed.get());assertFalse(early.generationReady());
        } finally {resume.countDown();drainDecoder();}
    }

    @Test void completedBackendPromotesTheInstalledCacheManagerInPlace(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        VSSClientConfig.CONFIG.rememberTerrain=true;
        var storage=new PredictionCacheStorage(directory,null);
        try(var disk=storage.open(new ClientTerrainSampler(42,PROFILE))) {assertNotNull(disk);}
        var minecraft=minecraft(level(Level.OVERWORLD));var decoder=prepare(storage);
        var cacheReady=new CountDownLatch(1);var resume=new CountDownLatch(1);var fullClosed=new AtomicInteger();
        var full=new ClientTerrainSampler(42,PROFILE) {
            @Override public void close(){super.close();fullClosed.incrementAndGet();}
        };
        doAnswer(call->{
            BiConsumer<ResourceKey<Level>,ClientTerrainSampler> cached=call.getArgument(3);
            cached.accept(Level.OVERWORLD,PredictionCacheStartupTest.cacheSampler(PROFILE));
            cacheReady.countDown();await(resume);
            BiConsumer<ResourceKey<Level>,ClientTerrainSampler> ready=call.getArgument(2);
            ready.accept(Level.OVERWORLD,full);return null;
        }).when(decoder).decode(any(),any(),any(),any());
        try {
            request(minecraft);assertTrue(cacheReady.await(5,TimeUnit.SECONDS));runClientTasks();
            var early=managers().get(Level.OVERWORLD);assertNotNull(early);assertFalse(early.generationReady());
            var disk=early.diskCache();long fingerprint=early.sampler().cacheFingerprint();
            resume.countDown();drainDecoder();
            assertFalse(early.generationReady(),"Only the client installation callback can promote the manager");
            runClientTasks();
            assertSame(early,managers().get(Level.OVERWORLD));assertTrue(early.generationReady());
            assertSame(full,early.sampler());assertSame(disk,early.diskCache());
            assertEquals(fingerprint,full.cacheFingerprint());assertEquals(0,fullClosed.get());
            assertEquals(false,get("dimensionInitializing"));
            unload(minecraft.level);PredictionResources.awaitRetired();assertEquals(1,fullClosed.get());
        } finally {resume.countDown();drainDecoder();}
    }

    private static WorldgenProfileS2CPayload profile(){
        return new WorldgenProfileS2CPayload(WorldgenProfileS2CPayload.FORMAT_VERSION,42,1,0,1,new byte[]{1},
                List.of(PROFILE,new DimensionProfile(Level.NETHER.location(),42,0,256,"noise","minecraft:nether",1)));
    }
    private ClientWorldgenProfileDecoder.Session prepare() throws Exception {
        return prepare(null);
    }
    private ClientWorldgenProfileDecoder.Session prepare(PredictionCacheStorage storage) throws Exception {
        var decoder=mock(ClientWorldgenProfileDecoder.Session.class);
        Class<?> type=Class.forName(ClientPredictionState.class.getName()+"$PreparedProfile");
        var constructor=type.getDeclaredConstructors()[0];constructor.setAccessible(true);
        set("preparedProfile",constructor.newInstance(decoder,((AtomicLong)get("DECODE_GENERATION")).get(),storage));
        set("acceptedProfile",profile());return decoder;
    }
    private Minecraft minecraft(ClientLevel level){
        var minecraft=mock(Minecraft.class);minecraft.level=level;
        doAnswer(call->{clientTasks.add(call.getArgument(0));return null;}).when(minecraft).execute(any(Runnable.class));
        return minecraft;
    }
    private static ClientLevel level(ResourceKey<Level> key){
        var level=mock(ClientLevel.class);when(level.dimension()).thenReturn(key);return level;
    }
    private static void unload(ClientLevel level){VSSClientNetworking.onClientLevelUnload(new net.neoforged.neoforge.event.level.LevelEvent.Unload(level));}
    private void runClientTasks(){Runnable task;while((task=clientTasks.poll())!=null)task.run();}
    private static void request(Minecraft minecraft) throws Exception {
        var method=ClientPredictionState.class.getDeclaredMethod("requestCurrentDimension",Minecraft.class,long.class);
        method.setAccessible(true);method.invoke(null,minecraft,System.nanoTime());
    }
    private static void drainDecoder() throws Exception {((ExecutorService)get("PROFILE_DECODER")).submit(()->{}).get(10,TimeUnit.SECONDS);}
    private static void await(CountDownLatch latch) throws InterruptedException {assertTrue(latch.await(5,TimeUnit.SECONDS));}
    @SuppressWarnings("unchecked") private static Map<ResourceKey<Level>,PredictionTileManager> managers() throws Exception {
        return (Map<ResourceKey<Level>,PredictionTileManager>)get("MANAGERS");
    }
    private static Object get(String name) throws Exception {
        var field=ClientPredictionState.class.getDeclaredField(name);field.setAccessible(true);return field.get(null);
    }
    private static void set(String name,Object value) throws Exception {
        var field=ClientPredictionState.class.getDeclaredField(name);field.setAccessible(true);field.set(null,value);
    }
}
