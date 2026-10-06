package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class PredictionVegetationPublicationTest {
    @Test @SuppressWarnings("unchecked")
    void publishedVegetationRemainsAvailableBeforeOwnerReleasesReservation() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        var profile=new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                net.minecraft.resources.ResourceLocation.withDefaultNamespace("overworld"),-64,384,"noise","minecraft:overworld",1L);
        var vegetation=new PredictionVegetation(new ClientTerrainSampler(1,profile));
        var cacheField=PredictionVegetation.class.getDeclaredField("chunks");cacheField.setAccessible(true);
        var generatingField=PredictionVegetation.class.getDeclaredField("generationJobs");generatingField.setAccessible(true);
        var cache=(java.util.Map<Long,java.util.Map<net.minecraft.core.BlockPos,net.minecraft.world.level.block.state.BlockState>>)cacheField.get(vegetation);
        var generating=(java.util.Map<Long,Object>)generatingField.get(vegetation);
        long key=(long)-5<<32|7L;
        var expected=java.util.Map.of(net.minecraft.core.BlockPos.ZERO,net.minecraft.world.level.block.Blocks.OAK_LOG.defaultBlockState());
        generating.put(key,PredictionSurfaceRetryTest.generationJob());
        cache.put(key,expected);
        assertSame(expected,vegetation.chunk(-5,7));
    }
}
