package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PredictionQuadMergerTest {
    private static final class Quads {
        final float[] p = new float[600], n = new float[600], scratch = new float[10];
        final int[] colors = new int[200];
        int count;
        void add(int axis, float plane, float u0, float v0, float u1, float v1, int color) {
            int u=(axis+1)%3,v=(axis+2)%3;
            float[] us={u0,u1,u1,u0,u1,u0},vs={v0,v0,v1,v0,v1,v1};
            for(int i=0;i<6;i++) {
                int at=count*3;p[at+axis]=plane;p[at+u]=us[i];p[at+v]=vs[i];
                n[at+axis]=1;colors[count++]=color;
            }
        }
        void merge(int start){count=PredictionQuadMerger.mergeLast(p,n,colors,count,start,scratch);}
    }

    @Test void adjacentRectanglesKeepAreaOrientationAndCellOwnership() {
        for(int axis=0;axis<3;axis++) {
            var q=new Quads();
            q.add(axis,5,0,0,1,1,0xff123456);
            q.add(axis,5,1,0,2,1,0xff123456);q.merge(0);
            assertEquals(6,q.count);
            q.add(axis,5,0,1,2,2,0xff123456);q.merge(0);
            assertEquals(6,q.count);
            assertEquals(2,q.p[6+(axis+1)%3]);assertEquals(2,q.p[6+(axis+2)%3]);
            q.add(axis,5,2,0,3,2,0xff123456);q.merge(6);
            assertEquals(12,q.count,"adjacent GPU cells must keep independent faces");
        }
    }

    @Test void gapsOverlapsMaterialsGradientsAndCutoutsNeverMerge() {
        for(int variant=0;variant<8;variant++) {
            var q=new Quads();q.add(1,5,0,0,1,1,0xaa123456);
            q.add(1,variant==3?6:5,variant==0?2:variant==1?.5f:1,0,3,1,variant==2?0xbb123456:0xaa123456);
            if(variant==4)q.colors[8]++;
            if(variant==5){q.n[18]=1;q.n[21]=1;}
            if(variant==6)q.p[24]=.75f;
            if(variant==7)for(int i=6;i<12;i++)q.n[i*3+1]=-1;
            q.merge(0);assertEquals(12,q.count,"variant="+variant);
        }
    }

    @Test void partialBlockRectanglesKeepTheirExactBounds() {
        var q=new Quads();q.add(2,.5f,0,.25f,.5f,.75f,7);q.add(2,.5f,.5f,.25f,1,.75f,7);q.merge(0);
        assertEquals(6,q.count);assertEquals(.25f,q.p[1]);assertEquals(.75f,q.p[7]);assertEquals(.5f,q.p[2]);
    }
}
