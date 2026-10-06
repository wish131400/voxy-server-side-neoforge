package dev.xantha.vss.networking.server.generation;

/** Inclusive block coordinates, rounded outward to whole chunk columns. */
public record ChunkyArea(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
    public static final int MAX_BLOCK_COORDINATE = 29_999_984;
    public static final int MAX_RADIUS_BLOCKS = 8192;
    public static final long MAX_COLUMNS = 4_194_304L;

    public ChunkyArea {
        long width = (long) maxChunkX - minChunkX + 1L;
        long depth = (long) maxChunkZ - minChunkZ + 1L;
        int limit = Math.floorDiv(MAX_BLOCK_COORDINATE, 16);
        if (minChunkX < -limit || minChunkZ < -limit || maxChunkX > limit || maxChunkZ > limit
                || width <= 0L || depth <= 0L || width > MAX_COLUMNS || depth > MAX_COLUMNS
                || width * depth > MAX_COLUMNS) {
            throw new IllegalArgumentException("vss.command.chunky.invalid_area");
        }
    }

    public static ChunkyArea square(int blockX, int blockZ, int radiusBlocks) {
        if (radiusBlocks < 0 || radiusBlocks > MAX_RADIUS_BLOCKS) {
            throw new IllegalArgumentException("vss.command.chunky.invalid_radius");
        }
        return fromBlocks((long) blockX - radiusBlocks, (long) blockZ - radiusBlocks,
                (long) blockX + radiusBlocks, (long) blockZ + radiusBlocks);
    }

    public static ChunkyArea rectangle(int x1, int z1, int x2, int z2) {
        return fromBlocks(Math.min(x1, x2), Math.min(z1, z2), Math.max(x1, x2), Math.max(z1, z2));
    }

    private static ChunkyArea fromBlocks(long minX, long minZ, long maxX, long maxZ) {
        if (minX < -MAX_BLOCK_COORDINATE || minZ < -MAX_BLOCK_COORDINATE
                || maxX > MAX_BLOCK_COORDINATE || maxZ > MAX_BLOCK_COORDINATE) {
            throw new IllegalArgumentException("vss.command.chunky.invalid_area");
        }
        return new ChunkyArea((int) Math.floorDiv(minX, 16L), (int) Math.floorDiv(minZ, 16L),
                (int) Math.floorDiv(maxX, 16L), (int) Math.floorDiv(maxZ, 16L));
    }

    public long columnCount() {
        return ((long) maxChunkX - minChunkX + 1L) * ((long) maxChunkZ - minChunkZ + 1L);
    }

    public int chunkX(long index) {
        checkIndex(index);
        return minChunkX + (int) (index % ((long) maxChunkX - minChunkX + 1L));
    }

    public int chunkZ(long index) {
        checkIndex(index);
        return minChunkZ + (int) (index / ((long) maxChunkX - minChunkX + 1L));
    }

    private void checkIndex(long index) {
        if (index < 0L || index >= columnCount()) throw new IndexOutOfBoundsException();
    }
}
