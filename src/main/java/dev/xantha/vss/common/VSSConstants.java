package dev.xantha.vss.common;

public final class VSSConstants {
    public static final String MOD_ID = "vss";
    public static final int PROTOCOL_VERSION = 31;

    public static final int CAPABILITY_VOXEL_COLUMNS = 1;
    public static final int CAPABILITY_ZSTD_COLUMNS = 1 << 1;
    public static final int MAX_BATCH_CHUNK_REQUESTS = 1024;
    public static final int MAX_BATCH_RESPONSES = 4096;
    public static final int MAX_DIRTY_COLUMN_POSITIONS = 10240;
    public static final int MAX_FAR_PLAYER_ENTRIES = 256;
    public static final int MAX_FAR_PLAYERS_PACKET_BYTES = 900 * 1024;
    public static final int MAX_FAR_VEHICLE_DATA_BYTES = 512 * 1024;
    public static final int MAX_FAR_VEHICLE_PARENT_DEPTH = 4;
    public static final int FAR_PLAYER_SYNC_START_BLOCKS = 32;
    public static final int FAR_PLAYER_VERTICAL_HANDOFF_BLOCKS = 16;
    public static final int ESTIMATED_COLUMN_OVERHEAD_BYTES = 25;
    public static final int LOD_DISTANCE_BUFFER = 32;

    public static final byte RESPONSE_RATE_LIMITED = 0;
    public static final byte RESPONSE_UP_TO_DATE = 1;
    public static final byte RESPONSE_NOT_GENERATED = 2;

    private VSSConstants() {
    }

    public static long epochMillis() {
        return System.currentTimeMillis();
    }

    public static long epochSeconds() {
        return epochMillis() / 1000L;
    }
}
