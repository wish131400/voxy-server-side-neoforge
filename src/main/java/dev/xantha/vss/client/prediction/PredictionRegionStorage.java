package dev.xantha.vss.client.prediction;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;
import java.util.zip.InflaterInputStream;

/** Bounded region cache. Calls perform IO on builders/commit worker, never the renderer.
 * A 32x32 region has a fixed, checksummed index followed by independent zlib records.
 * Replacing an entry appends its payload before publishing its index slot. A torn slot
 * is a miss, never a reason to resurrect a legacy file. All returned bytes are detached
 * from the file so reclamation cannot invalidate a decoder already in flight.
 */
final class PredictionRegionStorage implements AutoCloseable {
    static final int SLOTS = 1024, ENTRY_BYTES = 64, HEADER_BYTES = 16;
    static final int DATA_START = HEADER_BYTES + SLOTS * ENTRY_BYTES;
    static final int MAX_RECORD_BYTES = 32 * 1024 * 1024;
    private static final int MAGIC = 0x56535052, VERSION = 1, MAX_OPEN = 32;
    private static final long COMPACT_MIN_WASTE = 4L * 1024 * 1024;
    private static final long MAX_COMPACT_BYTES = 8L * 1024 * 1024;
    private final Path root;
    private final LinkedHashMap<Path, Region> open = new LinkedHashMap<>(16, .75F, true);
    private final Set<Path> maintenance = new LinkedHashSet<>();

    record Record(byte[] bytes, boolean legacy) { }
    private record Entry(long offset, int length, byte[] header, boolean deleted) { }

    PredictionRegionStorage(Path root) { this.root = root; }

    Path legacy(PredictionDiskCache.Key key) {
        return root.resolve(key.kind() + "-" + key.detail())
                .resolve((key.x() >> 5) + "_" + (key.z() >> 5))
                .resolve(key.x() + "_" + key.z() + ".vpd");
    }

    Path path(PredictionDiskCache.Key key) {
        return root.resolve(key.kind() + "-" + key.detail())
                .resolve((key.x() >> 5) + "_" + (key.z() >> 5) + ".vpr");
    }

    private static int slot(PredictionDiskCache.Key key) { return (key.x() & 31) + ((key.z() & 31) << 5); }

    synchronized Record read(PredictionDiskCache.Key key) throws IOException {
        Region region = region(path(key), false);
        Entry entry = region == null ? null : region.entries[slot(key)];
        if (entry != null) {
            if (entry.deleted) throw new NoSuchFileException(key.toString());
            byte[] bytes = new byte[entry.length];
            region.file.seek(entry.offset);
            region.file.readFully(bytes);
            return new Record(bytes, false);
        }
        Path old = legacy(key);
        long size = Files.size(old);
        if (size <= 0 || size > MAX_RECORD_BYTES) throw new IOException("legacy cache size");
        return new Record(Files.readAllBytes(old), true);
    }

    synchronized byte[] header(PredictionDiskCache.Key key) throws IOException {
        Region region = region(path(key), false);
        Entry entry = region == null ? null : region.entries[slot(key)];
        if (entry != null) {
            if (entry.deleted) throw new NoSuchFileException(key.toString());
            return entry.header;
        }
        try (var in = new InflaterInputStream(new BufferedInputStream(Files.newInputStream(legacy(key))))) {
            byte[] bytes = in.readNBytes(36);
            if (bytes.length != 36) throw new EOFException("legacy cache header");
            return bytes;
        }
    }

    /** Source is a completed private staging file; data is durable before slot publication. */
    synchronized void write(PredictionDiskCache.Key key, Path source) throws IOException {
        Region region = region(path(key), true);
        long size = Files.size(source);
        if (size <= 0 || size > MAX_RECORD_BYTES) throw new IOException("region record size");
        byte[] header;
        try (var in = new InflaterInputStream(new BufferedInputStream(Files.newInputStream(source)))) {
            header = in.readNBytes(36);
            if (header.length != 36) throw new EOFException("record header");
        }
        long offset = region.file.length();
        try {
            region.file.seek(offset);
            try (var in = Files.newInputStream(source)) {
                byte[] buffer = new byte[32 * 1024];
                for (int n; (n = in.read(buffer)) != -1;) region.file.write(buffer, 0, n);
            }
            region.file.getChannel().force(false);
            publish(region, slot(key), new Entry(offset, (int) size, header, false));
        } catch (IOException failure) {
            discard(region.path);
            throw failure;
        }
        // The region now shadows legacy data even if deleting it fails.
        Files.deleteIfExists(legacy(key));
        considerMaintenance(region);
    }

    /** A persisted tombstone shadows legacy files across sessions and interrupted deletion. */
    synchronized void delete(PredictionDiskCache.Key key) throws IOException {
        Region region;
        try { region = region(path(key), false); }
        catch (CorruptRegionException corrupt) { region = region(path(key), true); }
        Path old = legacy(key);
        if ((region == null || region.entries[slot(key)] == null) && !Files.exists(old)) return;
        if (region == null) region = region(path(key), true);
        Entry entry = region.entries[slot(key)];
        if (entry == null || !entry.deleted) {
            try { publish(region, slot(key), new Entry(0, 0, new byte[36], true)); }
            catch (IOException failure) { discard(region.path); throw failure; }
        }
        Files.deleteIfExists(old);
        considerMaintenance(region);
    }

    synchronized boolean migrate(PredictionDiskCache.Key key) throws IOException {
        Region region = region(path(key), false);
        if (region != null && region.entries[slot(key)] != null) return false;
        Path old = legacy(key);
        if (!Files.isRegularFile(old)) return false;
        write(key, old);
        return true;
    }

    private void publish(Region region, int slot, Entry entry) throws IOException {
        region.file.seek(HEADER_BYTES + (long) slot * ENTRY_BYTES);
        region.file.write(encode(slot, entry));
        region.file.getChannel().force(false);
        Entry previous = region.entries[slot];
        if (previous != null && !previous.deleted) region.liveBytes -= previous.length;
        if (!entry.deleted) region.liveBytes += entry.length;
        region.entries[slot] = entry;
    }

    private Region region(Path path, boolean create) throws IOException {
        Region existing = open.get(path);
        if (existing != null) return existing;
        if (!Files.exists(path)) {
            if (!create) return null;
            Files.createDirectories(path.getParent());
            Path temporary = Files.createTempFile(path.getParent(), "region-init-", ".tmp");
            try {
                try (var f = new RandomAccessFile(temporary.toFile(), "rw")) { initialize(f); f.getChannel().force(true); }
                // A partial initial index must never be published as an empty region.
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE);
            } finally { Files.deleteIfExists(temporary); }
        }
        Region loaded;
        try { loaded = new Region(path); }
        catch (CorruptRegionException corrupt) {
            if (!create) throw corrupt;
            Path replacement = Files.createTempFile(path.getParent(), "region-repair-", ".tmp");
            try {
                try (var file = new RandomAccessFile(replacement.toFile(), "rw")) {
                    initialize(file);
                    // Unknown entries must not fall back to legacy data invalidated previously.
                    for (int slot = 0; slot < SLOTS; slot++) file.write(encode(slot,
                            new Entry(0, 0, new byte[36], true)));
                    file.getChannel().force(true);
                }
                Files.move(replacement, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(replacement); }
            loaded = new Region(path);
        }
        open.put(path, loaded);
        while (open.size() > MAX_OPEN) discard(open.keySet().iterator().next());
        considerMaintenance(loaded);
        return loaded;
    }

    private static void initialize(RandomAccessFile file) throws IOException {
        file.setLength(DATA_START);
        file.seek(0);
        file.writeInt(MAGIC); file.writeInt(VERSION); file.writeInt(SLOTS); file.writeInt(ENTRY_BYTES);
    }

    private static byte[] encode(int slot, Entry entry) {
        byte[] bytes = new byte[ENTRY_BYTES];
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.putInt(slot + 1).putInt(entry.deleted ? 2 : 1).putLong(entry.offset)
                .putInt(entry.length).put(entry.header).putInt(0);
        CRC32 crc = new CRC32(); crc.update(bytes, 0, ENTRY_BYTES - 4);
        buffer.putInt((int) crc.getValue());
        return bytes;
    }

    private static Entry decode(int slot, byte[] bytes, long fileSize) {
        boolean empty = true;
        for (byte b : bytes) if (b != 0) { empty = false; break; }
        if (empty) return null;
        Entry invalid = new Entry(0, 0, new byte[36], true);
        CRC32 crc = new CRC32(); crc.update(bytes, 0, ENTRY_BYTES - 4);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        int id = buffer.getInt(), state = buffer.getInt();
        long offset = buffer.getLong(); int length = buffer.getInt();
        byte[] header = new byte[36]; buffer.get(header);
        if (id != slot + 1 || (int) crc.getValue() != buffer.getInt(ENTRY_BYTES - 4)) return invalid;
        if (state == 2) return invalid;
        if (state != 1 || offset < DATA_START || length <= 0 || length > MAX_RECORD_BYTES
                || offset > fileSize - length) return invalid;
        return new Entry(offset, length, header, false);
    }

    private void considerMaintenance(Region region) throws IOException {
        long waste = region.file.length() - DATA_START - region.liveBytes;
        if (waste >= COMPACT_MIN_WASTE && waste >= region.liveBytes
                && region.liveBytes <= MAX_COMPACT_BYTES && maintenance.size() < MAX_OPEN) maintenance.add(region.path);
    }

    synchronized boolean hasMaintenance() { return !maintenance.isEmpty(); }

    /** One bounded region per idle commit-queue turn; atomic replacement preserves old readers. */
    synchronized void compactOne() throws IOException {
        if (maintenance.isEmpty()) return;
        Path path = maintenance.iterator().next();
        maintenance.remove(path);
        Region region = region(path, false);
        maintenance.remove(path);
        if (region == null || region.liveBytes > MAX_COMPACT_BYTES) return;
        Path temp = Files.createTempFile(path.getParent(), "region-compact-", ".tmp");
        try {
            try (var output = new RandomAccessFile(temp.toFile(), "rw")) {
                initialize(output);
                for (int slot = 0; slot < SLOTS; slot++) {
                    Entry entry = region.entries[slot];
                    if (entry == null) continue;
                    if (!entry.deleted) {
                        long offset = output.length();
                        output.seek(offset); region.file.seek(entry.offset);
                        byte[] buffer = new byte[32 * 1024];
                        for (int remaining = entry.length; remaining > 0;) {
                            int n = Math.min(remaining, buffer.length);
                            region.file.readFully(buffer, 0, n); output.write(buffer, 0, n); remaining -= n;
                        }
                        entry = new Entry(offset, entry.length, entry.header, false);
                    }
                    output.seek(HEADER_BYTES + (long) slot * ENTRY_BYTES); output.write(encode(slot, entry));
                }
                output.getChannel().force(true);
            }
            discard(path); // Windows replacement requires our handle to be closed.
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }

    private void discard(Path path) throws IOException {
        Region removed = open.remove(path);
        if (removed != null) removed.file.close();
    }

    @Override public synchronized void close() throws IOException {
        IOException failure = null;
        for (Region region : open.values()) try { region.file.close(); } catch (IOException e) { failure = e; }
        open.clear(); maintenance.clear();
        if (failure != null) throw failure;
    }

    private static final class Region {
        final Path path;
        final RandomAccessFile file;
        final Entry[] entries = new Entry[SLOTS];
        long liveBytes;
        Region(Path path) throws IOException {
            this.path = path;
            file = new RandomAccessFile(path.toFile(), "rw");
            try {
                if (file.length() < DATA_START || file.readInt() != MAGIC || file.readInt() != VERSION
                        || file.readInt() != SLOTS || file.readInt() != ENTRY_BYTES) throw new CorruptRegionException();
                long fileSize = file.length();
                byte[] index = new byte[SLOTS * ENTRY_BYTES];
                file.readFully(index);
                for (int slot = 0; slot < SLOTS; slot++) {
                    Entry entry = decode(slot, Arrays.copyOfRange(index, slot * ENTRY_BYTES,
                            (slot + 1) * ENTRY_BYTES), fileSize);
                    entries[slot] = entry;
                    if (entry != null && !entry.deleted) liveBytes += entry.length;
                }
            } catch (IOException | RuntimeException failure) { file.close(); throw failure; }
        }
    }

    private static final class CorruptRegionException extends IOException {
        CorruptRegionException() { super("invalid or truncated prediction region header"); }
    }
}
