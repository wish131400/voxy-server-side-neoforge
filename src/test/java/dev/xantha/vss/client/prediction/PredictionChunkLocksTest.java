package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PredictionChunkLocksTest {
    @Test @SuppressWarnings("unchecked")
    void publishedVegetationRemainsAvailableBeforeOwnerReleasesReservation() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        var profile=new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                net.minecraft.resources.ResourceLocation.withDefaultNamespace("overworld"),-64,384,"noise","minecraft:overworld",1L);
        var vegetation=new PredictionVegetation(new ClientTerrainSampler(1,profile));
        var cacheField=PredictionVegetation.class.getDeclaredField("chunks");cacheField.setAccessible(true);
        var generatingField=PredictionVegetation.class.getDeclaredField("generating");generatingField.setAccessible(true);
        var cache=(java.util.Map<Long,java.util.Map<net.minecraft.core.BlockPos,net.minecraft.world.level.block.state.BlockState>>)cacheField.get(vegetation);
        var generating=(java.util.Set<Long>)generatingField.get(vegetation);
        long key=(long)-5<<32|7L;
        var expected=java.util.Map.of(net.minecraft.core.BlockPos.ZERO,net.minecraft.world.level.block.Blocks.OAK_LOG.defaultBlockState());
        // Owner publishes its immutable result before its finally block removes
        // the reservation; cache hits must stay available during that interval.
        generating.add(key);
        cache.put(key,expected);
        assertSame(expected,vegetation.chunk(-5,7));
    }

    @Test void diagonalRoutesDoNotSerializeAllUnrelatedChunks() {
        for(int stripes : new int[]{16,32}) for(int sign : new int[]{-1,1}) {
            int[] hits=new int[stripes];
            for(int x=-512;x<512;x++) {
                long key=(long)x<<32|(sign*x)&0xffffffffL;
                hits[PredictionChunkLocks.stripe(key,stripes)]++;
            }
            for(int count:hits) assertTrue(count>0 && count<1024/stripes*2,
                    "a diagonal route must spread work across the generation locks");
        }
    }
}
