package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PredictionSeamWallsTest {
    @Test void packedHeightsAndDictionaryPreserveEveryBitAndDiskBytes() throws Exception {
        Random random = new Random(2619);
        int[] endpoints = {0, 0x80000000, 0x7fc00001, 0x7f800000, 0xff800000,
                Float.floatToRawIntBits(0.125f), Float.floatToRawIntBits(-12.375f), Float.floatToRawIntBits(65536f)};
        for (int base : new int[]{-2048, Integer.MIN_VALUE, Integer.MAX_VALUE - 65535}) {
            int[] words = new int[8192 * 5];
            for (int at = 0; at < words.length; at += 5) {
                words[at] = endpoints[random.nextInt(endpoints.length)];
                words[at + 1] = endpoints[random.nextInt(endpoints.length)];
                words[at + 2] = base + random.nextInt(65536);
                words[at + 3] = base + random.nextInt(65536);
                words[at + 4] = random.nextInt();
            }
            words[2] = base; words[3] = base + 65535;
            var packed = PredictionSeamWalls.encode(words);
            assertEquals(3, packed.stride());
            exact(words, packed);
            assertTrue(packed.retainedHeapBytes() < words.length * 4L * .61);
        }
    }

    @Test void rawAndHeightOnlyFallbacksPreserveExtremeRangesAndHighEntropy() throws Exception {
        int[] words = new int[1024 * 5];
        for (int i = 0; i < 1024; i++) {
            words[i * 5] = i * 2; words[i * 5 + 1] = i * 2 + 1;
            words[i * 5 + 2] = -64; words[i * 5 + 3] = 320; words[i * 5 + 4] = i * 4 + 3;
        }
        var packed = PredictionSeamWalls.encode(words); assertEquals(4, packed.stride()); exact(words, packed);
        words[2] = Integer.MIN_VALUE; words[3] = Integer.MAX_VALUE;
        packed = PredictionSeamWalls.encode(words); assertEquals(5, packed.stride()); exact(words, packed);
        assertEquals(5, PredictionSeamWalls.encode(new int[63 * 5]).stride());
        exact(new int[0], PredictionSeamWalls.encode(new int[0]));
        assertThrows(IllegalArgumentException.class, () -> PredictionSeamWalls.encode(new int[6]));
    }

    @Test void dictionarySupportsUnsignedSixteenBitIndicesAndRejectsOverflow() throws Exception {
        int[] words = new int[131072 * 5];
        for (int i = 0; i < words.length / 5; i++) {
            words[i * 5] = i % 65536; words[i * 5 + 1] = (i + 1) % 65536;
            words[i * 5 + 2] = -64; words[i * 5 + 3] = 100; words[i * 5 + 4] = -1;
        }
        var packed = PredictionSeamWalls.encode(words); assertEquals(3, packed.stride()); exact(words, packed);
        words[0] = 65536;
        packed = PredictionSeamWalls.encode(words); assertEquals(4, packed.stride()); exact(words, packed);
    }

    private static void exact(int[] words, PredictionSeamWalls packed) throws Exception {
        assertEquals(words.length / 5, packed.count());
        for (int i = 0; i < packed.count(); i++) {
            assertEquals(words[i * 5], packed.firstBits(i)); assertEquals(words[i * 5 + 1], packed.lastBits(i));
            assertEquals(words[i * 5 + 2], packed.bottom(i)); assertEquals(words[i * 5 + 3], packed.top(i));
            assertEquals(words[i * 5 + 4], packed.flags(i));
        }
        assertArrayEquals(serialize(PredictionSeamWalls.raw(words)), serialize(packed));
    }
    private static byte[] serialize(PredictionSeamWalls walls) throws IOException {
        var bytes = new ByteArrayOutputStream(); walls.writeCache(new DataOutputStream(bytes)); return bytes.toByteArray();
    }

    @Test void actualSeamSubtractionMatchesOldImplementationWithMasksAndNegativeCoordinates() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        var fixture = PredictionMeshCodecTest.fixture(64);
        var bytes = new ByteArrayOutputStream(); fixture.seamMesh().writeCache(new DataOutputStream(bytes));
        var entry = fixture(bytes.toByteArray(), fixture.cellAxis(), 1);
        assertTrue(entry.queries.size() > 0);
        verify(entry);
    }

    @Test void heightOnlyAndRawQueryFallbacksMatchOldImplementation() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        for (boolean extreme : new boolean[]{false, true}) {
            var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
            PredictionMeshCodec.floats(out, new float[64 * 64]);
            PredictionMeshCodec.ints(out, new int[64 * 64]);
            out.writeInt(64 * 64); out.write(new byte[64 * 64]);
            out.writeInt(1); out.writeLong(0); // X plane, negative normal
            PredictionMeshCodec.ints(out, new int[]{0, 1024});
            int[] words = new int[1024 * 5];
            for (int i = 0; i < 1024; i++) {
                words[i * 5] = Float.floatToRawIntBits(i / 32f);
                words[i * 5 + 1] = Float.floatToRawIntBits(32 + i / 32f);
                words[i * 5 + 2] = extreme ? -100000 : -64;
                words[i * 5 + 3] = 128; words[i * 5 + 4] = (i * 4) | (i % 4);
            }
            PredictionMeshCodec.ints(out, words);
            var entry = fixture(bytes.toByteArray(), 64, 1);
            assertEquals(extreme ? 5 : 4, walls(entry.current).stride());
            verify(entry);
        }
    }

    @Test void realCacheCorpusQueriesAndEncodingCost() throws Exception {
        String directory = System.getProperty("vss.seamCorpus");
        org.junit.jupiter.api.Assumptions.assumeTrue(directory != null, "optional real seam cache corpus");
        ClientTerrainSamplerTest.bootstrapMinecraft();
        List<Fixture> entries = new ArrayList<>(); long before = 0, after = 0; int[] formats = new int[6];
        var manifest = Files.readAllLines(Path.of(directory, "manifest.csv"));
        for (String line : manifest.subList(1, manifest.size())) {
            String[] c = line.split(","); int axis = Integer.parseInt(c[2]), lod = Integer.parseInt(c[1]);
            var entry = fixture(Files.readAllBytes(Path.of(directory, c[0])), axis, (64 << lod) / axis);
            entries.add(entry); verify(entry);
            var walls = walls(entry.current);
            before += entry.rawWords.length * 4L; after += walls.retainedHeapBytes(); formats[walls.stride()]++;
        }
        assertFalse(entries.isEmpty());
        long[] encode = new long[15]; long[][] queries = new long[2][15];
        long check = 0;
        for (int round = -10; round < 15; round++) {
            long start = System.nanoTime();
            for (var entry : entries) check += PredictionSeamWalls.encode(entry.rawWords).retainedHeapBytes();
            if (round >= 0) encode[round] = System.nanoTime() - start;
            for (int order = 0; order < 2; order++) {
                int mode = Math.floorMod(round + order, 2); start = System.nanoTime();
                for (var entry : entries) for (var query : entry.queries) {
                    var gaps = gaps();
                    if (mode == 0) entry.old.subtract(gaps, query.surface, query.wx, query.wz, query.length, query.nx, query.nz, query.replaced);
                    else entry.current.subtract(gaps, query.surface, query.wx, query.wz, query.length, query.nx, query.nz, query.replaced);
                    check += gaps.size();
                }
                if (round >= 0) queries[mode][round] = System.nanoTime() - start;
            }
        }
        Arrays.sort(encode); for (var values : queries) Arrays.sort(values); assertTrue(check > 0);
        System.out.printf(Locale.ROOT, "SEAM_COMPACT tiles=%d queries=%d rawBytes=%d retainedBytes=%d savedPct=%.3f formats3/4/5=%d/%d/%d encodeMedianMs=%.3f queryOldMedianMs=%.3f queryNewMedianMs=%.3f%n",
                entries.size(), entries.stream().mapToInt(e -> e.queries.size()).sum(), before, after, 100.0 * (before-after)/before,
                formats[3], formats[4], formats[5], encode[7]/1e6, queries[0][7]/1e6, queries[1][7]/1e6);
    }

    private record Query(PredictionLodSeams.Surface surface, int wx, int wz, int length, int nx, int nz, byte[] replaced) { }
    private record Fixture(PredictionSeamMesh current, PredictionSeamMeshBaseline old, int[] rawWords, List<Query> queries) { }
    private static PredictionSeamWalls walls(PredictionSeamMesh mesh) throws Exception {
        var field = PredictionSeamMesh.class.getDeclaredField("walls"); field.setAccessible(true); return (PredictionSeamWalls)field.get(mesh);
    }
    private static Fixture fixture(byte[] bytes, int axis, int step) throws Exception {
        var current = new PredictionSeamMesh(ByteBuffer.wrap(bytes), axis);
        var old = new PredictionSeamMeshBaseline(ByteBuffer.wrap(bytes), axis);
        var out = new ByteArrayOutputStream(); current.writeCache(new DataOutputStream(out)); assertArrayEquals(bytes, out.toByteArray());
        var planeField = PredictionSeamMeshBaseline.class.getDeclaredField("planes"); planeField.setAccessible(true);
        var offsetField = PredictionSeamMeshBaseline.class.getDeclaredField("offsets"); offsetField.setAccessible(true);
        var wordsField = PredictionSeamMeshBaseline.class.getDeclaredField("walls"); wordsField.setAccessible(true);
        long[] planes = (long[])planeField.get(old); int[] offsets = (int[])offsetField.get(old), words = (int[])wordsField.get(old);
        var packed = new PredictionPackedMesh(new int[0], axis, 0, new int[5], new int[5], new int[5], new int[5], false, 0);
        var mesh = PredictionMesh.restored(0, 0, packed, current);
        var key = new PredictionTileManager.PredictionTileKey(net.minecraft.world.level.Level.OVERWORLD, -2, -3, 0);
        var tile = new PredictionTileManager.PredictionTile(key, new int[0], new int[0], new ClientColumnSample[0], mesh,
                new PredictionDepthBound(-2048, 8192), 0, 1, axis, step);
        var queries = new ArrayList<Query>();
        for (int variant = 0; variant < 4; variant++) {
            boolean[] allowed = new boolean[axis * axis];
            for (int i = 0; i < allowed.length; i++) allowed[i] = variant == 0 || variant == 3 || (variant == 1 && i % 3 != 0);
            byte[] replaced = variant == 3 ? new byte[allowed.length] : null;
            if (replaced != null) Arrays.fill(replaced, (byte)15);
            var surface = new PredictionLodSeams.Surface(tile, allowed);
            for (int p = 0; p < planes.length; p += Math.max(1, planes.length / 64)) {
                boolean xNormal = (planes[p] & 2) == 0; int sign = (planes[p] & 1) == 0 ? -1 : 1;
                int coordinate = (int)(planes[p] >> 3);
                for (int at = offsets[p]; at < offsets[p+1]; at += Math.max(1, (offsets[p+1] - offsets[p]) / 2)) {
                    int start = (int)Math.ceil(Float.intBitsToFloat(words[at*5]));
                    int length = Math.max(1, Math.min(step, (int)Math.floor(Float.intBitsToFloat(words[at*5+1]))-start));
                    queries.add(new Query(surface, tile.baseBlockX() + (xNormal ? coordinate : start),
                            tile.baseBlockZ() + (xNormal ? start : coordinate), length, xNormal ? sign : 0, xNormal ? 0 : sign, replaced));
                }
            }
        }
        return new Fixture(current, old, words, queries);
    }
    private static ArrayList<PredictionLodSeams.HeightSpan> gaps() {
        return new ArrayList<>(List.of(new PredictionLodSeams.HeightSpan(-2048, 64), new PredictionLodSeams.HeightSpan(80, 8192)));
    }
    private static void verify(Fixture entry) {
        for (var query : entry.queries) {
            var expected = gaps(); var actual = gaps();
            entry.old.subtract(expected, query.surface, query.wx, query.wz, query.length, query.nx, query.nz, query.replaced);
            entry.current.subtract(actual, query.surface, query.wx, query.wz, query.length, query.nx, query.nz, query.replaced);
            assertEquals(expected, actual);
        }
    }
}
