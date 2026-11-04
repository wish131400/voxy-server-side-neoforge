package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class VoxyPredictionBridgeTest {
    @Test void pruningPublishedRequestsPreservesTheirFinalNativeInvalidation() throws Exception {
        var type=Class.forName("dev.xantha.vss.compat.VoxyPredictionBridge$Context");
        Object context=org.mockito.Mockito.mock(type,org.mockito.Mockito.CALLS_REAL_METHODS);
        var requested=new VoxySectionVersions();var dirty=new VoxyPendingSections();
        var predictionOnly=new java.util.HashSet<Long>();var delivered=new java.util.HashMap<Long,Object>();
        var waiters=new VoxySubdivisionWaiters();
        long pos=VoxyPredictionBridge.position(0,10000,0,0);
        requested.put(pos,new java.util.concurrent.atomic.AtomicLong(1));
        predictionOnly.add(pos);dirty.add(pos);waiters.add(pos);
        var fields=java.util.Map.of("requested",requested,"dirty",dirty,"predictionOnly",predictionOnly,
                "delivered",delivered,"cachedSections",new java.util.LinkedHashMap<>(),
                "pruner",new VoxySectionPruner(),"subdivisionWaiters",waiters);
        for(var entry:fields.entrySet()) {
            var field=type.getDeclaredField(entry.getKey());field.setAccessible(true);field.set(context,entry.getValue());
        }
        var prune=type.getDeclaredMethod("prune",double.class,double.class,int.class);prune.setAccessible(true);
        prune.invoke(context,0d,0d,1024);
        assertFalse(requested.containsKey(pos));assertFalse(predictionOnly.contains(pos));
        assertFalse(waiters.contains(pos));
        assertTrue(dirty.contains(pos),"CPU retirement must not erase the final update of already published native geometry");
    }

    @Test void faceMergeMatchesFullMergeForEveryDirectionLodAndRealAirMode() {
        var random=new java.util.Random(451);
        for(int lod=0;lod<=4;lod++)for(int mode=0;mode<5;mode++) {
            long[] predicted=random.longs(32768).toArray();long[] data=mode==0?null:new long[32768];
            if(data!=null)for(int i=0;i<data.length;i++)data[i]=random.nextInt(80)==0?3L<<27:15L<<56;
            VoxyRenderAccess.Voxels real=mode==0?null:mode<3?new VoxyRenderAccess.Voxels(data,0):new VoxyRenderAccess.Voxels(null,mode==3?15L<<56:7L<<27);
            VoxyPredictionBridge.RealColumn authority=(x,z)->Math.floorMod(x*17+z,5)==0;
            long[] expected=predicted.clone();VoxyPredictionBridge.mergeRealVoxels(expected,real,lod,-1,0,-1,authority);
            for(int face=0;face<6;face++) {
                long[] actual=new long[1024];for(int i=0;i<1024;i++)actual[i]=predicted[VoxyFaceData.index(face,i)];
                VoxyPredictionBridge.mergeRealFace(actual,real,face,lod,-1,-1,authority,15L<<56);
                for(int i=0;i<1024;i++)assertEquals(expected[VoxyFaceData.index(face,i)],actual[i]);
            }
        }
    }
    @Test void confirmedRealAirWinsWithoutErasingAdjacentPrediction() {
        long[] predicted = new long[32768], real = new long[32768]; Arrays.fill(predicted,1L<<27);
        VoxyPredictionBridge.mergeReal(predicted,real,0,-1,0,-1,(x,z)->x==-2 && z==-2);
        assertEquals(0,predicted[0]);
        assertEquals(1L<<27,predicted[31]);
        assertEquals(1L<<27,predicted[31<<5]);
    }
    @Test void storedRealCellsKeepTheirAirAndDoNotClaimTheOtherThreeColumns() {
        long[] predicted = new long[32768], real = new long[32768]; Arrays.fill(predicted,1L<<27);
        Arrays.fill(real,15L<<56); // Voxy initializes unknown cells to sky-lit air, not zero.
        real[0]=2L<<27;
        VoxyPredictionBridge.mergeReal(predicted,real,0,0,0,0,(x,z)->false);
        assertEquals(2L<<27,predicted[0]);
        assertEquals(15L<<56,predicted[1]);
        assertEquals(1L<<27,predicted[16]);
        assertEquals(1L<<27,predicted[16<<10]);
    }
    @Test void unknownSkyAirDoesNotClaimPredictionButConfirmedAbsentSectionsDo() {
        long[] predicted = new long[32768], real = new long[32768]; Arrays.fill(predicted,1L<<27);
        Arrays.fill(real,15L<<56);
        VoxyPredictionBridge.mergeReal(predicted,real,0,0,0,0,(x,z)->false);
        assertTrue(Arrays.stream(predicted).allMatch(v->v==(1L<<27)));
        VoxyPredictionBridge.mergeReal(predicted,null,0,0,0,0,(x,z)->x==0 && z==0);
        assertEquals(15L<<56,predicted[0]); assertEquals(1L<<27,predicted[16]);
        assertEquals(1L<<27,predicted[16<<5]);
    }
    @Test void childMasksRespectVoxyOctantOrderAndEmptySections() {
        long[] data = new long[32768];
        assertEquals(0,VoxyPredictionBridge.children(data,4));
        data[16<<10 | 16<<5 | 16]=1L<<27;
        assertEquals((byte)128,VoxyPredictionBridge.children(data,4));
        assertEquals((byte)255,VoxyPredictionBridge.children(data,0));
    }
    @Test void sparseOldCoarseDataDoesNotClaimAirAcrossAHugeOctant() {
        long[] predicted=new long[32768],real=new long[32768];Arrays.fill(predicted,1L<<27);
        real[0]=2L<<27;
        VoxyPredictionBridge.mergeReal(predicted,real,4,0,0,0,(x,z)->false);
        assertEquals(2L<<27,predicted[0]);assertEquals(1L<<27,predicted[1]);
    }
    @Test void signedCoordinatesRoundTripAtEveryNativeLevel() {
        for(int lod=0;lod<=4;lod++) {
            long p=VoxyPredictionBridge.position(lod,-123,-2,-789);
            assertEquals(lod,VoxyPredictionBridge.level(p)); assertEquals(-123,VoxyPredictionBridge.x(p));
            assertEquals(-2,VoxyPredictionBridge.y(p)); assertEquals(-789,VoxyPredictionBridge.z(p));
        }
    }
    @Test void groupedRealMergePreservesOriginalResultsForSparseDataAndAuthoritativeAirAtEveryLevel() {
        var random=new java.util.Random(371);
        for(int lod=0;lod<=4;lod++)for(int mode=0;mode<3;mode++) {
            long[] expected=new long[32768], actual=new long[32768], real=mode==0?null:new long[32768];
            Arrays.fill(expected,7L<<27);Arrays.fill(actual,7L<<27);
            if(real!=null)for(int i=0;i<real.length;i++)real[i]=random.nextInt(64)==0?2L<<27:15L<<56;
            VoxyPredictionBridge.RealColumn columns=(x,z)->Math.floorMod(x*17+z,5)==0;
            boolean[] populated=new boolean[8];
            if(real!=null)for(int i=0;i<real.length;i++)if(!VoxyPredictionBridge.air(real[i]))
                populated[(i&31)>>4|(i>>5&31)>>4<<1|(i>>10)>>4<<2]=true;
            for(int z=0;z<32;z++)for(int x=0;x<32;x++)for(int y=0;y<32;y++) {
                int i=y<<10|z<<5|x;long v=real==null?15L<<56:real[i];
                boolean exact=columns.contains(Math.floorDiv(-(32<<lod)+x*(1<<lod),16),Math.floorDiv(-(32<<lod)+z*(1<<lod),16));
                if(exact||lod==0&&populated[(x>>4)|(z>>4)<<1|(y>>4)<<2]||!VoxyPredictionBridge.air(v))expected[i]=v;
            }
            VoxyPredictionBridge.mergeReal(actual,real,lod,-1,0,-1,columns);
            assertArrayEquals(expected,actual,"level="+lod+", mode="+mode);
        }
    }

}
