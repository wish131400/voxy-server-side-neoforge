import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Cross-check the Python container experiment using JVM file and zlib APIs.
 * java tools/prediction/StorageContainerBenchmark.java <benchmark-output>
 * Reads only; warm OS cache, includes reader/index setup and close per trial.
 */
class StorageContainerBenchmark {
    record Entry(String key, long offset, int length, byte[] header) {}
    static volatile long sink;
    static Path root;
    static List<Entry> entries;

    static List<Entry> index(Path file) throws IOException {
        var result = new ArrayList<Entry>();
        try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            while (true) {
                int size;
                try { size = in.readUnsignedShort(); } catch (EOFException done) { break; }
                String key = new String(in.readNBytes(size), java.nio.charset.StandardCharsets.UTF_8);
                result.add(new Entry(key, in.readLong(), in.readInt(), in.readNBytes(36)));
            }
        }
        return result;
    }

    static long run(String kind, String op, List<Entry> order) throws IOException {
        Map<String, Entry> lookup = new HashMap<>();
        Map<String, RandomAccessFile> regions = new HashMap<>();
        ZipFile zip = null;
        byte[] scratch = new byte[32768];
        long total = 0;
        try {
            if (kind.equals("region")) {
                for (Entry e : index(root.resolve("region/index.bin"))) lookup.put(e.key, e);
            } else if (kind.equals("zip")) zip = new ZipFile(root.resolve("zip/cache.zip").toFile());
            if (op.equals("probe")) {
                for (int i = 0; i < order.size() + order.size() / 4; i++) {
                    boolean missing = i >= order.size();
                    String key = order.get(i % order.size()).key + (missing ? ".missing" : "");
                    byte[] head = null;
                    if (kind.equals("files")) {
                        try (var in = new InflaterInputStream(new BufferedInputStream(
                                Files.newInputStream(root.resolve("files").resolve(key))))) {
                            head = in.readNBytes(36);
                        } catch (NoSuchFileException ignored) {}
                    } else if (kind.equals("region")) {
                        var e = lookup.get(key);
                        if (e != null) head = e.header;
                    } else {
                        var e = zip.getEntry(key);
                        if (e != null) head = Arrays.copyOfRange(e.getExtra(), 4, 40);
                    }
                    if (head != null) {
                        if (!Arrays.equals(head, order.get(i).header)) throw new AssertionError("header");
                        total++;
                    } else if (!missing) throw new AssertionError("missing record");
                }
                return total;
            }
            for (Entry e : order) {
                InputStream stream;
                if (kind.equals("files")) {
                    stream = new BufferedInputStream(Files.newInputStream(root.resolve("files").resolve(e.key)));
                } else if (kind.equals("region")) {
                    Entry stored = lookup.get(e.key);
                    String name = e.key.substring(0, e.key.lastIndexOf('/')) + ".region";
                    RandomAccessFile f = regions.get(name);
                    if (f == null) {
                        f = new RandomAccessFile(root.resolve("region").resolve(name).toFile(), "r");
                        regions.put(name, f);
                    }
                    f.seek(stored.offset);
                    byte[] bytes = new byte[stored.length];
                    f.readFully(bytes);
                    stream = new ByteArrayInputStream(bytes);
                } else stream = new BufferedInputStream(zip.getInputStream(zip.getEntry(e.key)));
                try (InputStream in = op.equals("decode") ? new InflaterInputStream(stream) : stream) {
                    for (int n; (n = in.read(scratch)) != -1;) total += n;
                }
            }
            return total;
        } finally {
            for (var f : regions.values()) f.close();
            if (zip != null) zip.close();
        }
    }

    public static void main(String[] args) throws Exception {
        root = Path.of(args[0]);
        entries = index(root.resolve("region/index.bin"));
        long compressed = entries.stream().mapToLong(Entry::length).sum();
        long raw = run("region", "decode", entries);
        Map<String, List<Double>> results = new TreeMap<>();
        for (int round = 0; round < 9; round++) {
            var order = new ArrayList<>(entries);
            Collections.shuffle(order, new Random(32021 + round));
            var cases = new ArrayList<String>();
            for (String kind : List.of("files", "region", "zip"))
                for (String op : List.of("probe", "read", "decode")) cases.add(kind + ":" + op);
            Collections.shuffle(cases, new Random(919 + round));
            for (String test : cases) {
                String[] parts = test.split(":");
                long start = System.nanoTime();
                long count = run(parts[0], parts[1], order);
                double ms = (System.nanoTime() - start) / 1e6;
                long expected = parts[1].equals("probe") ? entries.size() : parts[1].equals("read") ? compressed : raw;
                if (count != expected) throw new AssertionError(test + " count mismatch");
                sink = count;
                if (round >= 2) results.computeIfAbsent(test, ignored -> new ArrayList<>()).add(ms);
            }
            System.out.println("round=" + round);
        }
        System.out.println("java=" + System.getProperty("java.version") + ",records=" + entries.size()
                + ",compressedBytes=" + compressed + ",rawBytes=" + raw);
        for (var e : results.entrySet()) {
            var times = new ArrayList<>(e.getValue());
            Collections.sort(times);
            System.out.printf(Locale.ROOT, "%s medianMs=%.3f p90Ms=%.3f rounds=%s%n",
                    e.getKey(), times.get(3), times.get(6), e.getValue());
        }
    }
}
