package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PredictionBambooTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void repeatedCompletedStalksKeepTheFirstCrownWithoutMutatingInput() {
        var source = new HashMap<BlockPos, BlockState>();
        var trunk = Blocks.BAMBOO.defaultBlockState();
        for (int y = 70; y <= 139; y++) source.put(new BlockPos(355, y, 59), trunk);
        for (int y : new int[]{83, 96, 106, 122, 139})
            source.put(new BlockPos(355, y, 59), trunk.setValue(BambooStalkBlock.STAGE, 1));
        var soil = new BlockPos(355, 69, 59);
        source.put(soil, Blocks.PODZOL.defaultBlockState());
        var repaired = PredictionBamboo.normalize(Map.copyOf(source));
        assertEquals(15, repaired.size());
        assertEquals(Blocks.PODZOL.defaultBlockState(), repaired.get(soil));
        assertEquals(1, repaired.get(new BlockPos(355, 83, 59)).getValue(BambooStalkBlock.STAGE));
        assertEquals(71, source.size(), "do not mutate cached or shared input");
        assertSame(repaired, PredictionBamboo.normalize(repaired));
    }

    @Test void gapsAndOtherPlantsAreNotClippedAndTallSingleStalksStayValid() {
        var source = new HashMap<BlockPos, BlockState>();
        var trunk = Blocks.BAMBOO.defaultBlockState();
        for (int y = 10; y < 40; y++) source.put(new BlockPos(-3, y, -7), trunk);
        source.put(new BlockPos(-3, 39, -7), trunk.setValue(BambooStalkBlock.STAGE, 1));
        source.put(new BlockPos(-3, 41, -7), trunk);
        source.put(new BlockPos(-3, 40, -7), Blocks.OAK_LEAVES.defaultBlockState());
        assertSame(source, PredictionBamboo.normalize(source));
    }
}
