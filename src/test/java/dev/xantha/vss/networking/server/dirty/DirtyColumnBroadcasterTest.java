package dev.xantha.vss.networking.server.dirty;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.xantha.vss.common.PositionUtil;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class DirtyColumnBroadcasterTest {

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
