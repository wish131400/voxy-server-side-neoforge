package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

class VoxySectionDataTest {
    @Test void allFacesReadCompactAndDenseBitsWithoutExpandingSections() throws Exception {
        for(boolean dense:new boolean[]{false,true}) {
            long[] data=new long[32768];
            for(int i=0;i<data.length;i++)data[i]=dense?new Random(i).nextLong():(i/89L)<<27;
            var compact=VoxySectionData.compactCopy(data);
            var expanded=VoxySectionData.class.getDeclaredField("expanded");expanded.setAccessible(true);
            ((java.lang.ref.WeakReference<?>)expanded.get(compact)).clear();
            for(int face=0;face<6;face++) {
                long[] actual=new long[1024];compact.copyFace(face,actual);
                for(int i=0;i<1024;i++)assertEquals(data[VoxyFaceData.index(face,i)],actual[i]);
            }
            assertNull(((java.lang.ref.WeakReference<?>)expanded.get(compact)).get());
        }
    }
    @Test void reusableScratchCannotMutateCompactOrDensePublishedSections() {
        long[] scratch=new long[32768];Arrays.fill(scratch,7L<<27);
        var compact=VoxySectionData.compactCopy(scratch);
        Arrays.fill(scratch,0);assertEquals(7L<<27,compact.array()[32767]);

        long[] expected=new Random(97).longs(32768).toArray();
        System.arraycopy(expected,0,scratch,0,scratch.length);
        var dense=VoxySectionData.compactCopy(scratch);Arrays.fill(scratch,-1);
        assertArrayEquals(expected,dense.array());assertNotSame(scratch,dense.array());
    }
    @Test void compactRunsPreserveEveryVoxelBitAndExpandIntoTheDestination() throws Exception {
        long[] data=new long[32768];
        Arrays.fill(data,0,1024,-1L);
        Arrays.fill(data,1024,8192,15L<<56 | 123L<<47 | 7L<<27);
        var compact=VoxySectionData.compact(data);
        assertTrue(compact.bytes()<1024);
        long[] target=new long[32768]; Arrays.fill(target,Long.MIN_VALUE);
        compact.copyTo(target); assertArrayEquals(data,target);
        var field=VoxySectionData.class.getDeclaredField("expanded");field.setAccessible(true);
        ((java.lang.ref.WeakReference<?>)field.get(compact)).clear();
        assertArrayEquals(data,compact.array(),"Loss of a disposable expansion must not lose compact data");
        assertSame(compact.array(),compact.array());
    }

    @Test void denseNoiseUsesAnArrayInsteadOfExpandingRunMetadata() {
        long[] data=new Random(42).longs(32768).toArray();
        var compact=VoxySectionData.compact(data);
        assertTrue(compact.bytes()<data.length*8L+128);
        long[] target=new long[32768]; compact.copyTo(target);
        assertArrayEquals(data,target); assertSame(data,compact.array());
    }

    @Test void constantAirAndTheLastVoxelSurviveRoundTrip() {
        long[] data=new long[32768];Arrays.fill(data,15L<<56);
        var air=VoxySectionData.compact(data);assertTrue(air.bytes()<128);
        data=data.clone();data[32767]=Long.MIN_VALUE;
        long[] target=new long[32768];VoxySectionData.compact(data).copyTo(target);
        assertArrayEquals(data,target);
        assertThrows(IllegalArgumentException.class,()->VoxySectionData.compact(new long[0]));
    }
}
