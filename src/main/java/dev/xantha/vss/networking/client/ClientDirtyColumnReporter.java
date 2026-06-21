package dev.xantha.vss.networking.client;

import dev.xantha.vss.common.PositionUtil;
import dev.xantha.vss.common.VSSConstants;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.config.VSSClientConfig;
import dev.xantha.vss.networking.VSSNetworking;
import dev.xantha.vss.networking.payloads.ClientDirtyColumnsC2SPayload;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class ClientDirtyColumnReporter {
    private static final int SEND_INTERVAL_TICKS = 5;
    private static final LongOpenHashSet PENDING_COLUMNS = new LongOpenHashSet();
    private static int tickCounter;

    private ClientDirtyColumnReporter() {
    }

    public static void markChanged(Level level, BlockPos pos) {
        if (!shouldReport(level)) {
            return;
        }
        markColumnAndEdges(pos.getX() >> 4, pos.getZ() >> 4, pos.getX() & 15, pos.getZ() & 15);
    }

    public static void tick() {
        if (!VSSClientConfig.CONFIG.receiveServerLods || !VSSClientNetworking.isServerEnabled()) {
            PENDING_COLUMNS.clear();
            tickCounter = 0;
            return;
        }
        if (++tickCounter < SEND_INTERVAL_TICKS || PENDING_COLUMNS.isEmpty()) {
            return;
        }
        tickCounter = 0;

        int count = Math.min(PENDING_COLUMNS.size(), VSSConstants.MAX_CLIENT_DIRTY_COLUMN_HINTS);
        long[] positions = new long[count];
        LongIterator iterator = PENDING_COLUMNS.iterator();
        for (int i = 0; i < count && iterator.hasNext(); i++) {
            positions[i] = iterator.nextLong();
            iterator.remove();
        }

        try {
            VSSNetworking.sendToServer(new ClientDirtyColumnsC2SPayload(positions));
        } catch (Exception e) {
            VSSLogger.debug("Client dirty column hints send failed: " + e.getMessage());
        }
    }

    public static void clear() {
        PENDING_COLUMNS.clear();
        tickCounter = 0;
    }

    private static boolean shouldReport(Level level) {
        return level != null
                && level.isClientSide()
                && VSSClientConfig.CONFIG.receiveServerLods
                && VSSClientNetworking.isServerEnabled();
    }

    private static void markColumnAndEdges(int cx, int cz, int localX, int localZ) {
        PENDING_COLUMNS.add(PositionUtil.packPosition(cx, cz));
        if (localX == 0) {
            PENDING_COLUMNS.add(PositionUtil.packPosition(cx - 1, cz));
        } else if (localX == 15) {
            PENDING_COLUMNS.add(PositionUtil.packPosition(cx + 1, cz));
        }
        if (localZ == 0) {
            PENDING_COLUMNS.add(PositionUtil.packPosition(cx, cz - 1));
        } else if (localZ == 15) {
            PENDING_COLUMNS.add(PositionUtil.packPosition(cx, cz + 1));
        }
    }
}
