package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.compat.VoxyPredictionBridge;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.*;

class PredictionStutterRepairTest {
    @BeforeAll static void bootstrap() {ClientTerrainSamplerTest.bootstrapMinecraft();}
    private static PredictionVoxySource.Tile tile(int x,int z,int lod,int step,boolean complete,boolean plants) {
        int span=64<<lod,axis=span/step;
        var columns=new PredictionVoxySource.Column[axis*axis];
        Arrays.fill(columns,new PredictionVoxySource.Column(List.of(new PredictionVoxySource.Run(0,16,
                Blocks.STONE.defaultBlockState())),"minecraft:plains"));
        var features=plants?List.of(new PredictionVoxySource.Box(0,16,0,1,20,1,Blocks.OAK_LEAVES.defaultBlockState())):
                List.<PredictionVoxySource.Box>of();
        return new PredictionVoxySource.Tile(new PredictionTileKey(Level.OVERWORLD,x,z,lod),x*span,z*span,span,axis,step,
                columns,features,256,complete,0);
    }
    @Test void indexedEvidenceMatchesIndependentPointOwnershipAcrossLevelsAndNegativeCoordinates() {
        var random=new Random(20261028);
        for(int round=0;round<24;round++) {
            var before=tile(-1,-1,2,16,random.nextBoolean(),random.nextBoolean());
            var after=tile(-1,-1,2,8,random.nextBoolean(),random.nextBoolean());
            var peers=new ArrayList<PredictionVoxySource.Tile>();peers.add(after);
            for(int i=0;i<30;i++) {
                int lod=random.nextInt(3),x=-4+random.nextInt(5),z=-4+random.nextInt(5);
                peers.add(tile(x,z,lod,1<<random.nextInt(5),random.nextBoolean(),random.nextBoolean()));
            }
            boolean terrain=random.nextBoolean();
            var change=new PredictionVoxyChange(before,after,peers,terrain);
            for(int lod=0;lod<=5;lod++)for(int z=-3;z<=1;z++)for(int x=-3;x<=1;x++) {
                long pos=VoxyPredictionBridge.position(lod,x,round-12,z);
                boolean expected=contributes(before,pos,false,terrain,peers)||contributes(after,pos,false,terrain,peers)
                        ||contributes(before,pos,true,terrain,peers)||contributes(after,pos,true,terrain,peers);
                assertEquals(expected,change.test(pos),"round="+round+" lod="+lod+" x="+x+" z="+z);
            }
        }
    }
    // Deliberately use individual sample points as an oracle, independent of masks and indexing.
    private static boolean contributes(PredictionVoxySource.Tile tile,long pos,boolean plants,boolean terrain,
            List<PredictionVoxySource.Tile> peers) {
        if(plants?!tile.surfaceComplete()&&!tile.hasVegetation():!terrain&&tile.cityFeatures()==0)return false;
        int step=1<<VoxyPredictionBridge.level(pos);
        long bx=(long)VoxyPredictionBridge.x(pos)*32*step-step,bz=(long)VoxyPredictionBridge.z(pos)*32*step-step;
        for(int z=0;z<34;z++)for(int x=0;x<34;x++) {
            long wx=bx+(long)x*step+step/2,wz=bz+(long)z*step+step/2;
            if(!covers(tile,wx,wz))continue;
            boolean hidden=false;
            for(var peer:peers) {
                if(peer.key().equals(tile.key()) || !covers(peer,wx,wz))continue;
                if(plants && !peer.surfaceComplete())continue;
                boolean higher=plants&&peer.surfaceComplete()!=tile.surfaceComplete()?peer.surfaceComplete():
                        peer.step()<tile.step()||peer.step()==tile.step()&&peer.key().lod()<tile.key().lod();
                if(higher) {hidden=true;break;}
            }
            if(!hidden)return true;
        }
        return false;
    }
    private static boolean covers(PredictionVoxySource.Tile tile,long x,long z) {
        return x>=tile.baseX()&&z>=tile.baseZ()&&x<(long)tile.baseX()+tile.span()&&z<(long)tile.baseZ()+tile.span();
    }
    @Test void denseForestPublicationReusesHorizontalEvidenceInsteadOfScanningAllPeersForEveryFaceAndHeight() {
        var coarse=tile(0,0,6,64,true,true);var peers=new ArrayList<PredictionVoxySource.Tile>();
        for(int z=0;z<64;z++)for(int x=0;x<64;x++)peers.add(tile(x,z,0,1,true,true));
        var change=new PredictionVoxyChange(coarse,null,peers,true);
        long started=System.nanoTime();
        for(int y=-4;y<12;y++)for(int face=0;face<7;face++)for(int z=8;z<24;z++)for(int x=8;x<24;x++)
            assertFalse(change.test(VoxyPredictionBridge.position(0,x,y,z)));
        assertEquals(256,change.horizontalChecks());
        assertTrue(change.peerVisits()<4096,"Only local ownership buckets should be evaluated");
        System.out.println("VSS_PUBLICATION_LOCALITY={\"peerTiles\":4096,\"calls\":28672,\"horizontalChecks\":"+
                change.horizontalChecks()+",\"peerVisits\":"+change.peerVisits()+",\"queryMs\":"+(System.nanoTime()-started)/1e6+"}");
    }
    @Test void cacheIndexPreservesSharedFaceMembershipAndQueriesOnlyLocalSections() {
        var index=new PredictionSectionIndex();var all=new ArrayList<Long>();
        for(int lod=0;lod<6;lod++)for(int z=-18;z<18;z++)for(int x=-18;x<18;x++) {
            long pos=VoxyPredictionBridge.position(lod,x,(x+z)%5,z);all.add(pos);
            for(int face=0;face<7;face++)index.add(pos);
        }
        for(long pos:all)for(int face=0;face<6;face++)index.remove(pos);
        assertEquals(all.size(),index.size());
        long visited=index.candidates();
        var found=new HashSet<Long>(index.intersect(-33,-100,-33,1,100,1));
        var expected=new HashSet<Long>();
        for(long pos:all) {
            long span=32L<<VoxyPredictionBridge.level(pos),x=(long)VoxyPredictionBridge.x(pos)*span,
                    y=(long)VoxyPredictionBridge.y(pos)*span,z=(long)VoxyPredictionBridge.z(pos)*span;
            if(x<1&&y<100&&z<1&&x+span>-33&&y+span>-100&&z+span>-33)expected.add(pos);
        }
        assertEquals(expected,found);assertTrue(index.candidates()-visited<80);
        for(long pos:all)index.remove(pos);
        assertEquals(0,index.size());assertTrue(index.intersect(-100,-100,-100,100,100,100).isEmpty());
    }
    @Test @SuppressWarnings("unchecked")
    void mainThreadDemandHandoffNeverAcquiresBusySourceAndDoesNotLoseDrainedDemand() throws Exception {
        var samplerMethod=PredictionFirstCoverageTest.class.getDeclaredMethod("sampler",AtomicInteger.class,CountDownLatch.class,CountDownLatch.class);
        samplerMethod.setAccessible(true);var sampler=(ClientTerrainSampler)samplerMethod.invoke(null,new AtomicInteger(),null,null);
        var create=PredictionFirstCoverageTest.class.getDeclaredMethod("manager",ClientTerrainSampler.class,PredictionDiskCache.class);create.setAccessible(true);
        try(var manager=(PredictionTileManager)create.invoke(null,sampler,null)) {
            var field=PredictionTileManager.class.getDeclaredField("voxySource");field.setAccessible(true);
            var source=(PredictionVoxySource)field.get(manager);source.put(tile(0,0,1,8,false,false));
            source.requestMeshDetail(VoxyPredictionBridge.position(0,0,0,0),(x,z)->false);
            var refresh=PredictionTileManager.class.getDeclaredMethod("refreshVoxyDetails",boolean.class);refresh.setAccessible(true);
            synchronized(source) {
                assertTimeoutPreemptively(java.time.Duration.ofSeconds(1),()->{
                    for(int i=0;i<12;i++)refresh.invoke(manager,true);
                    // A source waiter must not own the manager monitor either.
                    synchronized(manager) {assertNotNull(manager.layout());}
                });
            }
            var targets=PredictionTileManager.class.getDeclaredField("voxyDetailTargets");targets.setAccessible(true);
            var key=new PredictionTileKey(Level.OVERWORLD,0,0,0);long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(!((Set<PredictionTileKey>)targets.get(manager)).contains(key)&&System.nanoTime()<until) {
                refresh.invoke(manager,false);Thread.sleep(2);
            }
            assertTrue(((Set<PredictionTileKey>)targets.get(manager)).contains(key),"Completed handoff must always be consumed");
        }
    }
    @Test void cacheMembershipSurvivesEvictionInvalidationAndMapperRetirement() throws Exception {
        VoxyPredictionBridge.Mapper mapper=(state,biome)->1L<<27;
        try(var source=new PredictionVoxySource(8,256*1024)) {
            source.put(tile(0,0,3,8,false,false));
            for(int round=0;round<3;round++) {
                for(int x=0;x<16;x++) {
                    long pos=VoxyPredictionBridge.position(0,x,0,0);source.mappedSection(pos,mapper);
                    for(int face=0;face<6;face++)source.copyFace(VoxyPredictionBridge.position(0,x,0,1),mapper,face,new long[1024]);
                }
                var diagnostics=source.diagnostics();
                assertTrue(((Number)diagnostics.get("indexedCachedPositions")).intValue()<=
                        ((Number)diagnostics.get("cachedSections")).intValue()+((Number)diagnostics.get("cachedFaces")).intValue());
                source.put(tile(round,0,0,1,true,true));source.remove(new PredictionTileKey(Level.OVERWORLD,round,0,0));
                source.releaseMapper(mapper);
                assertEquals(0,source.diagnostics().get("indexedCachedPositions"));
            }
        }
    }
    @Test void cacheBuiltBetweenPreparationAndCommitCannotRetainPrePublicationVoxels() {
        VoxyPredictionBridge.Mapper mapper=(state,biome)->state.is(Blocks.DIRT)?2L<<27:1L<<27;
        try(var source=new PredictionVoxySource()) {
            var before=tile(0,0,1,8,false,false);source.put(before);
            // Establish the mapper without caching any position in the changed region.
            assertNull(source.mappedSection(VoxyPredictionBridge.position(0,100,0,100),mapper));
            var columns=before.columns().clone();
            Arrays.fill(columns,new PredictionVoxySource.Column(List.of(new PredictionVoxySource.Run(0,16,
                    Blocks.DIRT.defaultBlockState())),"minecraft:plains"));
            var after=new PredictionVoxySource.Tile(before.key(),0,0,before.span(),before.axis(),before.step(),
                    columns,List.of(),256,false,0);
            long pos=VoxyPredictionBridge.position(0,0,0,0);var checks=new AtomicInteger();
            source.put(after,()->{
                if(checks.incrementAndGet()==3) {
                    assertEquals(1L<<27,source.section(pos,mapper)[0]&(0xfffffL<<27));
                    for(int face=0;face<6;face++)assertTrue(source.copyFace(
                            VoxyPredictionBridge.position(0,1,0,0),mapper,face,new long[1024]));
                }
                return true;
            });
            assertEquals(3,checks.get());
            assertEquals(0,source.diagnostics().get("indexedCachedPositions"),"Uncaptured old builds must be retired at commit");
            assertEquals(2L<<27,source.section(pos,mapper)[0]&(0xfffffL<<27));
        }
    }
    @Test void publicationRetriesChangedLocalPeersWithoutRetryingUnrelatedPublications() {
        try(var source=new PredictionVoxySource()) {
            var before=tile(0,0,1,8,false,false);source.put(before);
            // Three cells take the hash-bucket path; a fourth distant cell
            // switches intersect to coordinate order without changing local peers.
            source.put(tile(0,0,0,1,true,true));
            source.put(tile(0,1,0,1,true,true));
            source.put(tile(1,0,0,1,true,true));
            var after=tile(0,0,1,4,false,false);var checks=new AtomicInteger();
            source.put(after,()->{
                if(checks.incrementAndGet()==3)source.put(tile(1000,1000,0,1,false,false));
                return true;
            });
            assertEquals(3,checks.get(),"A distant publication cannot revoke local evidence");
            checks.set(0);
            source.put(before,()->{
                if(checks.incrementAndGet()==3)source.put(tile(1,1,0,1,true,true));
                return true;
            });
            assertEquals(6,checks.get(),"Changed local ownership must rebuild prepared evidence once");
        }
    }
}
