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

/** Persistent authoritative column captures owned by VSS. */
public final class PredictionSampleStore implements AutoCloseable {
    // V3 separates authoritative captures from obsolete vegetation hints.
    private static final int MAGIC = 0x56535335, RAW_MAGIC = 0x56535334, LEGACY_MAGIC = 0x56535333;
    private static final int MAX_ENTRIES = 262_144;
    private final Path file;
    private static final java.util.concurrent.ConcurrentMap<Path, Slot> OWNERS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final class Slot { PredictionSampleStore owner; }
    private final Slot slot;
    private final long fingerprint;
    private final PredictionCacheMappings mappings;
    private final Map<Long, ClientColumnSample> entries =
            new LinkedHashMap<>(1024, 0.75F, true);
    private boolean dirty;

    public PredictionSampleStore(Path file, long fingerprint) {
        this(file, fingerprint, null);
    }

    PredictionSampleStore(Path file, long fingerprint, PredictionCacheMappings mappings) {
        this.file = file;
        this.fingerprint = fingerprint;
        this.mappings = mappings;
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
            int magic = input.readInt();
            if ((magic != MAGIC && magic != RAW_MAGIC && magic != LEGACY_MAGIC) || input.readLong() != fingerprint) return;
            if (magic != MAGIC && mappings != null && !mappings.hasLegacyBlocks()) return;
            int count = bounded(input.readInt(), MAX_ENTRIES);
            int[] biomes = null, blocks = null;
            if (magic == MAGIC) {
                if (mappings == null) return;
                biomes = readPalette(input, mappings::biomeId);
                blocks = readPalette(input, PredictionCacheMappings::blockId);
            }
            for (int i = 0; i < count; i++) {
                long key = input.readLong();
                var sample = readSample(input, magic != LEGACY_MAGIC);
                try {
                    if (magic == MAGIC) {
                        int[] biomePalette = biomes, blockPalette = blocks;
                        sample = remap(sample, id -> paletteId(biomePalette, id), id -> paletteId(blockPalette, id));
                    } else if (mappings != null) sample = remap(sample, mappings::legacyBiome, mappings::legacyBlock);
                    entries.put(key, sample);
                } catch (IOException unavailable) { dirty = true; }
            }
            if (input.read() != -1) throw new IOException("Trailing capture data");
        } catch (EOFException ignored) {
            entries.clear();
        } catch (Exception exception) {
            VSSLogger.warn("VSS prediction sample store could not be loaded: " + file, exception);
            entries.clear();
        }
    }

    @FunctionalInterface private interface IdMapper { int map(int id) throws IOException; }
    @FunctionalInterface private interface NameDecoder { int decode(String name) throws IOException; }

    private static int bounded(int count, int max) throws IOException {
        if (count < 0 || count > max) throw new IOException("Invalid capture count");
        return count;
    }

    private static int[] readPalette(DataInputStream input, NameDecoder decoder) throws IOException {
        int[] ids = new int[bounded(input.readInt(), 65536)];
        for (int i = 0; i < ids.length; i++) {
            String name = input.readUTF();
            try { ids[i] = decoder.decode(name); }
            catch (IOException unavailable) { ids[i] = Integer.MIN_VALUE; }
        }
        return ids;
    }

    private static int paletteId(int[] palette, int id) throws IOException {
        if (id < 0 || id >= palette.length || palette[id] == Integer.MIN_VALUE) throw new IOException("Capture resource unavailable");
        return palette[id];
    }

    private static ClientColumnSample remap(ClientColumnSample sample, IdMapper biome, IdMapper block) throws IOException {
        var source = sample.volume();
        PredictionColumnVolume volume = null;
        if (source != null) {
            int[] runs = new int[source.size() * 4];
            for (int i = 0; i < source.size(); i++) {
                runs[i * 4] = source.bottom(i); runs[i * 4 + 1] = source.top(i);
                runs[i * 4 + 2] = block.map(source.block(i)); runs[i * 4 + 3] = source.fluid(i);
            }
            volume = new PredictionColumnVolume(runs);
        }
        return new ClientColumnSample(sample.surfaceY(), sample.fluidY(), biome.map(sample.biomeIndex()),
                block.map(sample.topBlockIndex()), sample.structureIndex(), sample.treeKind(), sample.treeDensity(),
                sample.treeHeight(), sample.fluid(), sample.flags(), sample.groundFeatureKind(),
                block.map(sample.underBlockIndex()), block.map(sample.deepBlockIndex()), sample.surfaceBottom(),
                sample.lowerTop(), sample.lowerBottom(), sample.spanFloor(), volume);
    }

    private static ClientColumnSample readSample(DataInputStream input, boolean volumes) throws IOException {
        var sample = new ClientColumnSample(
                input.readInt(), input.readInt(), input.readInt(), input.readInt(),
                input.readInt(), input.readInt(), input.readInt(), input.readInt(),
                input.readInt(), input.readInt(), input.readInt(), input.readInt(),
                input.readInt(), input.readInt(), input.readInt(), input.readInt(),
                input.readInt());
        if (!volumes) return sample;
        int count = input.readInt();
        if (count < -1 || count > PredictionColumnVolume.MAX_RUNS) throw new IOException("Invalid column volume");
        if (count < 0) return sample;
        int[] words = new int[count * 4];
        for (int i = 0; i < words.length; i++) words[i] = input.readInt();
        return new ClientColumnSample(sample.surfaceY(),sample.fluidY(),sample.biomeIndex(),sample.topBlockIndex(),
                sample.structureIndex(),sample.treeKind(),sample.treeDensity(),sample.treeHeight(),sample.fluid(),sample.flags(),
                sample.groundFeatureKind(),sample.underBlockIndex(),sample.deepBlockIndex(),sample.surfaceBottom(),
                sample.lowerTop(),sample.lowerBottom(),sample.spanFloor(),new PredictionColumnVolume(words));
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
        var volume = sample.volume();
        output.writeInt(volume == null ? -1 : volume.size());
        if (volume != null) for (int i = 0; i < volume.size(); i++) {
            output.writeInt(volume.bottom(i)); output.writeInt(volume.top(i));
            output.writeInt(volume.block(i)); output.writeInt(volume.fluid(i));
        }
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
                output.writeInt(mappings == null ? RAW_MAGIC : MAGIC);
                output.writeLong(fingerprint);
                output.writeInt(entries.size());
                Map<Integer, Integer> biomes = new LinkedHashMap<>(), blocks = new LinkedHashMap<>();
                if (mappings != null) {
                    for (var sample : entries.values()) {
                        biomes.computeIfAbsent(sample.biomeIndex(), ignored -> biomes.size());
                        for (int block : new int[]{sample.topBlockIndex(), sample.underBlockIndex(), sample.deepBlockIndex()})
                            blocks.computeIfAbsent(block, ignored -> blocks.size());
                        if (sample.volume() != null) for (int i = 0; i < sample.volume().size(); i++)
                            blocks.computeIfAbsent(sample.volume().block(i), ignored -> blocks.size());
                    }
                    output.writeInt(biomes.size());
                    for (int id : biomes.keySet()) output.writeUTF(mappings.biomeName(id));
                    output.writeInt(blocks.size());
                    for (int id : blocks.keySet()) output.writeUTF(PredictionCacheMappings.blockName(id));
                }
                for (Map.Entry<Long, ClientColumnSample> entry : entries.entrySet()) {
                    output.writeLong(entry.getKey());
                    writeSample(output, mappings == null ? entry.getValue() : remap(entry.getValue(), biomes::get, blocks::get));
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
