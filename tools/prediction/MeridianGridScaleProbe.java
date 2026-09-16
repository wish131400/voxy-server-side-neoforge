import com.leclowndu93150.meridian.jni.MeridianNative;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Compare a reference grid's LOD policy with explicit noise-cell settings. */
public class MeridianGridScaleProbe {
    public static void main(String[] args) throws Exception {
        System.load(Path.of(args[0]).toAbsolutePath().toString());
        String doc=Files.readString(Path.of(args[1]));
        var files=Files.list(Path.of(args[2])).filter(p->p.toString().endsWith(".json")).sorted().toList();
        StringBuilder result=new StringBuilder("seed,step,settings,changed,max,mae\n");
        for(long seed:new long[]{0,917}) for(int step:new int[]{2,4,8,16,32,64}) {
            int[] heights=new int[64];
            long original=MeridianNative.createGraph(seed,doc);
            try {
                var buffer=ByteBuffer.allocateDirect(64*32).order(ByteOrder.LITTLE_ENDIAN);
                if(MeridianNative.sampleGrid(original,100000,100000,step,8,buffer)!=64)throw new IllegalStateException();
                for(int k=0;k<64;k++)heights[k]=buffer.getInt(k*32);
            }finally{MeridianNative.destroy(original);}
            for(var path:files) {
                long h;
                try { h=MeridianNative.createGraph(seed,Files.readString(path)); }
                catch(IllegalArgumentException unsupported) { continue; }
                if(h==0)throw new IllegalStateException(path.toString());
                int changed=0,max=0;long total=0;
                try {
                    var buffer=ByteBuffer.allocateDirect(32).order(ByteOrder.LITTLE_ENDIAN);
                    for(int k=0;k<64;k++) {
                        if(MeridianNative.sampleColumn(h,100000+k%8*step,100000+k/8*step,buffer)!=32)throw new IllegalStateException();
                        int delta=Math.abs(heights[k]-buffer.getInt(0));
                        if(delta!=0)changed++;max=Math.max(max,delta);total+=delta;
                    }
                }finally{MeridianNative.destroy(h);}
                result.append(seed).append(',').append(step).append(',').append(path.getFileName()).append(',')
                    .append(changed).append(',').append(max).append(',').append(total/64.).append('\n');
            }
        }
        Files.writeString(Path.of(args[3]),result);
    }
}
