package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Random;
import org.junit.jupiter.api.Test;

class VoxyMergeLocalityTest {
    @Test void contiguousMergingMatchesVoxelRulesForDenseUniformAndMissingRealSectionsAtEveryLod() {
        var random=new Random(42);
        for(int lod=0;lod<=4;lod++)for(int kind=0;kind<4;kind++) {
            long[] predicted=random.longs(32768).toArray();
            long[] actual=random.longs(32768).toArray();
            for(int i=0;i<actual.length;i++)if(i%7!=0)actual[i]&=~(0xfffffL<<27);
            VoxyRenderAccess.Voxels real=switch(kind){case 0->null;case 1->new VoxyRenderAccess.Voxels(null,15L<<56);
                case 2->new VoxyRenderAccess.Voxels(null,15L<<56|123L<<47|7L<<27);default->new VoxyRenderAccess.Voxels(actual,0);};
            VoxyPredictionBridge.RealColumn authoritative=(x,z)->((x+z)&3)==0;
            long[] expected=predicted.clone();
            int populated=0;
            if(real!=null && real.data()!=null && lod==0)for(int i=0;i<32768;i++)
                if(!VoxyPredictionBridge.air(actual[i]))populated|=1<<((i&31)>>4|(i>>5&31)>>4<<1|(i>>10)>>4<<2);
            if(real!=null && real.data()==null && !VoxyPredictionBridge.air(real.uniform()))populated=255;
            for(int y=0;y<32;y++)for(int z=0;z<32;z++)for(int x=0;x<32;x++) {
                int i=y<<10|z<<5|x,octant=x>>4|(z>>4)<<1|(y>>4)<<2;
                long value=real==null ? 15L<<56 : real.get(i);
                boolean exact=authoritative.contains(Math.floorDiv((-2*32+x)*(1<<lod),16),Math.floorDiv((-3*32+z)*(1<<lod),16));
                if(exact || lod==0 && (populated&1<<octant)!=0 || !VoxyPredictionBridge.air(value))expected[i]=value;
            }
            VoxyPredictionBridge.mergeRealVoxels(predicted,real,lod,-2,0,-3,authoritative);
            assertArrayEquals(expected,predicted,"LOD "+lod+", real storage "+kind);
        }
    }
}
