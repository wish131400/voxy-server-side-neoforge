package dev.xantha.vss.compat;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Small content evidence survives native snapshot eviction without retaining section arrays. */
final class VoxySectionSignature {
    record Value(long a,long b,long c,long d) { }
    private static final ThreadLocal<VoxySectionSignature> WORKER=ThreadLocal.withInitial(VoxySectionSignature::new);
    private static final Value EMPTY_FACE=faces(new long[][]{new long[1024]});
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
    /** Native input of the single plane consumed by the adjacent section. Missing means air. */
    static Value face(VoxyRenderAccess.Voxels data,int face) {
        if(data==null || data.data()==null && data.uniform()==0)return EMPTY_FACE;
        var worker=WORKER.get();worker.begin();
        for(int i=0;i<1024;i++)worker.longs.put(data==null?0:data.get(VoxyFaceData.index(face,i)));
        return worker.end();
    }
    /** Canonical compact storage: include run ends as well as values. */
    static Value compact(long[] values,int[] ends) {
        var worker=WORKER.get();worker.begin();
        worker.longs.put(ends==null?0:1).put(values.length);
        worker.add(values);
        if(ends!=null)for(int end:ends) {
            worker.longs.put(end);if(!worker.longs.hasRemaining())worker.flush();
        }
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
