package dev.xantha.vss.networking.server.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Planning estimate, not a promise that unknown terrain has a fixed encoded size. */
public record PregenCacheCapacity(long sampleBytes, long sampleColumns) {
    public static final long MIN_ESTIMATED_COLUMN_BYTES = 16L * 1024;

    public long estimatedColumnBytes() {
        if (sampleColumns <= 0) return MIN_ESTIMATED_COLUMN_BYTES;
        long average = (sampleBytes + sampleColumns - 1) / sampleColumns;
        return Math.max(MIN_ESTIMATED_COLUMN_BYTES, average + (average + 3) / 4);
    }

    public long capacity(int maxMiB, int maxEntries) {
        return Math.min(maxEntries, (long) maxMiB * 1024 * 1024 / estimatedColumnBytes());
    }

    public boolean fits(long requestedColumns, int maxMiB, int maxEntries) {
        return requestedColumns >= 0 && requestedColumns <= capacity(maxMiB, maxEntries);
    }

    public static PregenCacheCapacity inspect(Path root, int retentionDays) throws IOException {
        if (!Files.isDirectory(root)) return new PregenCacheCapacity(0, 0);
        long now = System.currentTimeMillis(), age = TimeUnit.DAYS.toMillis(retentionDays);
        long bytes = 0, columns = 0;
        try (var stream = Files.walk(root)) {
            var files = stream.filter(p -> p.getFileName().toString().endsWith(".vcl")
                    && Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)).iterator();
            while (files.hasNext()) {
                Path path = files.next();
                try {
                    if (now - Files.getLastModifiedTime(path).toMillis() >= age) continue;
                    bytes += Files.size(path);
                    columns++;
                } catch (java.nio.file.NoSuchFileException ignored) { }
            }
        }
        return new PregenCacheCapacity(bytes, columns);
    }
}
