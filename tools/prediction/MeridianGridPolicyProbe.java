import com.leclowndu93150.meridian.jni.MeridianNative;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Black-box comparison of reference grid and column APIs at identical points. */
public class MeridianGridPolicyProbe {
    public static void main(String[] args) throws Exception {
        System.load(Path.of(args[0]).toAbsolutePath().toString());
        String doc = Files.readString(Path.of(args[1]));
        boolean centered = args.length > 3 && args[3].equals("center");
        StringBuilder text = new StringBuilder("seed,step,x,z,gridHeight,columnHeight,gridWater,columnWater,gridFluid,columnFluid\n");
        for (long seed : new long[]{0,917}) for (int step : new int[]{1,2,4,8,16,32,64}) {
            long gridWorld = MeridianNative.createGraph(seed,doc);
            long columnWorld = MeridianNative.createGraph(seed,doc);
            if (gridWorld == 0 || columnWorld == 0) throw new IllegalStateException("null world");
            try {
                ByteBuffer grid = ByteBuffer.allocateDirect(64*32).order(ByteOrder.LITTLE_ENDIAN);
                ByteBuffer column = ByteBuffer.allocateDirect(32).order(ByteOrder.LITTLE_ENDIAN);
                if (MeridianNative.sampleGrid(gridWorld,100000,100000,step,8,grid) != 64) throw new IllegalStateException("grid size");
                int changed=0, max=0, water=0;
                for (int k=0;k<64;k++) {
                    int x=100000+k%8*step,z=100000+k/8*step;
                    int offset = centered ? step / 2 : 0;
                    if (MeridianNative.sampleColumn(columnWorld,x+offset,z+offset,column) != 32) throw new IllegalStateException("column size");
                    int delta=Math.abs(grid.getInt(k*32)-column.getInt(0));
                    if(delta!=0)changed++;
                    max=Math.max(max,delta);
                    if(grid.getInt(k*32+4)!=column.getInt(4)||grid.get(k*32+17)!=column.get(17))water++;
                    text.append(seed).append(',').append(step).append(',').append(x).append(',').append(z).append(',')
                        .append(grid.getInt(k*32)).append(',').append(column.getInt(0)).append(',')
                        .append(grid.getInt(k*32+4)).append(',').append(column.getInt(4)).append(',')
                        .append(Byte.toUnsignedInt(grid.get(k*32+17))).append(',').append(Byte.toUnsignedInt(column.get(17))).append('\n');
                }
                System.out.printf(Locale.ROOT,"seed=%d step=%d heightChanged=%d/64 max=%d waterChanged=%d%n",seed,step,changed,max,water);
            } finally {
                MeridianNative.destroy(gridWorld); MeridianNative.destroy(columnWorld);
            }
        }
        Files.writeString(Path.of(args[2]),text);
    }
}
