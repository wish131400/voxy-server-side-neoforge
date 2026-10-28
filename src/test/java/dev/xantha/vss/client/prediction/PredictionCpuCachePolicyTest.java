package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PredictionCpuCachePolicyTest {
    @org.junit.jupiter.api.BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    @Test void coldBytesConvergeToLowWaterWithoutChurnAtHighWater() {
        var policy = new PredictionCpuCachePolicy(100, true);
        assertEquals(0, policy.reclaim(100)); assertEquals(26, policy.reclaim(101));
        assertEquals(15, policy.reclaim(90)); assertEquals(0, policy.reclaim(75));
        assertEquals(0, policy.reclaim(90), "a completed trim must not restart below high water");
    }
    @Test void activeGroundAndFeaturesNeedResidentSourceReplacementBeforeEviction() {
        long age = PredictionCpuCachePolicy.COLD_AGE_NANOS;
        assertTrue(PredictionCpuCachePolicy.safe(false,false,false,false,true,true,true,true,age));
        for (int protection=0;protection<8;protection++) {
            assertFalse(PredictionCpuCachePolicy.safe(protection==0,protection==1,protection==2,
                    protection==3,protection!=4,protection!=5,true,protection!=6,protection==7?age-1:age));
        }
        assertTrue(PredictionCpuCachePolicy.safe(false,false,false,false,true,true,false,false,age));
    }
    @Test void byteTargetsLeaveRoomForOtherHeapUsersAndReservationReaccountsColdStorage() {
        assertTrue(new PredictionCpuCachePolicy(12L << 30).highBytes <= 768L << 20);
        assertTrue(new PredictionCpuCachePolicy(2L << 30).highBytes < 256L << 20);
        var budget = new PredictionMemoryBudget(1000, 0, () -> Long.MAX_VALUE, System::nanoTime);
        var reservation = budget.tryReserve(500); assertNotNull(reservation); assertTrue(reservation.retain(400));
        reservation.accountResident(100); assertEquals(100, budget.usedBytes());
        reservation.accountResident(400); assertEquals(400, budget.usedBytes());
        reservation.close(); assertEquals(0, budget.usedBytes()); reservation.accountResident(200); assertEquals(0, budget.usedBytes());
    }

    @Test void managerRetiresStoredColdDetailOnlyAfterGroundAndFeatureSourceFallback() throws Exception {
        var profile = new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                net.minecraft.resources.ResourceLocation.withDefaultNamespace("overworld"), 42L, -64, 384,
                "noise", "minecraft:overworld", 1L);
        var sampler = new ClientTerrainSampler(42, profile);
        var budget = PredictionMemoryBudget.adaptive(12L << 30, () -> 8L << 30, System::nanoTime, 1, () -> 0);
        try (var manager = new PredictionTileManager(profile.levelKey(), sampler, budget, null)) {
            var layout = VssLodLayout.of(4096, 6, true, false);
            field(manager, "layout").set(manager, layout);
            field(manager, "cpuCachePolicy").set(manager, new PredictionCpuCachePolicy(1, true));
            var child = tile(32, 0, 0, layout, true); var parent = tile(16, 0, 1, layout, false);
            @SuppressWarnings("unchecked") var ready = (java.util.Map<PredictionTileManager.PredictionTileKey, PredictionTileManager.PredictionTile>) field(manager, "ready").get(manager);
            @SuppressWarnings("unchecked") var stored = (java.util.Set<PredictionTileManager.PredictionTileKey>) field(manager, "storedTiles").get(manager);
            ready.put(child.key(), child); ready.put(parent.key(), parent); stored.add(child.key());
            var eligible=PredictionTileManager.class.getDeclaredMethod("cacheEligible",PredictionTileManager.PredictionTile.class,long.class);
            eligible.setAccessible(true);
            assertEquals(false,eligible.invoke(manager,child,System.nanoTime()),"ground fallback cannot remove the only feature source");
            maintain(manager); assertSame(child,ready.get(child.key()));
            parent=tile(16,0,1,layout,true); ready.put(parent.key(),parent);
            assertEquals(true,eligible.invoke(manager,child,System.nanoTime()));
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            do { maintain(manager); } while(ready.containsKey(child.key()) && System.nanoTime()<deadline);
            assertFalse(ready.containsKey(child.key())); assertSame(parent,ready.get(parent.key()));
            assertEquals(0, manager.renderSnapshot().tiles().containsKey(child.key()) ? 1 : 0);
            assertTrue(manager.surfaceDiagnostics().contains("evictions=1"));
        }
    }
    private static void maintain(PredictionTileManager manager) throws Exception {
        field(manager, "nextCacheMaintenance").setLong(manager, 0);
        var method = PredictionTileManager.class.getDeclaredMethod("maintainCpuCache", int.class, int.class, long.class);
        method.setAccessible(true); method.invoke(manager, 0, 0, System.nanoTime());
    }
    private static java.lang.reflect.Field field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static PredictionTileManager.PredictionTile tile(int x, int z, int lod, VssLodLayout layout, boolean feature) {
        var key = new PredictionTileManager.PredictionTileKey(net.minecraft.world.level.Level.OVERWORLD, x, z, lod);
        var data=PredictionVoxelFixtures.flat(key,64,layout.sampleSpacing(lod),64);
        var source=data.voxyTile;
        var features=feature ? java.util.List.of(new PredictionVoxySource.Box(0,64,0,1,80,1,
                net.minecraft.world.level.block.Blocks.OAK_LEAVES.defaultBlockState())) : java.util.List.<PredictionVoxySource.Box>of();
        data=PredictionVoxelData.forVoxy(new PredictionVoxySource.Tile(key,source.baseX(),source.baseZ(),source.span(),
                source.axis(),source.step(),source.columns(),features,source.bytes()+64));
        return new PredictionTileManager.PredictionTile(key, new int[0], new int[0], new ClientColumnSample[0],
                data, new PredictionDepthBound(64, 80), System.nanoTime() - 60_000_000_000L, 1, 64, layout.sampleSpacing(lod));
    }
}
