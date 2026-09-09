package dev.xantha.vss.client.prediction;

import java.nio.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/** Development adapter for the independent noise ABI; never selected as the game's terrain backend.
 * Buffers are little endian, start at index zero (use slice for offsets), and may not overlap. */
public final class NativeNoiseReference {
    static native int abi();
    static native long capabilities();
    static native long create(long seed,int randomKind,int firstOctave,double[] amplitudes,int legacy);
    static native int sample(long handle,ByteBuffer points,ByteBuffer output,int count);
    static native int close(long handle);
    static void require(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
    static ByteBuffer buffer(int bytes) { return ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN); }
    public static void main(String[] args) throws Exception {
        System.load(Path.of(args[0]).toAbsolutePath().toString());
        require(abi()==1 && capabilities()==1,"independent noise-only ABI");
        int checked=0;
        for(long seed:new long[]{0,-1,693280690516334765L,-6939851821800531130L}) for(int kind=0;kind<2;kind++) {
            double[] amps={1,0,1,.5,1};
            var reference=NormalNoise.create(VanillaKernelReference.random(seed,kind),-7,amps);
            long handle=create(seed,kind,-7,amps,0); require(handle>0,"create");
            try {
                var input=buffer(4096*24); var output=buffer(4096*8);
                var rng=new Random(1511); double[] expected=new double[4096];
                for(int i=0;i<4096;i++) {
                    double x=rng.nextDouble()*60000000-30000000,y=rng.nextDouble()*384-64,z=rng.nextDouble()*60000000-30000000;
                    input.putDouble(x).putDouble(y).putDouble(z); expected[i]=reference.getValue(x,y,z);
                }
                require(sample(handle,input,output,4096)==4096,"batch count");
                for(int i=0;i<4096;i++) require(Double.doubleToRawLongBits(output.getDouble(i*8))==Double.doubleToRawLongBits(expected[i]),"JNI parity "+i);
                checked+=4096;
                require(sample(handle,input,output.asReadOnlyBuffer(),4096)==-1,"read-only output");
                require(sample(handle,input,input,1)==-1,"overlapping buffers");
                require(sample(handle,input,buffer(7),1)==-1,"short output");
                require(sample(handle,ByteBuffer.allocate(24),output,1)==-1,"heap input");
                require(sample(handle,input,output,-1)==-1,"negative count");
                require(sample(handle,input,output,65537)==-1,"oversize count");
                output.putDouble(0,123); input.putDouble(0,Double.NaN);
                require(sample(handle,input,output,4096)==-3 && output.getDouble(0)==123,"invalid coordinates cannot partially overwrite output");
                input.putDouble(0,0); input.position(1); output.position(1);
                var sliced=input.slice().order(ByteOrder.LITTLE_ENDIAN);
                sliced.putDouble(0,-17).putDouble(8,63.5).putDouble(16,23);
                var slicedOut=output.slice().order(ByteOrder.LITTLE_ENDIAN);
                require(sample(handle,sliced,slicedOut,1)==1,"unaligned slices");
                require(Double.doubleToRawLongBits(slicedOut.getDouble(0))==Double.doubleToRawLongBits(reference.getValue(-17,63.5,23)),"slice value");
            } finally { require(close(handle)==1,"close live handle"); }
            require(close(handle)==0 && sample(handle,buffer(24),buffer(8),1)==-2,"stale handle");
        }
        require(create(0,99,-7,new double[]{1},0)==0,"invalid algorithm");
        require(create(0,1,-7,new double[]{Double.NaN},0)==0,"invalid amplitude");
        require(create(0,1,-7,new double[65],0)==0,"oversize amplitude count");
        long handle=create(42,1,-7,new double[]{1,1},0);
        try(var pool=Executors.newFixedThreadPool(4)) {
            var start=new CountDownLatch(1); List<Future<Integer>> jobs=new ArrayList<>();
            for(int i=0;i<4;i++) jobs.add(pool.submit(()->{ var in=buffer(65536*24); var out=buffer(65536*8); start.await(); return sample(handle,in,out,65536); }));
            start.countDown(); close(handle);
            for(var job:jobs) { int value=job.get(); require(value==-2 || value==65536,"concurrent close must retain active leases"); }
        }
        System.out.println("JNI noise reference: "+checked+" exact doubles; validation and close race passed");
    }
}
