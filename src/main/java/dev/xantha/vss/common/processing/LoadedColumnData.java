package dev.xantha.vss.common.processing;

public record LoadedColumnData(int chunkX, int chunkZ, byte[] sectionBytes, int sizeBytes) {
}
