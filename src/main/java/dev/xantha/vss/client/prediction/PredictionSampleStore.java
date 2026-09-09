package dev.xantha.vss.client.prediction;

import dev.xantha.vss.common.VSSLogger;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/** Persistent Persistent column sample store owned by VSS. */
public final class PredictionSampleStore implements AutoCloseable {
    // V3 separates authoritative captures from obsolete vegetation hints.
    private static final int MAGIC = 0x56535333;
    private static final int MAX_ENTRIES = 262_144;
    private final Path file;
    private static final java.util.concurrent.ConcurrentMap<Path, Slot> OWNERS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final class Slot { PredictionSampleStore owner; }
    private final Slot slot;
    private final long fingerprint;
    private final Map<Long, ClientColumnSample> entries =
            new LinkedHashMap<>(1024, 0.75F, true);
    private boolean dirty;

    public PredictionSampleStore(Path file, long fingerprint) {
        this.file = file;
        this.fingerprint = fingerprint;
        slot = OWNERS.computeIfAbsent(file.toAbsolutePath().normalize(), path -> new Slot());
        synchronized (slot) {
            slot.owner = this;
            load();
        }
    }

    public synchronized ClientColumnSample get(long key) {
        return entries.get(key);
    }

    public synchronized void put(long key, ClientColumnSample sample) {
        if (sample == null) return;
        ClientColumnSample previous = entries.get(key);
        if (previous != null && previous.captured() && !sample.captured()) return;
        entries.put(key, sample);
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(entries.keySet().iterator().next());
        }
        dirty = true;
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized void remove(long key) {
        if (entries.remove(key) != null) dirty = true;
    }

    private void load() {
        if (!Files.isRegularFile(file)) return;
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (input.readInt() != MAGIC || input.readLong() != fingerprint) return;
            int count = Math.min(MAX_ENTRIES, Math.max(0, input.readInt()));
            for (int i = 0; i < count; i++) {
                long key = input.readLong();
                entries.put(key, readSample(input));
            }
        } catch (EOFException ignored) {
            entries.clear();
        } catch (Exception exception) {
            VSSLogger.warn("VSS prediction sample store could not be loaded: " + file, exception);
            entries.clear();
        }
    }

    private static ClientColumnSample readSample(DataInputStream input) throws IOException {
        return new ClientColumnSample(
                input.readInt(), input.readInt(), input.readInt(), input.readInt(),
                input.readInt(), input.readInt(), input.readInt(), input.readInt(),
                input.readInt(), input.readInt(), input.readInt(), input.readInt(),
                input.readInt(), input.readInt(), input.readInt(), input.readInt(),
                input.readInt());
    }

    private static void writeSample(DataOutputStream output, ClientColumnSample sample)
            throws IOException {
        output.writeInt(sample.surfaceY());
        output.writeInt(sample.fluidY());
        output.writeInt(sample.biomeIndex());
        output.writeInt(sample.topBlockIndex());
        output.writeInt(sample.structureIndex());
        output.writeInt(sample.treeKind());
        output.writeInt(sample.treeDensity());
        output.writeInt(sample.treeHeight());
        output.writeInt(sample.fluid());
        output.writeInt(sample.flags());
        output.writeInt(sample.groundFeatureKind());
        output.writeInt(sample.underBlockIndex());
        output.writeInt(sample.deepBlockIndex());
        output.writeInt(sample.surfaceBottom());
        output.writeInt(sample.lowerTop());
        output.writeInt(sample.lowerBottom());
        output.writeInt(sample.spanFloor());
    }

    public synchronized void flush() {
        synchronized (slot) { flushOwned(); }
    }

    private void flushOwned() {
        if (!dirty || slot.owner != this) return;
        try {
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE), 256 * 1024))) {
                output.writeInt(MAGIC);
                output.writeLong(fingerprint);
                output.writeInt(entries.size());
                for (Map.Entry<Long, ClientColumnSample> entry : entries.entrySet()) {
                    output.writeLong(entry.getKey());
                    writeSample(output, entry.getValue());
                }
            }
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
        } catch (Exception exception) {
            VSSLogger.warn("VSS prediction sample store could not be saved: " + file, exception);
        }
    }

    @Override
    public void close() {
        flush();
        synchronized (slot) { if (slot.owner == this) slot.owner = null; }
    }
}
