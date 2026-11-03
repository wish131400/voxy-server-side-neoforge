package dev.xantha.vss.client.prediction;

import com.google.gson.GsonBuilder;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/** Single-thread, offline diagnostic. Never part of a release source set. */
final class ClimateSharingProbe {
    private record Pos(int x,int y,int z) {}
    private static final class Row {
        final long[] bits=new long[6];
        int mask,stage,available;
        long calls;
    }
    private static final Map<Pos,Row> JAVA=new LinkedHashMap<>();
    private static int stage;
    private static final Map<Long,Integer> READS=new HashMap<>();
    private static final Map<Long,Integer> POINTS=new HashMap<>();
    static void column(int x,int z,boolean display) {if(stage==2)READS.merge((long)x<<32|z&0xffffffffL,display?2:1,(a,b)->a|b);}
    static void submitted(ByteBuffer input,int count,boolean display) {
        if(stage==2)for(int i=0;i<count;i++)POINTS.merge((long)input.getInt(i*8)<<32|input.getInt(i*8+4)&0xffffffffL,display?2:1,(a,b)->a|b);
    }
    private static long inconsistent;
    static void reset(long handle) {
        JAVA.clear();READS.clear();POINTS.clear();inconsistent=0;stage=1;
        RustWorldgenBackend.sharingStage(1,true);
    }
    static void stage(int value) {stage=value;RustWorldgenBackend.sharingStage(value,false);}
    static DensityFunction trace(DensityFunction source,int index) {return new Root(source,index);}
    private record Root(DensityFunction original,int index) implements DensityFunction.SimpleFunction {
        @Override public double compute(DensityFunction.FunctionContext c) {
            double value=original.compute(c);
            if(stage>0 && stage<=3) {
                var row=JAVA.computeIfAbsent(new Pos(c.blockX(),c.blockY(),c.blockZ()),p->{
                    Row r=new Row();r.available=RustWorldgenBackend.sharingLookup(p.x(),p.y(),p.z());return r;
                });
                long bits=Double.doubleToRawLongBits(value);
                if((row.mask&(1<<index))!=0 && row.bits[index]!=bits)inconsistent++;
                row.bits[index]=bits;row.mask|=1<<index;row.stage|=1<<stage;row.calls++;
            }
            return value;
        }
        @Override public double minValue(){return original.minValue();}
        @Override public double maxValue(){return original.maxValue();}
        @Override public KeyDispatchDataCodec<? extends DensityFunction> codec(){return original.codec();}
    }
    static void save(Path out,long handle,String mode,int round)throws Exception {
        String name=mode+"-"+round;
        String nativeStats=RustWorldgenBackend.sharingDump(handle,out.resolve("native-climate-"+name+".bin").toAbsolutePath().toString());
        Files.writeString(out.resolve("native-sharing-"+name+".json"),nativeStats);
        stage=4;RustWorldgenBackend.sharingStage(4,false);
        var input=ByteBuffer.allocateDirect(256*12).order(ByteOrder.LITTLE_ENDIAN);
        var output=ByteBuffer.allocateDirect(256*96).order(ByteOrder.LITTLE_ENDIAN);
        var entries=new ArrayList<>(JAVA.entrySet());
        long[] sameRaw=new long[6],sameTarget=new long[6];long complete=0,incomplete=0;
        var examples=new ArrayList<Object>();
        try(var javaOut=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(out.resolve("java-climate-"+name+".bin"))))) {
            for(int base=0;base<entries.size();base+=256) {
                int count=Math.min(256,entries.size()-base);input.clear();
                for(int i=0;i<count;i++){var p=entries.get(base+i).getKey();input.putInt(p.x()).putInt(p.y()).putInt(p.z());}
                if(RustWorldgenBackend.sharingClimate(handle,input,output,count)!=count)throw new AssertionError("Incomplete climate replay");
                for(int i=0;i<count;i++) {
                    var e=entries.get(base+i);var p=e.getKey();var row=e.getValue();
                    javaOut.writeInt(p.x());javaOut.writeInt(p.y());javaOut.writeInt(p.z());
                    javaOut.writeInt(row.mask);javaOut.writeInt(row.stage);javaOut.writeLong(row.calls);javaOut.writeInt(row.available);
                    for(long bits:row.bits)javaOut.writeLong(bits);
                    long[] targets=new long[6];
                    for(int j=0;j<6;j++) {
                        long raw=output.getLong(i*96+j*8),target=output.getLong(i*96+48+j*8);
                        long javaTarget=(long)((float)Double.longBitsToDouble(row.bits[j])*10000.0F);
                        targets[j]=javaTarget;
                        if(row.bits[j]==raw)sameRaw[j]++;
                        if(javaTarget==target)sameTarget[j]++;
                        else if(examples.size()<12)examples.add(Map.of("p",List.of(p.x(),p.y(),p.z()),"root",j,
                                "java",javaTarget,"rust",target,"javaRaw",Double.longBitsToDouble(row.bits[j]),"rustRaw",Double.longBitsToDouble(raw)));
                    }
                    for(long target:targets)javaOut.writeLong(target);
                    if(row.mask==63)complete++;else incomplete++;
                }
            }
        }
        var result=new LinkedHashMap<String,Object>();
        result.put("javaCoordinates",entries.size());result.put("complete",complete);result.put("incomplete",incomplete);
        result.put("inconsistentRootValues",inconsistent);result.put("sameRawDouble",sameRaw);result.put("sameQuantizedTarget",sameTarget);
        result.put("mismatches",examples);
        result.put("availableBeforeJava3D",JAVA.values().stream().filter(r->(r.available&1)!=0).count());
        result.put("availableBeforeJavaXZ",JAVA.values().stream().filter(r->(r.available&2)!=0).count());
        result.put("submittedExact",POINTS.values().stream().filter(v->(v&1)!=0).count());
        result.put("readExact",READS.values().stream().filter(v->(v&1)!=0).count());
        result.put("submittedDisplay",POINTS.values().stream().filter(v->(v&2)!=0).count());
        result.put("readDisplay",READS.values().stream().filter(v->(v&2)!=0).count());
        try(var data=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(out.resolve("java-columns-"+name+".bin"))))) {
            var all=new HashSet<Long>(POINTS.keySet());all.addAll(READS.keySet());
            for(long key:all){data.writeInt((int)(key>>32));data.writeInt((int)key);data.writeInt(POINTS.getOrDefault(key,0));data.writeInt(READS.getOrDefault(key,0));}
        }
        Files.writeString(out.resolve("java-sharing-"+name+".json"),new GsonBuilder().setPrettyPrinting().create().toJson(result));
        stage=0;
    }
}
