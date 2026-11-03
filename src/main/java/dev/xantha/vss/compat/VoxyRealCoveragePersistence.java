package dev.xantha.vss.compat;

import dev.xantha.vss.common.VSSLogger;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.CRC32;

/** Proof of coverage belongs to one saved native image, including its LOD. */
public final class VoxyRealCoveragePersistence {
    private static final Map<Object, State> STORAGES = new WeakHashMap<>();
    private static final Map<Object, Binding> ENGINES = new WeakHashMap<>();
    private static volatile long generation;
    private static final ClassValue<Map<String, Field>> FIELDS = new ClassValue<>() {
        @Override protected Map<String, Field> computeValue(Class<?> type) {
            Map<String, Field> fields = new HashMap<>();
            for (Class<?> current = type; current != null; current = current.getSuperclass())
                for (Field field : current.getDeclaredFields())
                    if (Set.of("key", "tracker", "engine", "basePath", "dimension", "biomeSeed").contains(field.getName())
                            && !fields.containsKey(field.getName())) {
                        field.setAccessible(true); fields.put(field.getName(), field);
                    }
            return Map.copyOf(fields);
        }
    };
    private static final ThreadLocal<Token> ACTIVE = new ThreadLocal<>();
    private static final long MAGIC = 0x5653535243500001L;
    private static final int MAX_RECORD = 8192, MAX_REGIONS = 16;

    private VoxyRealCoveragePersistence() { }

    /** Called at native client storage creation, before WorldEngine loads any sections. */
    public static void register(Object storage, Object instance, Object identifier) {
        try {
            Object base;
            try { base = instance.getClass().getMethod("getStorageBasePath").invoke(instance); }
            catch (NoSuchMethodException ignored) { base = field(instance, "basePath"); }
            String world = (String) identifier.getClass().getMethod("getWorldId").invoke(identifier);
            if (!(base instanceof Path path) || !world.matches("[a-zA-Z0-9_-]{1,128}")) return;
            String identity = path.toAbsolutePath().normalize() + "|" + world + "|"
                    + field(identifier, "key") + "|" + field(identifier, "dimension") + "|"
                    + field(identifier, "biomeSeed");
            register(storage, path.resolve(world).resolve("vss-real-coverage-v1"), identity);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            VSSLogger.warn("VSS real coverage storage identity unavailable: " + failure.getClass().getSimpleName());
        }
    }

    public static void register(Object storage, Path directory, String identity) {
        Objects.requireNonNull(storage); Objects.requireNonNull(directory); Objects.requireNonNull(identity);
        byte[] id = digest(ByteBuffer.wrap(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        synchronized (STORAGES) {
            STORAGES.computeIfAbsent(storage, ignored -> new State(directory.resolve(HexFormat.of().formatHex(id)), id));
        }
    }

    /** Identity keys are weak; state never retains its engine or storage. No disk access. */
    public static boolean bind(Object engine, Object storage) {
        if (engine == null || storage == null) return false;
        synchronized (STORAGES) {
            State state = STORAGES.get(storage);
            if (state == null) return false;
            Binding existing = ENGINES.get(engine);
            if (existing == null || existing.state != state || existing.generation != generation)
                ENGINES.put(engine, new Binding(state, generation));
            return true;
        }
    }

    /** Called only AFTER successful WorldUpdater insertion. Returned LOD bits need a native save. */
    public static int committed(Object engine, int cx, int cy, int cz) {
        Binding binding = binding(engine);
        if (binding == null) return 0;
        int changed = 0;
        for (int lod = 0; lod <= 4; lod++) {
            long key = key(lod, cx >> (lod + 1), cy >> (lod + 1), cz >> (lod + 1));
            int bit = bit(cx, cy, cz, lod);
            Node node = binding.nodes.computeIfAbsent(key, ignored -> new Node());
            synchronized (node) {
                if (!node.runtime.get(bit) && !node.restored.get(bit)) changed |= 1 << lod;
                node.runtime.set(bit);
            }
        }
        return changed;
    }

    /** Only restored proof; fresh runtime coverage remains owned by VoxyRealCoverage. No IO. */
    public static boolean contains(Object engine, int cx, int cy, int cz, int lod) {
        Binding binding = binding(engine);
        if (binding == null || lod < 0 || lod > 4 || !binding.restoredColumns.contains(column(cx, cz))) return false;
        Node node = binding.nodes.get(key(lod, cx >> (lod + 1), cy >> (lod + 1), cz >> (lod + 1)));
        return node != null && node.restored.get(bit(cx, cy, cz, lod));
    }

    public static int coverageMask(Object engine, int cx, int firstY, int cz, int count, int lod) {
        Binding binding = binding(engine);
        if (binding == null || lod < 0 || lod > 4 || count <= 0
                || !binding.restoredColumns.contains(column(cx, cz))) return 0;
        int result = 0;
        for (int i = 0; i < Math.min(32, count); i++) {
            int cy = firstY + i;
            Node node = binding.nodes.get(key(lod, cx >> (lod + 1), cy >> (lod + 1), cz >> (lod + 1)));
            if (node != null && node.restored.get(bit(cx, cy, cz, lod))) result |= 1 << i;
        }
        return result;
    }

    public static void reset() {
        synchronized (STORAGES) {
            // Old engines may still save during native shutdown. Retain their weakly
            // keyed masks for those saves, but revoke all query/load publication epochs.
            generation++;
        }
    }

    private static Binding binding(Object engine) {
        synchronized (STORAGES) {
            Binding binding = ENGINES.get(engine);
            return binding == null || binding.generation != generation ? null : binding;
        }
    }

    /** The wrapper holds this stripe across native serialization/write or read/deserialization. */
    public static Object begin(Object storage, Object section, boolean saving) {
        State state;
        synchronized (STORAGES) { state = STORAGES.get(storage); }
        if (state == null) return null;
        Token token = null;
        try {
            long key = ((Number) field(section, "key")).longValue();
            int lod = (int) (key >>> 60);
            if (lod > 4) return null;
            Object tracker = field(section, "tracker");
            if (tracker == null) return null;
            Object engine = field(tracker, "engine");
            Binding binding;
            synchronized (STORAGES) {
                binding = ENGINES.get(engine);
                if (binding == null) { binding = new Binding(state, generation); ENGINES.put(engine, binding); }
            }
            if (binding.state != state) return null;
            token = new Token(binding, storage, section, key, saving, ACTIVE.get());
            token.lock.lock();
            if (saving) {
                Node node = binding.nodes.get(key);
                if (node != null) synchronized (node) {
                    token.mask = (BitSet) node.runtime.clone(); token.mask.or(node.restored);
                }
            } else {
                token.proof = state.region(key).get(key);
            }
            ACTIVE.set(token);
            return token;
        } catch (ReflectiveOperationException | IOException | RuntimeException failure) {
            if (token != null && token.lock.isHeldByCurrentThread()) token.lock.unlock();
            state.warn(failure);
            return null;
        }
    }

    /** Consumes the exact native serialized image immediately, never retains a native buffer. */
    public static void capture(Object memory, Object storage, Object section) {
        Token token = ACTIVE.get();
        if (token != null && token.storage == storage && token.section == section) capture(memory);
    }

    public static void capture(Object memory) {
        Token token = ACTIVE.get();
        if (token == null || memory == null || token.saving && (token.mask == null || token.mask.isEmpty())
                || !token.saving && token.proof == null) return;
        try {
            ByteBuffer bytes = memory instanceof ByteBuffer buffer ? buffer.duplicate()
                    : ((ByteBuffer) memory.getClass().getMethod("asByteBuffer").invoke(memory)).duplicate();
            token.length = bytes.remaining();
            token.hash = digest(bytes);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            token.hash = null;
            token.state.warn(failure);
        }
    }

    /** Native failure is never turned into authority. Hook errors never replace native exceptions. */
    public static void finish(Object object, boolean success) {
        if (!(object instanceof Token token)) return;
        try {
            if (!token.saving && token.binding.generation == generation) {
                Node node = token.binding.nodes.get(token.key);
                if (node != null) node.restored = new BitSet();
            }
            if (success && token.hash != null) {
                if (token.saving) {
                    if (token.mask != null && !token.mask.isEmpty())
                        token.state.region(token.key).append(new Proof(token.key, token.length, token.hash, token.mask));
                } else {
                    Proof proof = token.proof;
                    if (token.binding.generation == generation && proof != null && proof.length == token.length && MessageDigest.isEqual(proof.hash, token.hash)) {
                        Node node = token.binding.nodes.computeIfAbsent(token.key, ignored -> new Node());
                        synchronized (node) {
                            node.restored = (BitSet) proof.mask.clone();
                        }
                        int lod = (int) (token.key >>> 60), width = 1 << (lod + 1);
                        int x = VoxySectionPosition.x(token.key) * width;
                        int z = VoxySectionPosition.z(token.key) * width;
                        BitSet columns = new BitSet(width * width);
                        int columnCount = 0;
                        for (int bit = proof.mask.nextSetBit(0); bit >= 0; bit = proof.mask.nextSetBit(bit + 1)) {
                            int cell = bit & (width * width - 1);
                            if (!columns.get(cell)) {
                                columns.set(cell);
                                columnCount++;
                                token.binding.restoredColumns.add(column(x + (cell & (width - 1)), z + (cell >> (lod + 1))));
                            }
                            if (columnCount == width * width) break;
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException failure) {
            token.state.warn(failure);
        } finally {
            if (ACTIVE.get() == token) {
                if (token.previous == null) ACTIVE.remove(); else ACTIVE.set(token.previous);
            }
            token.lock.unlock();
        }
    }

    public static boolean loadedSuccessfully(int status) { return status == 0; }

    private static Object field(Object object, String name) throws ReflectiveOperationException {
        Field field = FIELDS.get(object.getClass()).get(name);
        if (field == null) throw new NoSuchFieldException(name);
        return field.get(object);
    }

    private static byte[] digest(ByteBuffer buffer) {
        try { MessageDigest hash = MessageDigest.getInstance("SHA-256"); hash.update(buffer); return hash.digest(); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static int bit(int x, int y, int z, int lod) {
        int width = 1 << (lod + 1), mask = width - 1;
        return (x & mask) | ((z & mask) << (lod + 1)) | ((y & mask) << (2 * (lod + 1)));
    }

    private static long column(int x, int z) { return ((long) x << 32) | (z & 0xffffffffL); }

    private static long key(int lod, int x, int y, int z) {
        return VoxySectionPosition.key(lod, x, y, z);
    }

    private static final class Node {
        final BitSet runtime = new BitSet();
        volatile BitSet restored = new BitSet(); // immutable after publication
    }

    private static final class Token {
        final State state; final Binding binding; final Object storage, section;
        final long key; final boolean saving; final Token previous; final ReentrantLock lock;
        BitSet mask; byte[] hash; int length; Proof proof;
        Token(Binding binding, Object storage, Object section, long key, boolean saving, Token previous) {
            this.storage = storage; this.section = section;
            this.binding = binding; this.state = binding.state; this.key = key; this.saving = saving; this.previous = previous;
            this.lock = state.locks[regionName(key).hashCode() & 63];
        }
    }

    private static final class Binding {
        final State state; final long generation;
        final ConcurrentHashMap<Long, Node> nodes = new ConcurrentHashMap<>();
        final Set<Long> restoredColumns = ConcurrentHashMap.newKeySet();
        Binding(State state, long generation) { this.state = state; this.generation = generation; }
    }

    private static String regionName(long key) {
        int lod = VoxySectionPosition.level(key), x = VoxySectionPosition.x(key);
        int y = VoxySectionPosition.y(key), z = VoxySectionPosition.z(key);
        return lod + "_" + (x >> 2) + "_" + (y >> 2) + "_" + (z >> 2) + ".bin";
    }

    private static final class State {
        final Path directory; final byte[] identity;
        final ReentrantLock[] locks = new ReentrantLock[64];
        final LinkedHashMap<String, Region> regions = new LinkedHashMap<>(16, .75f, true);
        volatile boolean warned;
        State(Path directory, byte[] identity) {
            this.directory = directory; this.identity = identity;
            Arrays.setAll(locks, ignored -> new ReentrantLock());
        }
        synchronized Region region(long key) throws IOException {
            String name = regionName(key);
            Region region = regions.get(name);
            if (region == null) {
                region = new Region(directory.resolve(name), identity);
                regions.put(name, region);
                while (regions.size() > MAX_REGIONS) regions.remove(regions.keySet().iterator().next());
            }
            return region;
        }
        void warn(Exception failure) {
            if (!warned) { warned = true; VSSLogger.warn("VSS real coverage persistence unavailable: " + failure.getClass().getSimpleName()); }
        }
    }

    private record Proof(long key, int length, byte[] hash, BitSet mask) { }

    /** Small region journals: at most 64 native nodes; loaded only during native save/load. */
    private static final class Region {
        final Path path; final byte[] identity;
        final Map<Long, Proof> proofs = new HashMap<>();
        long validLength; int records;
        Region(Path path, byte[] identity) throws IOException {
            this.path = path; this.identity = identity;
            if (!Files.exists(path)) return;
            try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
                if (input.readLong() != MAGIC || !Arrays.equals(input.readNBytes(32), identity))
                    throw new IOException("Coverage identity/header mismatch");
                validLength = 40;
                while (true) {
                    int size;
                    try { size = input.readInt(); } catch (EOFException end) { break; }
                    if (size <= 0 || size > MAX_RECORD) break;
                    byte[] payload = input.readNBytes(size);
                    if (payload.length != size) break;
                    int checksum;
                    try { checksum = input.readInt(); } catch (EOFException end) { break; }
                    if (crc(payload) != checksum) break;
                    Proof proof;
                    try { proof = decode(payload); } catch (IOException invalid) { break; }
                    proofs.put(proof.key, proof); records++; validLength += size + 8L;
                }
            } catch (EOFException incompleteHeader) { validLength = 0; }
        }
        synchronized Proof get(long key) { return proofs.get(key); }
        synchronized void append(Proof proof) throws IOException {
            Proof previous = proofs.get(proof.key);
            if (previous != null && previous.length == proof.length && Arrays.equals(previous.hash, proof.hash)
                    && previous.mask.equals(proof.mask)) return;
            byte[] payload = encode(proof);
            Files.createDirectories(path.getParent());
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                channel.truncate(validLength); channel.position(validLength);
                if (validLength == 0) {
                    write(channel, ByteBuffer.allocate(40).putLong(MAGIC).put(identity).flip()); validLength = 40;
                }
                ByteBuffer record = ByteBuffer.allocate(payload.length + 8).putInt(payload.length).put(payload).putInt(crc(payload));
                write(channel, record.flip()); validLength += payload.length + 8L;
            }
            proofs.put(proof.key, proof); records++;
            if (records > 128 && records > proofs.size() * 3) compact();
        }
        private void compact() throws IOException {
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            long length = 40;
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                write(channel, ByteBuffer.allocate(40).putLong(MAGIC).put(identity).flip());
                for (Proof proof : proofs.values()) {
                    byte[] payload = encode(proof);
                    write(channel, ByteBuffer.allocate(payload.length + 8).putInt(payload.length).put(payload).putInt(crc(payload)).flip());
                    length += payload.length + 8L;
                }
                channel.force(false);
            }
            try { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
            validLength = length; records = proofs.size();
        }
        private static void write(FileChannel channel, ByteBuffer data) throws IOException { while (data.hasRemaining()) channel.write(data); }
        private static byte[] encode(Proof proof) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeLong(proof.key); output.writeInt(proof.length); output.write(proof.hash);
                byte[] dense = proof.mask.toByteArray();
                boolean sparse = proof.mask.cardinality() * 2 < dense.length;
                output.writeBoolean(sparse);
                if (sparse) {
                    output.writeInt(proof.mask.cardinality());
                    for (int bit = proof.mask.nextSetBit(0); bit >= 0; bit = proof.mask.nextSetBit(bit + 1)) output.writeShort(bit);
                } else { output.writeInt(dense.length); output.write(dense); }
            }
            return bytes.toByteArray();
        }
        private static Proof decode(byte[] data) throws IOException {
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(data))) {
                long key = input.readLong(); int length = input.readInt(); byte[] hash = input.readNBytes(32);
                int lod = (int) (key >>> 60);
                if (lod > 4 || length < 16 || length > 1_048_576 || hash.length != 32) throw new IOException("Invalid coverage record");
                int bits = 1 << (3 * (lod + 1));
                boolean sparse = input.readBoolean(); int count = input.readInt();
                if (count < 0 || count > (sparse ? bits : (bits + 7) / 8)) throw new IOException("Invalid mask size");
                BitSet mask;
                if (sparse) {
                    mask = new BitSet();
                    for (int i = 0; i < count; i++) { int bit = input.readUnsignedShort(); if (bit >= bits) throw new IOException("Invalid coverage bit"); mask.set(bit); }
                } else {
                    byte[] bytes = input.readNBytes(count);
                    if (bytes.length != count) throw new EOFException();
                    mask = BitSet.valueOf(bytes);
                }
                if (mask.length() > bits || input.available() != 0) throw new IOException("Invalid coverage payload");
                return new Proof(key, length, hash, mask);
            }
        }
        private static int crc(byte[] bytes) { CRC32 crc = new CRC32(); crc.update(bytes); return (int) crc.getValue(); }
    }
}
