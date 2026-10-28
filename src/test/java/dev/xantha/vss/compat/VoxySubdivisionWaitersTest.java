package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class VoxySubdivisionWaitersTest {
    @Test void requestBatchUsesNativeHighLowWordOrderAndTransfersOrFreesItsAllocationExactlyOnce() throws Exception {
        Class<?> type=Class.forName(VoxyPredictionBridge.class.getName()+"$RequestBatches");
        var constructor=type.getDeclaredConstructors()[0];constructor.setAccessible(true);
        var submit=type.getDeclaredMethod("submit",Object.class,long[].class);submit.setAccessible(true);
        Object batches=constructor.newInstance(Buffer.class.getConstructor(long.class),Buffer.class.getField("address"),
                Receiver.class.getMethod("submitRequestBatch",Buffer.class),Buffer.class.getMethod("free"));
        var receiver=new Receiver();
        long[] positions={0x40123456fedcba98L,0x1ffffffffffffff0L};
        submit.invoke(batches,receiver,positions);
        assertArrayEquals(positions,receiver.positions);
        assertEquals(1,receiver.buffer.frees);assertEquals(0,Buffer.live);
        receiver.fail=true;
        var error=assertThrows(java.lang.reflect.InvocationTargetException.class,()->submit.invoke(batches,receiver,positions));
        assertInstanceOf(IllegalStateException.class,error.getCause());
        assertEquals(1,receiver.buffer.frees);assertEquals(0,Buffer.live);
    }
    public static final class Buffer {
        public final long address;public final long size;int frees;static int live;
        public Buffer(long bytes) {size=bytes;address=org.lwjgl.system.MemoryUtil.nmemAlloc(bytes);live++;}
        public void free() {assertEquals(0,frees++);org.lwjgl.system.MemoryUtil.nmemFree(address);live--;}
    }
    public static final class Receiver {
        boolean fail;Buffer buffer;long[] positions;
        public void submitRequestBatch(Buffer batch) {
            buffer=batch;if(fail)throw new IllegalStateException("stopped before transfer");
            int count=org.lwjgl.system.MemoryUtil.memGetInt(batch.address);
            assertEquals(8L+count*8L,batch.size);assertEquals(0,org.lwjgl.system.MemoryUtil.memGetInt(batch.address+4));
            positions=new long[count];
            for(int i=0;i<count;i++)positions[i]=(long)org.lwjgl.system.MemoryUtil.memGetInt(batch.address+8L+i*8L)<<32
                    |Integer.toUnsignedLong(org.lwjgl.system.MemoryUtil.memGetInt(batch.address+12L+i*8L));
            batch.free();
        }
    }
    @Test void boundedRechecksVisitEveryWaiterWithoutDuplicateOrStaleQueueEntries() {
        var waits=new VoxySubdivisionWaiters();
        for(long i=0;i<100;i++)assertTrue(waits.add(i));
        assertFalse(waits.add(0));
        var seen=new java.util.HashSet<Long>();
        for(int batch=0;batch<4;batch++) {
            long[] positions=waits.nextBatch(32);assertEquals(32,positions.length);
            for(long p:positions)seen.add(p);
        }
        assertEquals(100,seen.size());assertEquals(100,waits.size());
        for(long i=0;i<100;i++)assertTrue(waits.remove(i));
        assertEquals(0,waits.nextBatch(32).length);
        for(int i=0;i<10000;i++) {waits.add(123);waits.remove(123);}
        waits.add(-123);assertArrayEquals(new long[]{-123},waits.nextBatch(32));
        waits.clear();assertEquals(0,waits.size());assertFalse(waits.contains(-123));
    }
    @Test void boundedRotationKeepsTheReasonForEachWaitAndForgetDropsIt() {
        var waits=new VoxySubdivisionWaiters();waits.add(1,false);waits.add(2,true);
        assertArrayEquals(new long[]{1,2},waits.nextBatch(32));
        assertFalse(waits.needsSamples(1));assertTrue(waits.needsSamples(2));
        assertFalse(waits.add(2,false));assertFalse(waits.needsSamples(2));
        waits.remove(1);waits.add(1);assertTrue(waits.needsSamples(1));
        waits.clear();assertFalse(waits.needsSamples(1));
    }
}
