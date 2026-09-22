package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PredictionGpuEncodingTest {
    @Test void arbitraryCoordinatesFlagsAndFourDifferentCornerColorsRoundTrip() {
        Random random = new Random(981);
        int[] source = random.ints(4096 * 12).toArray();
        int[][] colors = new int[19][4];
        for (int[] color : colors) for (int i=0;i<4;i++) color[i]=random.nextInt();
        for (int i=0;i<source.length;i+=12) {
            int[] color=colors[random.nextInt(colors.length)];
            source[i+7]=color[0]; source[i+8]=random.nextInt(65536);
            System.arraycopy(color,1,source,i+9,3);
        }
        var result=PredictionGpuEncoding.encode(source);
        assertEquals(8192,result.paletteBaseTexel());
        assertTrue(result.words().length<source.length*0.7);
        assertArrayEquals(source,PredictionGpuEncoding.decode(result.words(),result.paletteBaseTexel(),4096));
        assertArrayEquals(result.words(),PredictionGpuEncoding.encode(source).words());
    }
    @Test void invalidCellAndHighEntropyOrSmallMeshKeepLegacyWords() {
        int[] words=new int[1024*12];
        for(int i=0;i<1024;i++){words[i*12+7]=i;words[i*12+8]=i;}
        assertSame(words,PredictionGpuEncoding.encode(words).words());
        Arrays.fill(words,0);words[8]=65536;
        assertEquals(0,PredictionGpuEncoding.encode(words).paletteBaseTexel());
        assertSame(words,PredictionGpuEncoding.encode(words).words());
        assertEquals(0,PredictionGpuEncoding.encode(new int[12*63]).paletteBaseTexel());
        assertThrows(IllegalArgumentException.class,()->PredictionGpuEncoding.encode(new int[13]));
    }
    @Test void paletteIndexUsesAllSixteenBitsAndDoesNotLoseCoverageFlags() {
        int quads=110000;int[] source=new int[quads*12];
        for(int q=0;q<quads;q++) {
            int at=q*12;source[at+7]=q%65536;source[at+8]=65535;
            source[at+9]=0xf9000000;source[at+10]=0xffffffff;source[at+11]=0x80000000;
        }
        var encoded=PredictionGpuEncoding.encode(source);assertTrue(encoded.paletteBaseTexel()>0);
        assertArrayEquals(source,PredictionGpuEncoding.decode(encoded.words(),encoded.paletteBaseTexel(),quads));
        encoded.words()[7]=0xffff0000; // valid last entry
        assertEquals(65535,PredictionGpuEncoding.decode(encoded.words(),encoded.paletteBaseTexel(),quads)[7]);
    }
    @Test void compactCpuCompressionColdRestoreAndDiskCacheKeepCanonicalWords()throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        var mesh=PredictionMeshCodecTest.fixture(64);var packed=mesh.gpuPayload();
        int[] original=packed.quads().clone();
        packed.prepareGpuStorage();assertTrue(packed.paletteBaseTexel()>0);
        int[] upload=packed.uploadWords().clone();
        assertArrayEquals(original,packed.quads());assertTrue(upload.length<original.length);
        assertEquals(upload.length/4,packed.morphBaseTexel());
        packed.prepareStorage();assertTrue(packed.compressed());PredictionMeshRestore.clear();
        try {
            assertArrayEquals(original,packed.restoreWords());
            assertArrayEquals(upload,PredictionMeshCompressionTest.awaitUpload(packed));packed.uploaded();
            byte[] signature=new byte[32];
            var loaded=PredictionMeshCodec.decode(PredictionMeshCodec.encode(mesh,signature),signature,mesh.cellAxis());
            assertNotNull(loaded);assertArrayEquals(original,loaded.gpuPayload().quads());
            loaded.gpuPayload().prepareGpuStorage();assertArrayEquals(upload,loaded.gpuPayload().uploadWords());
        } finally {PredictionMeshRestore.clear();}
    }
    @Test void realCorpusRoundTripAndEncodingCost()throws Exception {
        String directory=System.getProperty("vss.gpuCorpus");
        org.junit.jupiter.api.Assumptions.assumeTrue(directory!=null,"optional real mesh corpus");
        List<int[]> inputs=new ArrayList<>();
        try(var paths=Files.list(Path.of(directory))) {
            for(Path path:paths.filter(p->p.toString().endsWith(".bin")).sorted().toList()) {
                var bytes=ByteBuffer.wrap(Files.readAllBytes(path)).order(ByteOrder.LITTLE_ENDIAN);
                int[] words=new int[bytes.remaining()/4];bytes.asIntBuffer().get(words);inputs.add(words);
            }
        }
        assertFalse(inputs.isEmpty());long raw=0,stored=0;int selected=0;
        for(int[] input:inputs){var e=PredictionGpuEncoding.encode(input);raw+=input.length*4L;stored+=e.words().length*4L;
            if(e.paletteBaseTexel()!=0)selected++;assertArrayEquals(input,PredictionGpuEncoding.decode(e.words(),e.paletteBaseTexel(),input.length/12));}
        long oldCpu=0,newCpu=0;int zstd=0;
        for(int[] input:inputs) {
            var e=PredictionGpuEncoding.encode(input);var oldBlob=PredictionMeshCompression.compress(input);
            var newBlob=PredictionMeshCompression.compress(e.words());
            if(oldBlob!=null && oldBlob.zstd())zstd++;
            oldCpu+=oldBlob==null?input.length*4L:oldBlob.bytes().length;
            newCpu+=newBlob==null?e.words().length*4L:newBlob.bytes().length;
        }
        System.out.printf(Locale.ROOT,"GPU_ENCODING_CPU_COMPRESSED before=%d after=%d zstdTiles=%d%n",oldCpu,newCpu,zstd);
        long[] nanos=new long[15];
        for(int round=-5;round<nanos.length;round++) {
            long start=System.nanoTime();long check=0;
            for(int[] input:inputs)check+=PredictionGpuEncoding.encode(input).words().length;
            if(round>=0)nanos[round]=System.nanoTime()-start;
            assertEquals(stored/4,check);
        }
        Arrays.sort(nanos);
        System.out.printf(Locale.ROOT,"GPU_ENCODING tiles=%d compact=%d raw=%d encoded=%d savedPct=%.3f batchMedianMs=%.3f%n",
                inputs.size(),selected,raw,stored,100.0*(raw-stored)/raw,nanos[7]/1e6);
    }
}
