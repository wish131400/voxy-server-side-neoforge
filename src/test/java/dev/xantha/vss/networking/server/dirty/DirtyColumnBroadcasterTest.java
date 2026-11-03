package dev.xantha.vss.networking.server.dirty;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.xantha.vss.common.PositionUtil;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class DirtyColumnBroadcasterTest {
    @org.junit.jupiter.api.BeforeAll
    static void initializeConfigDirectory() {
        dev.xantha.vss.testutil.MinecraftTestBootstrap.initialize();
        if (net.neoforged.fml.loading.FMLPaths.GAMEDIR.get() == null) {
            net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(
                    java.nio.file.Path.of("build", "tmp", "generation-tests"));
        }
    }

    @Test
    void disabledGenerationStillVersionsAndInvalidatesDirtyRealColumns() {
        var config = dev.xantha.vss.config.VSSServerConfig.CONFIG;
        boolean enabled = config.enabled;
        boolean generation = config.enableChunkGeneration;
        boolean versions = config.dirtyVersionCacheEnabled;
        var level = org.mockito.Mockito.mock(net.minecraft.server.level.ServerLevel.class);
        org.mockito.Mockito.when(level.dimension()).thenReturn(net.minecraft.world.level.Level.OVERWORLD);
        try (var networking = org.mockito.Mockito.mockStatic(
                dev.xantha.vss.networking.server.VSSServerNetworking.class)) {
            config.enabled = true;
            config.enableChunkGeneration = false;
            config.dirtyVersionCacheEnabled = true;
            DirtyColumnBroadcaster.clear();

            DirtyColumnBroadcaster.markDirtyColumn((Object) level, 2, 3);

            long timestamp = DirtyColumnBroadcaster.latestDirtyTimestamp(
                    net.minecraft.world.level.Level.OVERWORLD, 2, 3);
            org.junit.jupiter.api.Assertions.assertTrue(timestamp > 0L);
            networking.verify(() -> dev.xantha.vss.networking.server.VSSServerNetworking
                    .invalidateCachedColumn(level, 2, 3, timestamp));
        } finally {
            DirtyColumnBroadcaster.clear();
            config.enabled = enabled;
            config.enableChunkGeneration = generation;
            config.dirtyVersionCacheEnabled = versions;
        }
    }


    @Test
    void middleBlockAffectsOnlyItsOwnColumn() {
        assertEquals(
                Set.of(PositionUtil.packPosition(0, 1)),
                affectedColumns(new BlockPos(10, 64, 20)));
    }

    @Test
    void edgeBlockAffectsTouchingColumn() {
        assertEquals(
                Set.of(
                        PositionUtil.packPosition(2, 1),
                        PositionUtil.packPosition(1, 1)),
                affectedColumns(new BlockPos(32, 64, 20)));
    }

    @Test
    void cornerBlockAffectsOnlyTouchingCornerColumns() {
        assertEquals(
                Set.of(
                        PositionUtil.packPosition(-1, -1),
                        PositionUtil.packPosition(0, -1),
                        PositionUtil.packPosition(-1, 0),
                        PositionUtil.packPosition(0, 0)),
                affectedColumns(new BlockPos(-1, 64, -1)));
    }

    private static Set<Long> affectedColumns(BlockPos pos) {
        return LongStream.of(DirtyColumnBroadcaster.affectedColumnsForBlock(pos))
                .boxed()
                .collect(Collectors.toSet());
    }
}
