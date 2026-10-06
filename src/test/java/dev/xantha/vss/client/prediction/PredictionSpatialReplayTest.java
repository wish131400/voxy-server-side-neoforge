package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named="vss.spatialReplay",matches=".+")
class PredictionSpatialReplayTest {
    @Test void capturedClientMeshesPreserveGeometryAndImproveBounds() throws Exception {
        Path dir=Path.of(System.getProperty("vss.spatialReplay"));
        List<Path> files;
        try(var paths=Files.list(dir)){files=paths.filter(p->p.toString().endsWith(".bin")).sorted().toList();}
        assertFalse(files.isEmpty());
        long beforeQuads=0;long[] afterQuads=new long[4];
        long oldRuns=0,newRuns=0,groups=0;double oldSpan=0,newSpan=0;long elapsed=0;
        for(Path path:files) {
            int[] words,first,count;boolean[] aquatic=new boolean[256];
            try(var in=new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
                int n=in.readInt();first=new int[n];count=new int[n];
                for(int i=0;i<n;i++){first[i]=in.readInt();count[i]=in.readInt();}
                for(int i=0;i<aquatic.length;i++)aquatic[i]=in.readBoolean();
                words=new int[in.readInt()];for(int i=0;i<words.length;i++)words[i]=in.readInt();assertEquals(-1,in.read());
            }
            int terrain=Arrays.stream(count).sum();
            int[] original=words.clone();
            var oldBounds=new PredictionMeshletBounds(words);
            oldRuns+=new PredictionOwnershipRuns(words).ends.length;
            long start=System.nanoTime();
            int[][] retained=PredictionSpatialOrder.arrange(words,first,count,aquatic);
            elapsed+=System.nanoTime()-start;
            var newBounds=new PredictionMeshletBounds(words);
            newRuns+=new PredictionOwnershipRuns(words).ends.length;
            int boxes=oldBounds.values.length/6;groups+=boxes;
            oldSpan+=PredictionSpatialOrderTest.meanSpan(oldBounds.values)*boxes;
            newSpan+=PredictionSpatialOrderTest.meanSpan(newBounds.values)*boxes;
            assertArrayEquals(Arrays.copyOfRange(original,terrain*12,original.length),Arrays.copyOfRange(words,terrain*12,words.length));
            // Exact multiset comparison inside each face group, including all material/ownership bits.
            for(int g=0;g<count.length;g++) {
                var records=new HashMap<String,Integer>();
                for(int q=first[g],end=q+count[g];q<end;q++)records.merge(record(original,q),1,Integer::sum);
                for(int q=first[g],end=q+count[g];q<end;q++)records.merge(record(words,q),-1,Integer::sum);
                assertTrue(records.values().stream().allMatch(n->n==0));
            }
            for(int tier=0;tier<4;tier++)afterQuads[tier]+=Arrays.stream(retained[tier]).sum();
            beforeQuads+=terrain;
        }
        System.out.printf(Locale.ROOT,"SPATIAL_REPLAY meshes=%d quads=%d retained=%s boundsMean=%.3f->%.3f ownershipRuns=%d->%d workerOrderMs=%.3f%n",
                files.size(),beforeQuads,Arrays.toString(afterQuads),oldSpan/groups,newSpan/groups,oldRuns,newRuns,elapsed/1e6);
        assertEquals(beforeQuads,afterQuads[0]);
        assertTrue(newSpan<oldSpan*.8,"real mesh boxes should become materially tighter");
        assertTrue(newRuns<=oldRuns*1.25,"do not trade GPU bounds for excessive CPU ownership fragmentation");
    }
    private static String record(int[] words,int q){return Arrays.toString(Arrays.copyOfRange(words,q*12,(q+1)*12));}
}
