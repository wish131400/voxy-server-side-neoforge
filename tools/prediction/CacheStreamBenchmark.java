import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** java tools/prediction/CacheStreamBenchmark.java [terrain.vpd]
 * Measures primitive streaming across zlib, not world loading or mesh creation.
 * An optional existing cache file is read only. Both variants process identical bytes.
 */
class CacheStreamBenchmark {
    static volatile long sink;
    static byte[] raw;
    static byte[] compressed;
    static final int BUFFER = 32 * 1024;

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            compressed = Files.readAllBytes(Path.of(args[0]));
            try (var in = new InflaterInputStream(new ByteArrayInputStream(compressed))) {
                raw = in.readAllBytes();
            }
        } else {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) {
                for (int z = 0; z < 66; z++) for (int x = 0; x < 66; x++)
                    for (int field = 0; field < 20; field++)
                        out.writeInt(field == 0 ? 64 + (x + z) / 8 : field % 7);
            }
            raw = bytes.toByteArray();
            compressed = encode(true);
        }
        for (boolean buffered : new boolean[]{false, true}) {
            byte[] encoded = encode(buffered);
            try (var in = new InflaterInputStream(new ByteArrayInputStream(encoded))) {
                if (!Arrays.equals(raw, in.readAllBytes())) throw new AssertionError("round trip differs");
            }
        }
        if (decode(false) != decode(true)) throw new AssertionError("read checksum differs");
        for (int i = 0; i < 12; i++) { decode(false); decode(true); encode(false); encode(true); }
        double[][] readings = new double[4][31];
        for (int i = 0; i < 31; i++) for (int j = 0; j < 4; j++) {
            int variant = (j + i) % 4;
            long start = System.nanoTime();
            if (variant < 2) sink = decode(variant == 1);
            else sink = encode(variant == 3).length;
            readings[variant][i] = (System.nanoTime() - start) / 1e6;
        }
        System.out.println("rawBytes=" + raw.length + ", compressedBytes=" + compressed.length + ", rounds=31");
        String[] names = {"readOld", "readBuffered", "writeOld", "writeBuffered"};
        for (int i = 0; i < 4; i++) {
            Arrays.sort(readings[i]);
            System.out.printf(Locale.ROOT, "%s medianMs=%.3f p90Ms=%.3f%n", names[i], readings[i][15], readings[i][27]);
        }
    }

    static long decode(boolean buffered) throws IOException {
        InputStream stream = new InflaterInputStream(new BufferedInputStream(new ByteArrayInputStream(compressed)));
        if (buffered) stream = new BufferedInputStream(stream, BUFFER);
        long checksum = 0;
        try (var in = new DataInputStream(stream)) {
            for (int i = 0; i < raw.length / 4; i++) checksum = checksum * 31 + in.readInt();
            for (int i = 0; i < raw.length % 4; i++) checksum = checksum * 31 + in.readUnsignedByte();
            if (in.read() != -1) throw new AssertionError("trailing bytes");
        }
        return checksum;
    }

    static byte[] encode(boolean buffered) throws IOException {
        var result = new ByteArrayOutputStream();
        OutputStream stream = new DeflaterOutputStream(new BufferedOutputStream(result));
        if (buffered) stream = new BufferedOutputStream(stream, BUFFER);
        try (var out = new DataOutputStream(stream); var in = new DataInputStream(new ByteArrayInputStream(raw))) {
            for (int i = 0; i < raw.length / 4; i++) out.writeInt(in.readInt());
            for (int i = 0; i < raw.length % 4; i++) out.writeByte(in.readUnsignedByte());
        }
        return result.toByteArray();
    }
}
