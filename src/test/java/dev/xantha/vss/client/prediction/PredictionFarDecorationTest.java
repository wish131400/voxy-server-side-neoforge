package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.util.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.*;

class PredictionFarDecorationTest {
    @BeforeAll static void bootstrap() { dev.xantha.vss.testutil.TerrainPredictionFixtures.bootstrap(); }

    @Test void entireFootprintMustBeBeyondThePromotionBand() {
        assertFalse(PredictionFarPolicy.outside(0,0,4352,0,64,4096));
        assertTrue(PredictionFarPolicy.outside(0,0,4353,0,64,4096));
        assertFalse(PredictionFarPolicy.outside(0,0,4096,0,1024,4096));
        assertFalse(PredictionFarPolicy.outside(0,0,-4416,0,64,4096));
        assertTrue(PredictionFarPolicy.outside(0,0,-4417,0,64,4096));
        assertFalse(PredictionFarPolicy.outside(10000,10000,9984,9984,1024,4096));
        assertTrue(PredictionFarPolicy.outside(0,0,4096,4096,64,4096));
    }

    static PredictionVoxySource.Tile tile(boolean complete,boolean approximate) {
        var key=new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,80,0,0);
        return new PredictionVoxySource.Tile(key,5120,0,64,1,64,
                new PredictionVoxySource.Column[]{new PredictionVoxySource.Column(
                        List.of(new PredictionVoxySource.Run(0,64,Blocks.STONE.defaultBlockState())),"minecraft:plains")},
                List.of(new PredictionVoxySource.Box(0,64,0,4,72,4,Blocks.OAK_LOG.defaultBlockState())),
                128,complete,0,null,approximate);
    }

    @Test void retainedAppearanceKeepsItsQualityAndExactCompletionPromotesIt() {
        var far=tile(true,true);
        var refined=tile(false,false).retainFeatures(far);
        assertTrue(refined.approximateDecorations());
        assertTrue(refined.withVegetation(PredictionVegetation.Tile.EMPTY,null,true).surfaceComplete());
        assertFalse(refined.withVegetation(PredictionVegetation.Tile.EMPTY,null,true).approximateDecorations());
        var exact=tile(true,false);
        assertFalse(exact.reuseContent(far).approximateDecorations());
        assertNotSame(far,exact.reuseContent(far),"Equal geometry cannot erase an exact quality upgrade");
    }

    @Test void sourcePublishesExactQualityEvenWhenGeometryMatches() {
        try(var source=new PredictionVoxySource()) {
            source.put(tile(true,true));
            var exact=source.put(tile(true,false),()->true);
            assertNotNull(exact);assertFalse(exact.approximateDecorations());
        }
    }

    @Test void exactDiskWriterRejectsApproximationBeforeWritingAnyBytes() {
        var out=new ByteArrayOutputStream();
        var data=new PredictionVoxelCache.Data(tile(true,true),new int[0],null,
                new ClientColumnSample[0],new PredictionDepthBound(0,72));
        assertThrows(IOException.class,()->PredictionVoxelCache.write(new DataOutputStream(out),data,0,
                new byte[32],null,null));
        assertEquals(0,out.size());
    }

    @Test @SuppressWarnings("unchecked") void promotionBypassesCompletedReadinessAndKeepsOldGeometry() throws Exception {
        var config=dev.xantha.vss.config.VSSClientConfig.CONFIG;
        boolean enabled=config.predictionFarDecorations;
        int radius=config.predictionExactDistanceBlocks;
        config.predictionFarDecorations=true;config.predictionExactDistanceBlocks=4096;
        var sampler=org.mockito.Mockito.mock(RustTerrainSampler.class);
        org.mockito.Mockito.when(sampler.profile()).thenReturn(new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                Level.OVERWORLD.location(),42,-64,384,"noise","minecraft:overworld",1));
        org.mockito.Mockito.when(sampler.decorationContext()).thenReturn(sampler);
        org.mockito.Mockito.when(sampler.cacheBiomes()).thenReturn(List.of());
        try(var manager=new PredictionTileManager(Level.OVERWORLD,sampler,new PredictionMemoryBudget(
                512L*PredictionMemoryBudget.MIB,0,()->Long.MAX_VALUE,System::nanoTime,1),null)) {
            field("layout").set(manager,VssLodLayout.of(8192,2,true,false));
            field("unifiedVoxy").setBoolean(manager,true);
            var far=tile(true,true);var key=far.key();
            var payload=PredictionVoxelData.forVoxy(far);
            var resident=new PredictionTileManager.PredictionTile(key,new int[0],new int[0],new ClientColumnSample[0],
                    payload,new PredictionDepthBound(0,72),0,1,1,64);
            ((Map<Object,Object>)field("ready").get(manager)).put(key,resident);
            ((Set<Object>)field("surfaceReady").get(manager)).add(key);
            ((Set<Object>)field("desiredKeys").get(manager)).add(key);
            assertEquals(false,call(manager,"needsExactSurface",key));
            field("cameraBlockX").setDouble(manager,1024);
            assertEquals(true,call(manager,"needsExactSurface",key));
            call(manager,"refreshFarSurfaces");
            assertFalse(((Set<?>)field("surfaceReady").get(manager)).contains(key));
            assertEquals(true,call(manager,"surfaceBuildReady",key));
            assertSame(resident,((Map<?,?>)field("ready").get(manager)).get(key),"Promotion must retain old geometry while computing");
            var memory=(PredictionMemoryBudget)field("memoryBudget").get(manager);
            var publish=PredictionTileManager.class.getDeclaredMethod("publishTileLocked",
                    PredictionTileManager.PredictionTile.class,PredictionMemoryBudget.Reservation.class,
                    long.class,long.class,boolean.class,boolean.class,boolean.class,PredictionRelief.class);
            publish.setAccessible(true);
            long revision=((java.util.concurrent.atomic.AtomicLong)field("meshRevision").get(manager)).get();
            try(var claim=memory.tryReserveBuild()) {
                assertNotNull(claim);
                assertEquals(false,publish.invoke(manager,resident,claim,revision,0L,true,false,false,null),
                        "An old far job must not publish after entering the exact band");
            }
            field("cameraBlockX").setDouble(manager,0);
            var exactResident=new PredictionTileManager.PredictionTile(key,new int[0],new int[0],new ClientColumnSample[0],
                    PredictionVoxelData.forVoxy(tile(true,false)),new PredictionDepthBound(0,72),0,2,1,64);
            ((Map<Object,Object>)field("ready").get(manager)).put(key,exactResident);
            try(var claim=memory.tryReserveBuild()) {
                assertNotNull(claim);
                assertEquals(false,publish.invoke(manager,resident,claim,revision,0L,true,false,false,null),
                        "A late far result must not downgrade a completed exact surface");
            }
        } finally { config.predictionFarDecorations=enabled;config.predictionExactDistanceBlocks=radius; }
    }
    private static java.lang.reflect.Field field(String name)throws Exception {
        var f=PredictionTileManager.class.getDeclaredField(name);f.setAccessible(true);return f;
    }
    private static Object call(Object target,String name,Object... args)throws Exception {
        var types=args.length==0?new Class<?>[0]:new Class<?>[]{PredictionTileManager.PredictionTileKey.class};
        var m=PredictionTileManager.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(target,args);
    }
}
