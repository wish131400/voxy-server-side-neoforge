package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import dev.xantha.vss.testutil.TerrainPredictionFixtures;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.*;

class PredictionCacheDecodeTest {
    @BeforeAll static void bootstrap() { TerrainPredictionFixtures.bootstrap(); }
    private static ClientWorldgenProfileDecoder.Session session() throws Exception {
        var document=LithostitchedNativeTest.document();var registries=TerrainPredictionFixtures.emptyRegistries();var generator=new JsonObject();
        registries.add("biomes",document.get("biomes"));
        for(String key:List.of("noises","density_functions"))registries.add(key,document.get(key));
        for(String key:List.of("settings","biome_source"))generator.add(key,document.get(key));
        byte[] data=generator.toString().getBytes(StandardCharsets.UTF_8),registry=registries.toString().getBytes(StandardCharsets.UTF_8);
        var profile=new DimensionProfile(Level.OVERWORLD.location(),42,-64,384,"noise","minecraft:overworld",123,0,data.length,data);
        var payload=new WorldgenProfileS2CPayload(WorldgenProfileS2CPayload.FORMAT_VERSION,42,1,0,registry.length,registry,List.of(profile));
        var session=ClientWorldgenProfileDecoder.prepare(payload,TerrainPredictionFixtures.access(),()->true);
        // Supply the exported resource colors without creating a Minecraft window.
        var inputsField=ClientWorldgenProfileDecoder.Session.class.getDeclaredField("shared");inputsField.setAccessible(true);
        var inputs=(RustWorldgenDocument.SharedInputs)inputsField.get(session);inputs.prepare();
        var colors=new JsonObject();for(String key:List.of("grass_colormap","foliage_colormap"))colors.add(key,document.get(key));
        var colorsField=RustWorldgenDocument.SharedInputs.class.getDeclaredField("colors");colorsField.setAccessible(true);colorsField.set(inputs,colors);
        return session;
    }
    @Test void cacheContextPublishesBeforeFullRustContextWithTheSamePaletteAndVoxelIdentity() throws Exception {
        var stages=new ArrayList<String>();var owned=new ArrayList<ClientTerrainSampler>();
        try(var session=session()) {
            session.decode(Level.OVERWORLD,()->true,(key,full)->{
                owned.add(full);stages.add("generation");assertInstanceOf(RustTerrainSampler.class,full);
                var cached=owned.get(0);assertEquals(cached.cacheBiomes(),full.cacheBiomes());
                assertEquals(cached.predictionCacheNamespace(),full.predictionCacheNamespace());
                assertArrayEquals(PredictionVoxelCache.identity(cached),PredictionVoxelCache.identity(full));
            },(key,cached)->{
                owned.add(cached);stages.add("cache");assertInstanceOf(PredictionCacheSampler.class,cached);
                assertNull(cached.generatorContext());assertNull(cached.randomStateContext());
            });
            assertEquals(List.of("cache","generation"),stages);
        } finally {owned.forEach(PredictionResources::releaseSampler);}
    }
    @Test void cancellationAfterCachePublicationDoesNotPublishOrAllocateAFullBackend() throws Exception {
        var active=new AtomicBoolean(true);var owned=new ArrayList<ClientTerrainSampler>();
        try(var session=session()) {
            assertThrows(java.util.concurrent.CancellationException.class,()->session.decode(Level.OVERWORLD,active::get,
                    (key,full)->{owned.add(full);fail("Cancelled cache visit cannot publish a generation backend");},
                    (key,cached)->{owned.add(cached);active.set(false);}));
            assertEquals(1,owned.size());assertInstanceOf(PredictionCacheSampler.class,owned.get(0));
        } finally {owned.forEach(PredictionResources::releaseSampler);}
    }
    @Test void cancellationDuringNativeOpenReleasesTheUnpublishedJavaContext() throws Exception {
        var cancelled = new java.util.concurrent.CancellationException("native construction superseded");
        var context = new java.util.concurrent.atomic.AtomicReference<ClientTerrainSampler>();
        try (var session = session();
                var nativeOpen = org.mockito.Mockito.mockStatic(RustTerrainSampler.class);
                var resources = org.mockito.Mockito.mockStatic(PredictionResources.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            nativeOpen.when(() -> RustTerrainSampler.open(org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
                context.set(call.getArgument(3));
                throw cancelled;
            });
            assertSame(cancelled, assertThrows(java.util.concurrent.CancellationException.class,
                    () -> session.decode(Level.OVERWORLD, () -> true,
                            (key, sampler) -> fail("An aborted native context cannot be published"))));
            assertNotNull(context.get(), "the fixture must reach native construction with an owned Java context");
            resources.verify(() -> PredictionResources.releaseSampler(context.get()), org.mockito.Mockito.times(1));
        }
    }

}
