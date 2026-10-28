package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class VoxySectionSignatureTest {
    private static VoxySectionSignature.Value expected(long[] values)throws Exception {
        var bytes=ByteBuffer.allocate(values.length*8);for(long value:values)bytes.putLong(value);
        var hash=ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(bytes.array()));
        return new VoxySectionSignature.Value(hash.getLong(),hash.getLong(),hash.getLong(),hash.getLong());
    }
    @Test void bulkSignaturesMatchExactSha256ForDenseUniformFacesAndScratchReuse()throws Exception {
        var random=new Random(42);long[] dense=new long[32768];for(int i=0;i<dense.length;i++)dense[i]=random.nextLong();
        for(int repeat=0;repeat<3;repeat++) {
            assertEquals(expected(dense),VoxySectionSignature.section(new VoxyRenderAccess.Voxels(dense,0)));
            long[] uniform=new long[32768];Arrays.fill(uniform,-312741971831L);
            assertEquals(expected(uniform),VoxySectionSignature.section(new VoxyRenderAccess.Voxels(null,uniform[0])));
            var faces=new long[6][1024];var flat=new long[6144];
            for(int face=0;face<6;face++)for(int i=0;i<1024;i++)faces[face][i]=flat[face*1024+i]=random.nextLong();
            assertEquals(expected(flat),VoxySectionSignature.faces(faces));
        }
    }
    @Test void concurrentSignaturesNeverShareMutableDigestState()throws Exception {
        var pool=Executors.newFixedThreadPool(4);
        try {
            var tasks=new ArrayList<Callable<Void>>();
            for(int i=0;i<32;i++) {final int value=i;tasks.add(()->{
                var data=new long[32768];Arrays.fill(data,value);var hash=expected(data);
                for(int j=0;j<10;j++)assertEquals(hash,VoxySectionSignature.section(new VoxyRenderAccess.Voxels(data,0)));
                return null;
            });}
            for(var result:pool.invokeAll(tasks))result.get();
        }finally{pool.shutdownNow();}
    }
}
