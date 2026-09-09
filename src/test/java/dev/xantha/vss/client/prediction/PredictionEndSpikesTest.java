package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.HashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.SpikeFeature;
import net.minecraft.world.level.levelgen.feature.configurations.SpikeConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PredictionEndSpikesTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void pillarAndCageMatchVanillaWritesBeforeItsLiveEntitySpawn() {
        var profile = new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                ResourceLocation.withDefaultNamespace("the_end"),42,0,256,"noise","minecraft:end",1);
        var sampler = new ClientTerrainSampler(42,profile);
        for (boolean guarded : new boolean[]{false,true}) {
            var config = new SpikeConfiguration(false,List.of(new SpikeFeature.EndSpike(0,0,3,85,guarded)),null);
            var expected = new PredictionDecorationLevel(sampler,sampler,RegistryAccess.EMPTY,0,0);
            var exception = assertThrows(UnsupportedOperationException.class,() ->
                    new ConfiguredFeature<>(Feature.END_SPIKE,config).place(expected,null,RandomSource.create(42),BlockPos.ZERO));
            assertTrue(exception.getMessage().contains("getLevel"));
            var actual = new PredictionDecorationLevel(sampler,sampler,RegistryAccess.EMPTY,0,0);
            actual.beginFeature();
            PredictionEndSpikes.place(actual,RandomSource.create(42),config,BlockPos.ZERO);
            actual.endFeature(true);
            var blocks = new HashMap<>(actual.placed());
            assertTrue(blocks.remove(new BlockPos(0,85,0)).is(Blocks.BEDROCK));
            assertTrue(blocks.remove(new BlockPos(0,86,0)).is(Blocks.FIRE));
            // Vanilla first cleared these positions while carving the top.
            blocks.put(new BlockPos(0,85,0),Blocks.AIR.defaultBlockState());
            blocks.put(new BlockPos(0,86,0),Blocks.AIR.defaultBlockState());
            assertEquals(expected.placed(),blocks);
            assertTrue(actual.placed().values().stream().anyMatch(state -> state.is(Blocks.OBSIDIAN)));
            assertEquals(guarded,actual.placed().values().stream().anyMatch(state -> state.is(Blocks.IRON_BARS)));
        }
    }
}
