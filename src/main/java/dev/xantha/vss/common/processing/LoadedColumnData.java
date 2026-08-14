package dev.xantha.vss.common.processing;

import java.util.Arrays;

public record LoadedColumnData(
        int chunkX,
        int chunkZ,
        byte[] sectionBytes,
        int sizeBytes,
        boolean completeColumn,
        int[] sectionYs,
        int[] sectionLengths) {
    public LoadedColumnData {
        sectionYs = immutableSectionYs(sectionYs);
        sectionLengths = immutableSectionLengths(sectionYs, sectionLengths);
        sectionBytes = reorderSerializedSections(sectionBytes, sectionYs, sectionLengths);
        sortSectionManifest(sectionYs, sectionLengths);
    }

    public LoadedColumnData(int chunkX, int chunkZ, byte[] sectionBytes, int sizeBytes, boolean completeColumn) {
        this(chunkX, chunkZ, sectionBytes, sizeBytes, completeColumn, new int[0], new int[0]);
    }

    public LoadedColumnData(
            int chunkX,
            int chunkZ,
            byte[] sectionBytes,
            int sizeBytes,
            boolean completeColumn,
            int[] sectionYs) {
        this(chunkX, chunkZ, sectionBytes, sizeBytes, completeColumn, sectionYs, new int[0]);
    }

    @Override
    public int[] sectionYs() {
        return Arrays.copyOf(sectionYs, sectionYs.length);
    }

    @Override
    public int[] sectionLengths() {
        return Arrays.copyOf(sectionLengths, sectionLengths.length);
    }

    private static int[] immutableSectionYs(int[] sectionYs) {
        if (sectionYs == null || sectionYs.length == 0) {
            return new int[0];
        }
        int[] copy = Arrays.copyOf(sectionYs, sectionYs.length);
        for (int i = 0; i < copy.length; i++) {
            if (copy[i] < Byte.MIN_VALUE || copy[i] > Byte.MAX_VALUE) {
                throw new IllegalArgumentException("Section Y is outside the wire range: " + copy[i]);
            }
        }
        return copy;
    }

    private static void sortSectionManifest(int[] sectionYs, int[] sectionLengths) {
        for (int i = 1; i < sectionYs.length; i++) {
            int y = sectionYs[i];
            int length = sectionLengths.length == sectionYs.length ? sectionLengths[i] : 0;
            int insertAt = i;
            while (insertAt > 0 && sectionYs[insertAt - 1] > y) {
                sectionYs[insertAt] = sectionYs[insertAt - 1];
                if (sectionLengths.length == sectionYs.length) {
                    sectionLengths[insertAt] = sectionLengths[insertAt - 1];
                }
                insertAt--;
            }
            sectionYs[insertAt] = y;
            if (sectionLengths.length == sectionYs.length) {
                sectionLengths[insertAt] = length;
            }
        }
        for (int i = 1; i < sectionYs.length; i++) {
            if (sectionYs[i] == sectionYs[i - 1]) {
                throw new IllegalArgumentException("Duplicate section Y: " + sectionYs[i]);
            }
        }
    }

    private static int[] immutableSectionLengths(int[] sectionYs, int[] sectionLengths) {
        if (sectionLengths == null || sectionLengths.length == 0) {
            return new int[0];
        }
        if (sectionLengths.length != sectionYs.length) {
            throw new IllegalArgumentException("Section length manifest does not match section Y manifest");
        }
        int[] copy = Arrays.copyOf(sectionLengths, sectionLengths.length);
        for (int length : copy) {
            if (length <= 0) {
                throw new IllegalArgumentException("Invalid serialized section length: " + length);
            }
        }
        return copy;
    }

    private static byte[] reorderSerializedSections(
            byte[] sectionBytes,
            int[] sectionYs,
            int[] sectionLengths) {
        if (sectionBytes == null || sectionYs.length <= 1 || sectionLengths.length != sectionYs.length) {
            return sectionBytes;
        }
        long expectedLength = 1L;
        for (int sectionLength : sectionLengths) {
            expectedLength += sectionLength;
        }
        if (expectedLength != sectionBytes.length
                || (sectionBytes[0] & 0xFF) != sectionYs.length) {
            return sectionBytes;
        }

        Integer[] order = new Integer[sectionYs.length];
        int[] offsets = new int[sectionYs.length];
        int offset = 1;
        boolean alreadyOrdered = true;
        for (int i = 0; i < sectionYs.length; i++) {
            order[i] = i;
            offsets[i] = offset;
            offset += sectionLengths[i];
            alreadyOrdered &= i == 0 || sectionYs[i - 1] < sectionYs[i];
        }
        if (alreadyOrdered) {
            return sectionBytes;
        }
        Arrays.sort(order, (left, right) -> Integer.compare(sectionYs[left], sectionYs[right]));
        byte[] reordered = Arrays.copyOf(sectionBytes, sectionBytes.length);
        int destination = 1;
        for (int sourceIndex : order) {
            int sectionLength = sectionLengths[sourceIndex];
            System.arraycopy(sectionBytes, offsets[sourceIndex], reordered, destination, sectionLength);
            destination += sectionLength;
        }
        return reordered;
    }
}
