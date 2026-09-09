package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import dev.xantha.vss.client.prediction.feature.FeatureSimulator;
import dev.xantha.vss.client.prediction.feature.FeatureStamp;
import dev.xantha.vss.client.prediction.feature.StampMesher;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class FeatureStampPipelineTest {
    @BeforeAll
    static void bootstrap() {
        LoadingModList.of(java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(), java.util.Map.of());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void fallbackStampIsStableAndBounded() {
        PredictionFeatureStampCache cache = new PredictionFeatureStampCache();
        PredictionFeatureStampCache.FeatureStamp first = cache.get(2, 12, 1234L);
        PredictionFeatureStampCache.FeatureStamp second = cache.get(2, 12, 1234L);
        assertSame(first, second);
        assertEquals(12, first.height());
        assertTrue(first.radius() > 0);
        assertEquals((first.radius() * 2 + 1) * first.height() * (first.radius() * 2 + 1),
                first.voxels().length);
    }

    @Test
    void stampMesherEmitsOnlyExposedFacesAndRecordsBlocks() {
        Map<BlockPos, net.minecraft.world.level.block.state.BlockState> placed = Map.of(
                new BlockPos(0, 64, 0), Blocks.OAK_LOG.defaultBlockState(),
                new BlockPos(1, 64, 0), Blocks.OAK_LOG.defaultBlockState());
        FeatureStamp stamp = StampMesher.mesh(placed, 64, (x, z) -> 0, 64);
        assertFalse(stamp.isEmpty());
        assertEquals(2, stamp.blocks().size());
        assertTrue(stamp.quads().size() >= 5);
        assertTrue(stamp.height() >= 1);
        assertTrue(FeatureSimulator.isSolid(Blocks.OAK_LOG.defaultBlockState()));
    }
}
