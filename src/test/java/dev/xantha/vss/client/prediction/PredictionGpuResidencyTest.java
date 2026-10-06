package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class PredictionGpuResidencyTest {
    @Test void unchangedCpuSnapshotRequeuesEvictedDetailAndKeepsItsDrawableParent() {
        PredictionMeshCodecTest.bootstrap();
        var layout = VssLodLayout.of(8192,6,true,false);
        var fine = tile(-9,-5,0,layout);
        var parent = tile(-5,-3,1,layout);
        var source = new PredictionTileManager.RenderSnapshot(net.minecraft.world.level.Level.OVERWORLD,layout,
                java.util.Map.of(fine.key(),fine,parent.key(),parent),java.util.Map.of());
        var residency = new PredictionRenderResidency();residency.retain(source);
        residency.uploaded(fine);residency.uploaded(parent);
        assertTrue(residency.pendingUploads(source).isEmpty());
        assertTrue(residency.hasFallback(fine.key()));assertFalse(residency.hasFallback(parent.key()));
        var before = residency.snapshot(source);long revision = residency.revision();
        residency.evict(fine.key());
        var after = residency.snapshot(source);
        assertTrue(residency.revision()>revision);assertSame(parent,after.tiles().get(parent.key()));
        assertFalse(after.tiles().containsKey(fine.key()));
        assertTrue(after.epoch(parent.key())>before.epoch(parent.key()));
        assertEquals(java.util.List.of(fine),residency.pendingUploads(source));
        residency.uploaded(fine);assertTrue(residency.contains(fine));assertTrue(residency.pendingUploads(source).isEmpty());
    }
    private static PredictionTileManager.PredictionTile tile(int x, int z, int lod, VssLodLayout layout) {
        var mesh = PredictionMeshCodecTest.fixture(64);
        var key = new PredictionTileManager.PredictionTileKey(net.minecraft.world.level.Level.OVERWORLD,x,z,lod);
        return new PredictionTileManager.PredictionTile(key,new int[0],new int[0],new ClientColumnSample[0],mesh,
                new PredictionDepthBound(64,80),0,1,64,layout.sampleSpacing(lod));
    }
    @Test void onlyOldOffscreenDetailWithFallbackIsEvictedUnderPressure() {
        var now = new AtomicLong(); var cache = new PredictionGpuResidency<String>(100, now::get);
        for (String key : new String[]{"visible","near","no-parent","cold"}) cache.uploaded(key, 1, 40);
        now.set(PredictionGpuResidency.OUTSIDE_GRACE_NANOS - 1);
        cache.observe("visible",40,true,false,true,false); cache.observe("near",40,false,true,true,false);
        cache.observe("no-parent",40,false,false,false,false); cache.observe("cold",40,false,false,true,false);
        assertTrue(cache.retirements(160).isEmpty());
        now.incrementAndGet(); var victims = cache.retirements(160);
        assertEquals(1,victims.size()); assertEquals("cold",victims.get(0).key());
        cache.evicted(victims.get(0)); assertEquals(120,cache.residentBytes());
        assertFalse(cache.allowsUpload("cold",1,false,false), "background scheduling cannot immediately undo eviction");
        assertTrue(cache.allowsUpload("cold",1,true,false), "turning back requests a bounded upload immediately");
    }
    @Test void fullyOwnedReleasesAfterGraceAndCoverageWithdrawalRestoresImmediately() {
        var now = new AtomicLong(); var cache = new PredictionGpuResidency<String>(1000,now::get);
        cache.uploaded("owned",5,40); cache.observe("owned",40,true,false,true,true);
        now.set(PredictionGpuResidency.OWNED_GRACE_NANOS-1); assertTrue(cache.retirements(40).isEmpty());
        now.incrementAndGet(); var victim=cache.retirements(40).get(0);
        assertEquals(PredictionGpuResidency.Reason.OWNERSHIP,victim.reason());cache.evicted(victim);
        assertFalse(cache.allowsUpload("owned",5,true,true));
        assertTrue(cache.allowsUpload("owned",5,false,false));
    }
    @Test void briefCoverageAndOcclusionChangesDoNotReleasePinnedFallback() {
        var now=new AtomicLong();var cache=new PredictionGpuResidency<String>(1,now::get);
        cache.uploaded("tile",1,100);cache.observe("tile",100,false,true,true,true);
        now.set(30_000_000_000L);assertTrue(cache.retirements(100).isEmpty());
        cache.observe("tile",100,false,false,false,true);assertTrue(cache.retirements(100).isEmpty());
        cache.observe("tile",100,true,false,true,false);assertTrue(cache.retirements(100).isEmpty());
    }
    @Test void evictionIsBoundedAndLowWaterTargetDoesNotMakeVisibleGeometryDisappear() {
        var now=new AtomicLong();var cache=new PredictionGpuResidency<Integer>(64*PredictionGpuResidency.MIB,now::get);
        for(int i=0;i<20;i++){cache.uploaded(i,1,8*PredictionGpuResidency.MIB);cache.observe(i,8*PredictionGpuResidency.MIB,false,false,true,false);}
        now.set(20_000_000_000L);var victims=cache.retirements(160*PredictionGpuResidency.MIB);
        assertEquals(4,victims.size());victims.forEach(cache::evicted);
        assertEquals(128*PredictionGpuResidency.MIB,cache.residentBytes());
        cache.clear();assertEquals(0,cache.residentBytes());assertEquals(0,cache.coldCount());
    }
}
