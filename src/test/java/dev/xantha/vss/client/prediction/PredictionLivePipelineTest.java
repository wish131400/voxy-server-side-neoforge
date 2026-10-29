package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.*;
import dev.xantha.vss.compat.VoxyPredictionBridge;
import dev.xantha.vss.client.prediction.PredictionTileManager.*;

class PredictionLivePipelineTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    @Test void coordinateHashesDistributeARegularGridInEveryDemandAndCacheKey() {
        for(int kind=0;kind<3;kind++) {
            var lowBits=new HashSet<Integer>();
            for(int x=0;x<4096;x++) {
                int h=switch(kind) {
                    case 0 -> new PredictionTileKey(Level.OVERWORLD,x,0,1).hashCode();
                    case 1 -> new PredictionVoxySource.Detail(x,0,1).hashCode();
                    default -> PredictionDiskCache.Key.terrain(x,0,1).hashCode();
                };
                lowBits.add(h&65535);
            }
            assertTrue(lowBits.size()>3500,"X coordinates must contribute to hash table bucket selection: "+lowBits.size());
        }
    }
    @Test void sharedColumnPalettePreservesEveryVoxelAndMapsEachMaterialOnce() {
        var palette=new PredictionVoxyColumns();var columns=new PredictionVoxySource.Column[4096];
        var plain=new PredictionVoxySource.Column[4096];
        for(int i=0;i<columns.length;i++) {
            var runs=List.of(new PredictionVoxySource.Run(-64,5,Blocks.STONE.defaultBlockState()),
                    new PredictionVoxySource.Run(5,10,Blocks.DIRT.defaultBlockState()));
            columns[i]=palette.column(runs,new String("minecraft:plains"));
            plain[i]=new PredictionVoxySource.Column(runs,"minecraft:plains");
        }
        assertSame(columns[0],columns[4095]);assertTrue(palette.bytes(4096)<40000);
        var key=new PredictionTileKey(Level.OVERWORLD,0,0,0);
        var count=new java.util.concurrent.atomic.AtomicInteger();
        VoxyPredictionBridge.Mapper mapper=(state,biome)->{count.incrementAndGet();return state.is(Blocks.STONE)?1L<<27:2L<<27;};
        try(var a=new PredictionVoxySource();var b=new PredictionVoxySource()) {
            a.put(new PredictionVoxySource.Tile(key,0,0,64,64,1,columns,List.of(),palette.bytes(4096)));
            b.put(new PredictionVoxySource.Tile(key,0,0,64,64,1,plain,List.of(),1000000));
            long pos=VoxyPredictionBridge.position(0,0,0,0);long[] expected=b.section(pos,mapper);count.set(0);
            assertArrayEquals(expected,a.section(pos,mapper));assertEquals(2,count.get());
        }
        var nullCount=new java.util.concurrent.atomic.AtomicInteger();
        var materials=new PredictionVoxyMaterials((state,biome)->{nullCount.incrementAndGet();return 15L<<56;});
        assertEquals(15L<<56,materials.map(null,null));assertEquals(15L<<56,materials.map(null,null));
        assertEquals(1,nullCount.get());
    }
    @Test void completedTerrainStaysResidentButLeavesTheBackgroundSortAndPlantsRemainEligible() {
        var layout=VssLodLayout.of(8192,6,true,false);
        var done=PredictionVoxelFixtures.tile(0,0,0,layout);var near=new PredictionTileKey(Level.OVERWORLD,1,0,0);
        var far=new PredictionTileKey(Level.OVERWORLD,40,0,0);
        var input=new PredictionVoxyWorkPreparation.Input(layout,null,null,0,80,0,1000,-64,384,8192,1,1,
                Set.of(done.key(),near,far),Set.of(done.key()),Set.of(),Set.of(),
                new RenderSnapshot(Level.OVERWORLD,layout,Map.of(done.key(),done),Map.of()),Map.of());
        var result=PredictionVoxyWorkPreparation.build(input);
        assertEquals(List.of(near,far),result.terrain());assertEquals(List.of(done.key()),result.surfaces());
    }
    @Test @SuppressWarnings("unchecked")
    void publicationReleasesTheManagerLockAndLateSourceWritesCannotResurrectRetiredOrReplacedTiles() throws Exception {
        var profile=new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                net.minecraft.resources.ResourceLocation.tryParse("minecraft:overworld"),42L,-64,384,"noise","minecraft:overworld",123L);
        for(String scenario:List.of("replacement","retirement","close")) {
            var sampler=new ClientTerrainSampler(profile.seed(),profile);
            try(var manager=new PredictionTileManager(Level.OVERWORLD,sampler,
                    new PredictionMemoryBudget(512L*PredictionMemoryBudget.MIB,0,()->Long.MAX_VALUE,System::nanoTime,1),null,
                    new PredictionCpuBudget(()->4, System::nanoTime))) {
                var tile=PredictionVoxelFixtures.tile(0,0,0,manager.layout());
                ((Set<PredictionTileKey>)get(manager,"desiredKeys")).add(tile.key());
                var ready=(Map<PredictionTileKey,PredictionTile>)get(manager,"ready");
                var source=(PredictionVoxySource)get(manager,"voxySource");
                var reservation=((PredictionMemoryBudget)get(manager,"memoryBudget")).tryReserve(tile.retainedHeapBytes()+1024);
                assertNotNull(reservation);
                var method=PredictionTileManager.class.getDeclaredMethod("publishTile",PredictionTile.class,
                        PredictionMemoryBudget.Reservation.class,long.class,long.class,boolean.class,boolean.class,boolean.class,PredictionRelief.class);
                method.setAccessible(true);
                var pool=Executors.newSingleThreadExecutor();Future<Boolean> task;
                try {
                    synchronized(source) {
                        task=pool.submit(()->(boolean)method.invoke(manager,tile,reservation,
                                ((AtomicLong)get(manager,"meshRevision")).get(),0L,false,false,true,PredictionRelief.of(tile)));
                        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                        while(ready.get(tile.key())!=tile && System.nanoTime()<deadline)Thread.onSpinWait();
                        assertSame(tile,ready.get(tile.key()));
                        var lockFree=new CountDownLatch(1);var check=new Thread(()->{synchronized(manager){lockFree.countDown();}});
                        check.start();assertTrue(lockFree.await(2,TimeUnit.SECONDS),"Source publication must not hold the manager monitor");check.join();
                        if(scenario.equals("replacement")) {
                            var next=PredictionVoxelFixtures.tile(0,0,0,manager.layout());ready.put(tile.key(),next);source.put(next.data().voxyTile);
                        } else if(scenario.equals("retirement")) {ready.remove(tile.key());source.remove(tile.key());}
                        else manager.close();
                    }
                    assertTrue(task.get(5,TimeUnit.SECONDS));
                    long pos=VoxyPredictionBridge.position(0,0,0,0);
                    if(scenario.equals("replacement"))assertEquals(1,source.mapRevision());
                    else assertNull(source.section(pos,(s,b)->1L<<27));
                } finally {pool.shutdownNow();}
            }
        }
    }
    private static Object get(Object target,String name)throws Exception {
        var field=PredictionTileManager.class.getDeclaredField(name);field.setAccessible(true);return field.get(target);
    }
}
