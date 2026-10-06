package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PredictionMeshletBoundsTest {
    @Test void localFineCoarseFluidAndNegativeHeightsStayInsideTheirOwnSegment() {
        int[] words = new int[513 * 12];
        for (int q = 0; q < 513; q++) {
            int p=q*12;
            for (int i=0;i<4;i++) words[p+i]=0;
            words[p+4]=words[p+5]=32768|(32768<<16);
        }
        // The first 256 quads stay at the origin, a separate segment carries distant geometry.
        words[256*12] = words[256*12+1] = 16|(32<<16);
        words[256*12+2] = words[256*12+3] = 48|(64<<16);
        words[256*12+4] = words[256*12+5] = 32736|(32800<<16);
        words[256*12+6] = PredictionPackedMesh.FLAG_FINE_COORDINATES;
        words[512*12] = words[512*12+1] = 4|(8<<16);
        words[512*12+2] = words[512*12+3] = 12|(16<<16);
        words[512*12+4] = words[512*12+5] = 32752|(32784<<16);
        words[512*12+6] = (2 << PredictionPackedMesh.XZ_SHIFT_BITS)
                | (1 << PredictionPackedMesh.FLAGS_FLUID_SHIFT) | PredictionPackedMesh.FLAG_FLUID_FINE_Y;
        float[] b=new PredictionMeshletBounds(words).values;
        assertArrayEquals(new float[]{0,0,0,0,0,0, 0,-2,0,2,2,4, 16,-1,48,32,1,64},b);
        var ranges=new PredictionDrawRanges(new int[]{250,510},new int[]{20,3},3);
        assertEquals(4,PredictionMeshletBounds.count(ranges));
        assertEquals(256,PredictionMeshletBounds.end(250,270));
        assertEquals(270,PredictionMeshletBounds.end(256,270));
        assertEquals(0,new PredictionMeshletBounds(new int[0]).values.length);
    }
}
