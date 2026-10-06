package dev.xantha.vss.client.prediction;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Stream;
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
    private static final long MAX_COMPACT_BYTES = 256L * 1024 * 1024;
    private static final int MAX_MAINTENANCE = 4096;
    static final int MAX_PATHS = 256;
    private final Path root;
    private final Map<RegionAddress, RegionPaths> paths = new LinkedHashMap<>(16, .75F, true);
    private volatile RegionPaths lastPaths;
    private final LinkedHashMap<Path, Region> open = new LinkedHashMap<>(16, .75F, true);
    private final Map<Path, Integer> activePins = new HashMap<>();
    private final Object[] mutations = new Object[64];
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock(true);
    private final Set<Path> maintenance = new LinkedHashSet<>();
    private long closeGeneration;
    private volatile long regionCreationRevision;
    private long deletionDirectoryScans, deletionRegionGroups, deletionForces;

    record Record(byte[] bytes, boolean legacy) { }
    private record Entry(long offset, int length, byte[] header, boolean deleted) { }
    private record RegionAddress(int kind, int detail, int x, int z) { }
    private record RegionPaths(RegionAddress address, Path region, Path legacyDirectory) {
        boolean matches(PredictionDiskCache.Key key) {
            return address.kind == key.kind() && address.detail == key.detail()
                    && address.x == key.x() >> 5 && address.z == key.z() >> 5;
        }
    }

    PredictionRegionStorage(Path root) {
        this.root = root;
        Arrays.setAll(mutations, ignored -> new Object());
    }

    private Object mutationLock(Path path) { return mutations[path.hashCode() & (mutations.length - 1)]; }

    Path legacy(PredictionDiskCache.Key key) {
        return regionPaths(key).legacyDirectory.resolve(key.x() + "_" + key.z() + ".vpd");
    }

    Path path(PredictionDiskCache.Key key) {
        return regionPaths(key).region;
    }

    private RegionPaths regionPaths(PredictionDiskCache.Key key) {
        RegionPaths previous = lastPaths;
        if (previous != null && previous.matches(key)) return previous;
        var address = new RegionAddress(key.kind(), key.detail(), key.x() >> 5, key.z() >> 5);
        synchronized (paths) {
            RegionPaths cached = paths.get(address);
            if (cached == null) {
                Path directory = root.resolve(key.kind() + "-" + key.detail());
                String name = address.x + "_" + address.z;
                cached = new RegionPaths(address, directory.resolve(name + ".vpr"), directory.resolve(name));
                paths.put(address, cached);
                if (paths.size() > MAX_PATHS) paths.remove(paths.keySet().iterator().next());
            }
            lastPaths = cached;
            return cached;
        }
    }

    private static int slot(PredictionDiskCache.Key key) { return (key.x() & 31) + ((key.z() & 31) << 5); }

    /** Null denotes absent/deleted data; damaged records still throw. */
    Record read(PredictionDiskCache.Key key) throws IOException {
        lifecycle.readLock().lock();
        try {
            Region region = region(path(key), false);
            try {
                Entry entry;
                synchronized (this) { entry = region == null ? null : region.entries[slot(key)]; }
                if (entry != null && entry.deleted) return null;
                if (entry != null) {
                    byte[] bytes = new byte[entry.length];
                    readFully(region.file, ByteBuffer.wrap(bytes), entry.offset);
                    return new Record(bytes, false);
                }
            } finally { release(region); }
            Path old = legacy(key);
            if (!Files.isRegularFile(old)) return null;
            try {
                long size = Files.size(old);
                if (size <= 0 || size > MAX_RECORD_BYTES) throw new IOException("legacy cache size");
                return new Record(Files.readAllBytes(old), true);
            } catch (NoSuchFileException raced) { return null; }
        } finally { lifecycle.readLock().unlock(); }
    }

    byte[] header(PredictionDiskCache.Key key) throws IOException {
        lifecycle.readLock().lock();
        try {
            Region region = region(path(key), false);
            try {
                Entry entry;
                synchronized (this) { entry = region == null ? null : region.entries[slot(key)]; }
                if (entry != null) return entry.deleted ? null : entry.header.clone();
            } finally { release(region); }
            Path old = legacy(key);
            if (!Files.isRegularFile(old)) return null;
            try (var in = new InflaterInputStream(new BufferedInputStream(Files.newInputStream(old)))) {
                byte[] bytes = in.readNBytes(36);
                if (bytes.length != 36) throw new EOFException("legacy cache header");
                return bytes;
            } catch (NoSuchFileException raced) { return null; }
        } finally { lifecycle.readLock().unlock(); }
    }

    /** Source is a completed private staging file; data is durable before slot publication. */
    void write(PredictionDiskCache.Key key, Path source) throws IOException {
        lifecycle.readLock().lock();
        try {
            synchronized (mutationLock(path(key))) { writeRecord(key, source); }
        } finally { lifecycle.readLock().unlock(); }
    }

    private void writeRecord(PredictionDiskCache.Key key, Path source) throws IOException {
        long size = Files.size(source);
        if (size <= 0 || size > MAX_RECORD_BYTES) throw new IOException("region record size");
        byte[] header;
        try (var in = new InflaterInputStream(new BufferedInputStream(Files.newInputStream(source)))) {
            header = in.readNBytes(36);
            if (header.length != 36) throw new EOFException("record header");
        }
        Region region = region(path(key), true);
        try {
            long offset = region.length;
            long destination = offset;
            try (var in = Files.newInputStream(source)) {
                byte[] buffer = new byte[32 * 1024];
                for (int n; (n = in.read(buffer)) != -1;) {
                    writeFully(region.file, ByteBuffer.wrap(buffer, 0, n), destination);
                    destination += n;
                }
            }
            if (destination - offset != size) throw new IOException("changed region staging file");
            region.file.force(false);
            publish(region, slot(key), new Entry(offset, (int) size, header, false));
            synchronized (this) { region.length = destination; considerMaintenance(region); }
        } catch (IOException failure) {
            discard(region);
            throw failure;
        } finally { release(region); }
        // The region now shadows legacy data even if deleting it fails.
        deleteLegacyIfPresent(legacy(key));
    }

    /** A persisted tombstone shadows legacy files across sessions and interrupted deletion. */
    void delete(PredictionDiskCache.Key key) throws IOException {
        IOException failure = deleteAll(List.of(key)).get(key);
        if (failure != null) throw failure;
    }

    /** Presence is inventoried only for this batch; writes and external legacy arrivals stay observable. */
    Map<PredictionDiskCache.Key, IOException> deleteAll(Collection<PredictionDiskCache.Key> keys) {
        var failures = new HashMap<PredictionDiskCache.Key, IOException>();
        if (keys.isEmpty()) return failures;
        lifecycle.readLock().lock();
        try {
            long inventoryRevision = regionCreationRevision;
            Set<String> directories;
            try { directories = deletionEntries(root); }
            catch (IOException failure) { for (var key : keys) failures.put(key, failure); return failures; }
            var groups = new LinkedHashMap<RegionPaths, List<PredictionDiskCache.Key>>();
            for (var key : keys) groups.computeIfAbsent(regionPaths(key), ignored -> new ArrayList<>()).add(key);
            var inventories = new HashMap<Path, Set<String>>();
            var inventoryFailures = new HashMap<Path, IOException>();
            for (var group : groups.entrySet()) {
                RegionPaths paths = group.getKey();
                try {
                    synchronized (mutationLock(paths.region())) {
                        // A concurrent writer may have created a previously absent region
                        // while this batch waited for its mutation lock.
                        if (inventoryRevision != regionCreationRevision) {
                            inventoryRevision = regionCreationRevision;
                            directories = deletionEntries(root);
                            inventories.clear(); inventoryFailures.clear();
                        }
                        Path directory = paths.region().getParent();
                        if (!directories.contains(directory.getFileName().toString())) continue;
                        if (!inventories.containsKey(directory) && !inventoryFailures.containsKey(directory)) {
                            try { inventories.put(directory, deletionEntries(directory)); }
                            catch (IOException failure) { inventoryFailures.put(directory, failure); }
                        }
                        IOException inventoryFailure = inventoryFailures.get(directory);
                        if (inventoryFailure != null) throw inventoryFailure;
                        deleteRegion(paths, group.getValue(), inventories.get(directory));
                    }
                } catch (IOException failure) {
                    // Keep every affected in-memory invalidation if the group's force/delete failed.
                    for (var key : group.getValue()) failures.put(key, failure);
                }
            }
        } finally { lifecycle.readLock().unlock(); }
        return failures;
    }

    private Set<String> deletionEntries(Path directory) throws IOException {
        deletionDirectoryScans++;
        var entries = new HashSet<String>();
        try (var stream = Files.newDirectoryStream(directory)) {
            for (Path entry : stream) entries.add(entry.getFileName().toString());
        } catch (NoSuchFileException absent) { }
        catch (DirectoryIteratorException failure) { throw failure.getCause(); }
        return entries;
    }

    private void deleteRegion(RegionPaths paths, List<PredictionDiskCache.Key> keys, Set<String> directory) throws IOException {
        Region region = null;
        synchronized (this) {
            region = open.get(paths.region());
            if (region != null) pin(region);
        }
        if (region == null && directory.contains(paths.region().getFileName().toString())) {
            try { region = region(paths.region(), false); }
            catch (CorruptRegionException corrupt) { region = region(paths.region(), true); }
        }
        deletionRegionGroups++;
        var legacyDeletes = new ArrayList<Path>();
        boolean changed = false;
        try {
            Set<String> legacyFiles = directory.contains(paths.legacyDirectory().getFileName().toString())
                    ? deletionEntries(paths.legacyDirectory()) : Set.of();
            for (var key : keys) {
                String name = key.x() + "_" + key.z() + ".vpd";
                boolean legacy = legacyFiles.contains(name);
                Entry entry = region == null ? null : region.entries[slot(key)];
                if (entry == null && !legacy) continue;
                if (region == null) region = region(paths.region(), true);
                if (entry == null || !entry.deleted) {
                    publish(region, slot(key), new Entry(0, 0, new byte[36], true), false);
                    changed = true;
                }
                if (legacy) legacyDeletes.add(paths.legacyDirectory().resolve(name));
            }
            // Persist all tombstones before deleting any legacy file.
            if (changed) { region.file.force(false); deletionForces++; }
            for (Path old : legacyDeletes) Files.deleteIfExists(old);
            if (region != null) synchronized (this) { considerMaintenance(region); }
        } catch (IOException failure) {
            if (region != null) discard(region);
            throw failure;
        } finally { release(region); }
    }

    boolean migrate(PredictionDiskCache.Key key) throws IOException {
        lifecycle.readLock().lock();
        try {
            synchronized (mutationLock(path(key))) {
                Region region = region(path(key), false);
                try { if (region != null && region.entries[slot(key)] != null) return false; }
                finally { release(region); }
                Path old = legacy(key);
                if (!Files.isRegularFile(old)) return false;
                writeRecord(key, old);
                return true;
            }
        } finally { lifecycle.readLock().unlock(); }
    }

    private void publish(Region region, int slot, Entry entry) throws IOException {
        publish(region, slot, entry, true);
    }
    private void publish(Region region, int slot, Entry entry, boolean force) throws IOException {
        writeFully(region.file, ByteBuffer.wrap(encode(slot, entry)), HEADER_BYTES + (long) slot * ENTRY_BYTES);
        if (force) region.file.force(false);
        synchronized (this) {
            Entry previous = region.entries[slot];
            if (previous != null && !previous.deleted) region.liveBytes -= previous.length;
            if (!entry.deleted) region.liveBytes += entry.length;
            region.entries[slot] = entry;
        }
    }

    /** Returned handles are pinned; no file IO runs while holding the open-handle monitor. */
    private Region region(Path path, boolean create) throws IOException {
        synchronized (this) {
            Region existing = open.get(path);
            if (existing != null) { pin(existing); return existing; }
        }
        synchronized (mutationLock(path)) {
            synchronized (this) {
                Region existing = open.get(path);
                if (existing != null) { pin(existing); return existing; }
            }
            Region loaded = loadRegion(path, create);
            if (loaded == null) return null;
            List<Region> retired;
            synchronized (this) {
                open.put(path, loaded);
                pin(loaded);
                considerMaintenance(loaded);
                retired = trimOpen();
            }
            try { closeRetired(retired); }
            catch (IOException failure) { release(loaded); throw failure; }
            return loaded;
        }
    }

    private Region loadRegion(Path path, boolean create) throws IOException {
        if (!Files.exists(path)) {
            if (!create) return null;
            Files.createDirectories(path.getParent());
            Path temporary = Files.createTempFile(path.getParent(), "region-init-", ".tmp");
            try {
                try (var f = new RandomAccessFile(temporary.toFile(), "rw")) { initialize(f); f.getChannel().force(true); }
                // A partial initial index must never be published as an empty region.
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE);
                synchronized (this) { regionCreationRevision++; }
            } finally { Files.deleteIfExists(temporary); }
        }
        Region loaded;
        try { loaded = new Region(path); }
        catch (CorruptRegionException corrupt) {
            if (!create) throw corrupt;
            synchronized (this) { if (activePins.containsKey(path)) throw corrupt; }
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
        return loaded;
    }

    private void pin(Region region) {
        region.pins++;
        activePins.merge(region.path, 1, Integer::sum);
    }

    private void release(Region region) throws IOException {
        if (region == null) return;
        List<Region> retired;
        boolean close;
        synchronized (this) {
            int remaining = activePins.get(region.path) - 1;
            if (remaining == 0) activePins.remove(region.path);
            else activePins.put(region.path, remaining);
            region.pins--;
            close = region.pins == 0 && region.retired;
            retired = trimOpen();
        }
        try { closeRetired(retired); }
        finally { if (close) region.file.close(); }
    }

    private List<Region> trimOpen() {
        if (open.size() <= MAX_OPEN) return List.of();
        var retired = new ArrayList<Region>();
        for (var iterator = open.values().iterator(); open.size() > MAX_OPEN && iterator.hasNext();) {
            Region region = iterator.next();
            if (region.pins != 0) continue;
            iterator.remove(); region.retired = true; retired.add(region);
        }
        return retired;
    }

    private static void closeRetired(List<Region> regions) throws IOException {
        IOException failure = null;
        for (Region region : regions) try { region.file.close(); }
        catch (IOException error) { failure = error; }
        if (failure != null) throw failure;
    }

    private static void initialize(RandomAccessFile file) throws IOException {
        file.setLength(DATA_START);
        file.seek(0);
        file.writeInt(MAGIC); file.writeInt(VERSION); file.writeInt(SLOTS); file.writeInt(ENTRY_BYTES);
    }

    private static void readFully(FileChannel file, ByteBuffer buffer, long offset) throws IOException {
        while (buffer.hasRemaining()) {
            int count = file.read(buffer, offset);
            if (count <= 0) throw new EOFException("truncated prediction region record");
            offset += count;
        }
    }

    private static void writeFully(FileChannel file, ByteBuffer buffer, long offset) throws IOException {
        while (buffer.hasRemaining()) {
            int count = file.write(buffer, offset);
            if (count <= 0) throw new IOException("incomplete prediction region write");
            offset += count;
        }
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

    private void considerMaintenance(Region region) {
        long waste = region.length - DATA_START - region.liveBytes;
        if (waste >= COMPACT_MIN_WASTE && waste >= region.liveBytes / 4
                && region.liveBytes <= MAX_COMPACT_BYTES && maintenance.size() < MAX_MAINTENANCE) maintenance.add(region.path);
    }

    synchronized boolean hasMaintenance() { return !maintenance.isEmpty(); }

    /** Discover old append-only regions without delaying world startup or loading them all into memory. */
    void discoverMaintenance() throws IOException {
        if (!Files.isDirectory(root)) return;
        long generation;
        synchronized (this) { generation = closeGeneration; }
        try (Stream<Path> files = Files.walk(root, 2)) {
            for (Iterator<Path> paths = files.filter(path -> path.getFileName().toString().endsWith(".vpr")
                    && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).iterator(); paths.hasNext();) {
                Path path = paths.next();
                lifecycle.readLock().lock();
                try {
                    synchronized (this) {
                        if (generation != closeGeneration || maintenance.size() >= MAX_MAINTENANCE) return;
                    }
                    try { release(region(path, false)); }
                    catch (IOException ignored) { /* A damaged region remains a cache miss. */ }
                } finally { lifecycle.readLock().unlock(); }
            }
        }
    }

    /** Copy outside the cache lock so builders can keep reading while a large region is reclaimed. */
    void compactOne() throws IOException {
        compactOne(() -> true);
    }

    void compactOne(java.util.function.BooleanSupplier idle) throws IOException {
        lifecycle.readLock().lock();
        try { compactRegion(idle); }
        finally { lifecycle.readLock().unlock(); }
    }

    private void compactRegion(java.util.function.BooleanSupplier idle) throws IOException {
        if (!idle.getAsBoolean() || Thread.currentThread().isInterrupted()) return;
        Path path;
        Entry[] snapshot;
        long fileSize, generation;
        synchronized (this) {
            if (maintenance.isEmpty()) return;
            path = maintenance.iterator().next();
            maintenance.remove(path);
        }
        Region region = region(path, false);
        try { synchronized (this) {
            maintenance.remove(path);
            if (region == null || region.liveBytes > MAX_COMPACT_BYTES) return;
            long waste = region.length - DATA_START - region.liveBytes;
            if (waste < COMPACT_MIN_WASTE || waste < region.liveBytes / 4) return;
            snapshot = region.entries.clone();
            fileSize = region.length;
            generation = closeGeneration;
        } } finally { release(region); }
        Path temp = Files.createTempFile(path.getParent(), "region-compact-", ".tmp");
        boolean published = false;
        long copyStarted = System.nanoTime(), copiedBytes = 0;
        try {
            try (var input = FileChannel.open(path, StandardOpenOption.READ);
                 var output = new RandomAccessFile(temp.toFile(), "rw")) {
                initialize(output);
                var target = output.getChannel();
                ByteBuffer buffer = ByteBuffer.allocateDirect(128 * 1024);
                for (int slot = 0; slot < SLOTS; slot++) {
                    Entry entry = snapshot[slot];
                    if (entry == null) continue;
                    if (!entry.deleted) {
                        long offset = output.length();
                        long source = entry.offset, destination = offset;
                        for (int remaining = entry.length; remaining > 0;) {
                            if (!idle.getAsBoolean() || Thread.currentThread().isInterrupted()) return;
                            buffer.clear();
                            buffer.limit(Math.min(remaining, buffer.capacity()));
                            int n = input.read(buffer, source);
                            if (n <= 0) throw new EOFException("truncated prediction region during compaction");
                            buffer.flip();
                            while (buffer.hasRemaining()) destination += target.write(buffer, destination);
                            source += n;
                            remaining -= n;
                            copiedBytes += n;
                            // Maintenance is not foreground loading. Limit it to
                            // 8 MiB/s copied (8 MiB read + 8 MiB write), and yield
                            // as soon as gameplay starts requesting cache data.
                            long deadline = copyStarted + copiedBytes * 1_000_000_000L / (8L * 1024 * 1024);
                            for (long wait; (wait = deadline - System.nanoTime()) > 0;) {
                                if (!idle.getAsBoolean() || Thread.currentThread().isInterrupted()) return;
                                java.util.concurrent.locks.LockSupport.parkNanos(Math.min(wait, 16_000_000L));
                            }
                        }
                        entry = new Entry(offset, entry.length, entry.header, false);
                    }
                    output.seek(HEADER_BYTES + (long) slot * ENTRY_BYTES); output.write(encode(slot, entry));
                }
                output.getChannel().force(true);
            }
            if (!idle.getAsBoolean() || Thread.currentThread().isInterrupted()) return;
            synchronized (mutationLock(path)) {
                Region current = region(path, false);
                try { synchronized (this) {
                    maintenance.remove(path);
                    if (generation != closeGeneration || current == null) return;
                    // Include pins on retired handles before replacing the Windows file.
                    if (activePins.getOrDefault(path, 0) != 1 || current.length != fileSize
                            || !sameEntries(snapshot, current.entries)) {
                        considerMaintenance(current);
                        return;
                    }
                    discard(current);
                } } finally { release(current); }
                // New opens wait on this region's mutation lock until replacement completes.
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                published = true;
            }
        } finally {
            Files.deleteIfExists(temp);
            if (!published) synchronized (this) {
                if (generation == closeGeneration && maintenance.size() < MAX_MAINTENANCE) maintenance.add(path);
            }
        }
    }

    private static boolean sameEntries(Entry[] expected, Entry[] actual) {
        for (int i = 0; i < SLOTS; i++) {
            Entry a = expected[i], b = actual[i];
            if (a == null || b == null) { if (a != b) return false; }
            else if (a.offset != b.offset || a.length != b.length || a.deleted != b.deleted) return false;
        }
        return true;
    }

    private static void deleteLegacyIfPresent(Path path) throws IOException {
        if (!Files.isRegularFile(path)) return;
        try { Files.deleteIfExists(path); }
        catch (NoSuchFileException raced) { }
    }

    private synchronized void discard(Region region) {
        open.remove(region.path, region);
        region.retired = true;
    }

    @Override public void close() throws IOException {
        lifecycle.writeLock().lock();
        try {
            List<Region> retired;
            synchronized (this) {
                closeGeneration++;
                retired = new ArrayList<>(open.values());
                retired.forEach(region -> region.retired = true);
                open.clear(); maintenance.clear();
            }
            synchronized (paths) { paths.clear(); lastPaths = null; }
            closeRetired(retired);
        } finally { lifecycle.writeLock().unlock(); }
    }

    private static final class Region {
        final Path path;
        final FileChannel file;
        final Entry[] entries = new Entry[SLOTS];
        long liveBytes, length;
        int pins;
        boolean retired;
        Region(Path path) throws IOException {
            this.path = path;
            file = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
            try {
                long fileSize = file.size();
                length = fileSize;
                if (fileSize < DATA_START) throw new CorruptRegionException();
                ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
                readFully(file, header, 0);
                header.flip();
                if (header.getInt() != MAGIC || header.getInt() != VERSION
                        || header.getInt() != SLOTS || header.getInt() != ENTRY_BYTES) throw new CorruptRegionException();
                byte[] index = new byte[SLOTS * ENTRY_BYTES];
                readFully(file, ByteBuffer.wrap(index), HEADER_BYTES);
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
