package dev.xantha.vss.common;

import java.util.concurrent.atomic.AtomicLong;

public final class VSSConstants {
    public static final String MOD_ID = "vss";
    public static final int PROTOCOL_VERSION = 38;

    public static final int CAPABILITY_VOXEL_COLUMNS = 1;
    public static final int CAPABILITY_ZSTD_COLUMNS = 1 << 1;
    public static final int MAX_BATCH_CHUNK_REQUESTS = 1024;
    public static final int MAX_BATCH_RESPONSES = 4096;
    public static final int MAX_DIRTY_COLUMN_POSITIONS = 10240;
    public static final int MAX_REGION_PRESENCE_REGIONS = 4096;
    public static final int MAX_REGION_PRESENCE_COLUMNS = 65536;
    public static final int MAX_REGION_PRESENCE_RAW_BYTES = 4 * 1024 * 1024;
    public static final int MAX_REGION_PRESENCE_ENCODED_BYTES = MAX_REGION_PRESENCE_RAW_BYTES + 65536;
    public static final int MAX_FAR_PLAYER_ENTRIES = 256;
    public static final int MAX_FAR_PLAYERS_PACKET_BYTES = 900 * 1024;
    public static final int MAX_FAR_VEHICLE_DATA_BYTES = 512 * 1024;
    public static final int MAX_FAR_VEHICLE_PARENT_DEPTH = 4;
    public static final int FAR_PLAYER_SYNC_START_BLOCKS = 32;
    public static final int FAR_PLAYER_VERTICAL_HANDOFF_BLOCKS = 16;
    public static final int ESTIMATED_COLUMN_OVERHEAD_BYTES = 25;
    public static final int LOD_DISTANCE_BUFFER = 32;
    public static final int MAX_CLIENT_LOD_DISTANCE_CHUNKS = 512;
    public static final int SYNC_NEAR_DISTANCE_CHUNKS = 32;
    public static final int SYNC_MID_DISTANCE_CHUNKS = 64;
    public static final int SYNC_FAR_DISTANCE_CHUNKS = 128;

    public static final byte RESPONSE_RATE_LIMITED = 0;
    public static final byte RESPONSE_UP_TO_DATE = 1;
    public static final byte RESPONSE_NOT_GENERATED = 2;
    public static final byte RESPONSE_BACKPRESSURE = 3;
    public static final byte RESPONSE_GENERATION_QUEUED = 4;

    private VSSConstants() {
    }

    private static final AtomicLong LAST_COLUMN_VERSION = new AtomicLong();

    public static long epochMillis() {
        return System.currentTimeMillis();
    }

    public static long columnVersion() {
        long now = epochMillis();
        while (true) {
            long previous = LAST_COLUMN_VERSION.get();
            long next = Math.max(now, previous + 1L);
            if (LAST_COLUMN_VERSION.compareAndSet(previous, next)) {
                return next;
            }
        }
    }

    public static long epochSeconds() {
        return epochMillis() / 1000L;
    }
}
