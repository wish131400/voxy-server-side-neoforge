package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PredictionAffineMergeTest {
    @Test void globallyAffineTintMergesWithExactOuterCorners() {
        var mesh = fixture(false, false);
        var packed = PredictionQuadMesh.from(mesh);
        assertEquals(1, packed.quadCount());
        assertEquals(4, packed.x(0, 2)); assertEquals(4, packed.z(0, 2));
        for (int c = 0; c < 4; c++) assertEquals(color((int)packed.x(0,c),(int)packed.z(0,c)),packed.color(0,c));
        for (int cell = 0; cell < 16; cell++) assertEquals(0, packed.quadForCell(cell));
        boolean[] mask = new boolean[16]; java.util.Arrays.fill(mask,true);
        assertTrue(packed.coversAllowed(0,mask)); mask[5]=false; assertFalse(packed.coversAllowed(0,mask));
    }

    @Test void repeatedGradientAndAnInteriorColorChangeCannotBeStretchedAway() {
        assertEquals(16, PredictionQuadMesh.from(fixture(true,false)).quadCount());
        var packed=PredictionQuadMesh.from(fixture(false,true));
        assertTrue(packed.quadCount()>1);
        boolean found=false;
        for(int q=0;q<packed.quadCount();q++)for(int c=0;c<4;c++) found |= packed.color(q,c)==0x01443322;
        assertTrue(found,"interior tint discontinuity must survive");
    }

    @Test void unknownLightingOrMorphKeepsNonuniformCellsSeparate() {
        var mesh = fixture(false, false);
        mesh.affineTintSafe = false;
        assertEquals(16, PredictionQuadMesh.from(mesh).quadCount());
    }

    private static int color(int x,int z) { return 0x01000000 | ((30+x*3+z*2)<<16) | ((80-x+z)<<8) | (100+x+z); }
    private static PredictionMesh fixture(boolean repeat,boolean discontinuity) {
        float[] positions=new float[16*18],normals=new float[positions.length];int[] colors=new int[16*6];
        int[] dx={0,1,1,0,1,0},dz={0,0,1,0,1,1};
        for(int cell=0;cell<16;cell++)for(int v=0;v<6;v++) {
            int index=cell*6+v,x=cell%4+dx[v],z=cell/4+dz[v];
            positions[index*3]=x;positions[index*3+1]=80;positions[index*3+2]=z;normals[index*3+1]=1;
            colors[index]=color(repeat?dx[v]:x,repeat?dz[v]:z);
            if(discontinuity&&cell==5&&(v==2||v==4))colors[index]=0x01443322;
        }
        var mesh = new PredictionMesh(positions,normals,colors,new float[0],new float[0],new int[0],new boolean[16],96,0,16);
        mesh.affineTintSafe = true;
        return mesh;
    }
}
