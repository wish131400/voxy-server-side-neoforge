package dev.xantha.vss.compat;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Small content evidence survives native snapshot eviction without retaining section arrays. */
final class VoxySectionSignature {
    record Value(long a,long b,long c,long d) { }
    private static final ThreadLocal<VoxySectionSignature> WORKER=ThreadLocal.withInitial(VoxySectionSignature::new);
    private final MessageDigest digest;
    private final ByteBuffer buffer=ByteBuffer.allocate(8192);
    private final java.nio.LongBuffer longs=buffer.asLongBuffer();
    private VoxySectionSignature() {
        try {digest=MessageDigest.getInstance("SHA-256");}
        catch(NoSuchAlgorithmException impossible) {throw new AssertionError(impossible);}
    }
    static Value section(VoxyRenderAccess.Voxels data) {
        var worker=WORKER.get();worker.begin();
        if(data.data()!=null)worker.add(data.data());
        else {
            worker.longs.clear();while(worker.longs.hasRemaining())worker.longs.put(data.uniform());
            for(int i=0;i<32768;i+=worker.longs.capacity())worker.digest.update(worker.buffer.array());
            worker.longs.clear();
        }
        return worker.end();
    }
    static Value faces(long[][] faces) {
        var worker=WORKER.get();worker.begin();
        for(var face:faces)worker.add(face);
        return worker.end();
    }
    private void begin() {digest.reset();buffer.clear();longs.clear();}
    private void add(long[] values) {
        for(int start=0;start<values.length;) {
            int count=Math.min(longs.remaining(),values.length-start);
            longs.put(values,start,count);start+=count;
            if(!longs.hasRemaining())flush();
        }
    }
    private void flush() {digest.update(buffer.array(),0,longs.position()*Long.BYTES);longs.clear();}
    private Value end() {flush();var bytes=ByteBuffer.wrap(digest.digest());return new Value(bytes.getLong(),bytes.getLong(),bytes.getLong(),bytes.getLong());}
}
