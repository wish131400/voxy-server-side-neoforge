import com.leclowndu93150.meridian.jni.MeridianNative;
import dev.xantha.vss.client.prediction.RustWorldgenBackend;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

public class StageDisplayGridBench {
 static boolean vss;
 static ByteBuffer input=ByteBuffer.allocateDirect(64*8).order(ByteOrder.LITTLE_ENDIAN);
 static ByteBuffer output=ByteBuffer.allocateDirect(66*66*40).order(ByteOrder.LITTLE_ENDIAN);
 static int stride;
 static boolean chunked;
 static boolean fullChunks;
 static HashSet<Long> loadedChunks=new HashSet<>();
 static ByteBuffer full=ByteBuffer.allocateDirect(256*40).order(ByteOrder.LITTLE_ENDIAN);
 static ByteBuffer temporary=ByteBuffer.allocateDirect(64*40).order(ByteOrder.LITTLE_ENDIAN);
 static long query(long h,int x,int z,int side,int step,boolean single) {
  long ns=0;
  if(vss && fullChunks) {
   for(int cz=z>>4;cz<=(z+(side-1)*step)>>4;cz++) for(int cx=x>>4;cx<=(x+(side-1)*step)>>4;cx++) {
    long key=((long)cx<<32)|(cz&0xffffffffL);
    if(!loadedChunks.add(key)) continue;
    long start=System.nanoTime();
    int wrote=RustWorldgenBackend.surfaceColumns(h,cx,cz,full);
    ns+=System.nanoTime()-start;
    if(wrote!=256) throw new IllegalStateException("full chunk wrote "+wrote);
    for(int k=0;k<side*side;k++) {
     int xx=x+k%side*step,zz=z+k/side*step;
     if(xx>>4!=cx || zz>>4!=cz) continue;
     int source=((xx&15)*16+(zz&15))*40;
     for(int b=0;b<40;b++) output.put(k*40+b,full.get(source+b));
    }
   }
  } else if(vss && chunked && side==66) {
   TreeMap<Long,ArrayList<Integer>> groups=new TreeMap<>();
   for(int k=0;k<side*side;k++) {
    int cx=(x+k%side*step)>>4,cz=(z+k/side*step)>>4;
    long key=((long)cx<<32)|(cz&0xffffffffL);
    groups.computeIfAbsent(key,q->new ArrayList<>()).add(k);
   }
   for(var group:groups.values()) for(int offset=0;offset<group.size();offset+=64) {
    int n=Math.min(64,group.size()-offset); input.clear();
    for(int j=0;j<n;j++) {int k=group.get(offset+j);input.putInt(x+k%side*step).putInt(z+k/side*step);}
    long start=System.nanoTime();
    int wrote=RustWorldgenBackend.displayPoints(h,input,temporary,n);
    ns+=System.nanoTime()-start;
    if(wrote!=n) throw new IllegalStateException("chunked wrote "+wrote);
    for(int j=0;j<n;j++) for(int b=0;b<40;b++) output.put(group.get(offset+j)*40+b,temporary.get(j*40+b));
   }
  } else if(vss && side==66) {
   for(int oz=0;oz<side;oz+=8) for(int ox=0;ox<side;ox+=8) {
    int w=Math.min(8,side-ox),hh=Math.min(8,side-oz),n=w*hh;
    input.clear();
    for(int j=0;j<n;j++) input.putInt(x+(ox+j%w)*step).putInt(z+(oz+j/w)*step);
    long start=System.nanoTime();
    int wrote=RustWorldgenBackend.displayPoints(h,input,temporary,n);
    ns+=System.nanoTime()-start;
    if(wrote!=n) throw new IllegalStateException("grid wrote "+wrote);
    for(int j=0;j<n;j++) for(int k=0;k<40;k++) output.put(((oz+j/w)*side+ox+j%w)*40+k,temporary.get(j*40+k));
   }
  } else if(vss) {
   for(int offset=0;offset<side*side;) {
    int n=Math.min(single?1:64,side*side-offset);
    input.clear();
    for(int j=0;j<n;j++) {int k=offset+j; input.putInt(x+k%side*step).putInt(z+k/side*step);}
    ByteBuffer out=output.slice(offset*40,n*40).order(ByteOrder.LITTLE_ENDIAN);
    long start=System.nanoTime();
    int wrote=RustWorldgenBackend.displayPoints(h,input,out,n);
    ns+=System.nanoTime()-start;
    if(wrote!=n) throw new IllegalStateException("VSS wrote "+wrote);
    offset+=n;
   }
  } else if(single) {
   for(int k=0;k<side*side;k++) {
    ByteBuffer out=output.slice(k*32,32).order(ByteOrder.LITTLE_ENDIAN);
    long start=System.nanoTime();
    int wrote=MeridianNative.sampleColumn(h,x+k%side*step,z+k/side*step,out);
    ns+=System.nanoTime()-start;
    if(wrote!=32) throw new IllegalStateException("Meridian wrote "+wrote);
   }
  } else {
   long start=System.nanoTime();
   int wrote=MeridianNative.sampleGrid(h,x,z,step,side,output);
   ns+=System.nanoTime()-start;
   if(wrote!=side*side) throw new IllegalStateException("Meridian grid wrote "+wrote);
  }
  return ns;
 }
 public static void main(String[] args) throws Exception {
  vss=args[0].equals("vss"); stride=vss?40:32;
  System.load(Path.of(args[1]).toAbsolutePath().toString());
  int abi=vss?RustWorldgenBackend.abi():MeridianNative.abiVersion();
  String doc=Files.readString(Path.of(args[2]));
  long seed=Long.parseLong(args[3]);
  String[] names={"single_scattered","batch8_step1","batch8_step4","tile66_step1","tile66_chunked","batch8_fullchunk","batch8_step64","tile66_warm","batch8_step128","batch8_step256","batch8_step512"};
  int[] sides={16,8,8,66,66,8,8,66,8,8,8}, steps={64,1,4,1,1,1,64,1,128,256,512}, tiles={1,16,16,1,1,16,16,1,16,16,16};
  for(int scenario=0;scenario<names.length;scenario++) {
   if(args.length>5 && !args[5].equals(names[scenario])) continue;
   chunked=scenario==4;
   fullChunks=false;loadedChunks.clear();
   long start=System.nanoTime();
   long h=vss?RustWorldgenBackend.create(seed,0,doc):MeridianNative.createGraph(seed,doc);
   double init=(System.nanoTime()-start)/1e6;
   if(h==0) throw new IllegalStateException("null world");
   try {
    for(int i=0;i<32;i++) query(h,-100000+i*64,-100000,1,1,true);
    fullChunks=scenario==5;
    if(Boolean.getBoolean("vss.stageProbe")) StageProbe.reset();
    if(vss) System.err.println("VSS_QUERY_STATS_BEFORE="+RustWorldgenBackend.decorationQueryStats(h));
    long ns=0,hash=0xcbf29ce484222325L;
    StringBuilder rows=new StringBuilder("x\tz\theight\twaterY\tfluid\ttop\tunder\tdeep\n");
    int side=sides[scenario],step=steps[scenario];
    for(int tile=0;tile<tiles[scenario];tile++) {
     int x=100000+tile*2048,z=100000;
     if(scenario==7) { query(h,x,z,side,step,false); if(Boolean.getBoolean("vss.stageProbe")) StageProbe.reset(); }
     ns+=query(h,x,z,side,step,scenario==0);
     for(int k=0;k<side*side;k++) {
      int base=k*stride;
      for(int j=0;j<stride;j++) hash=(hash^(output.get(base+j)&255L))*0x100000001b3L;
      rows.append(x+k%side*step).append('\t').append(z+k/side*step).append('\t')
       .append(output.getInt(base)).append('\t').append(output.getInt(base+4)).append('\t')
       .append(vss?output.getInt(base+8):Byte.toUnsignedInt(output.get(base+17))).append('\t')
       .append(vss?output.getInt(base+16):0).append('\t')
       .append(vss?output.getInt(base+20):0).append('\t')
       .append(vss?output.getInt(base+24):0).append('\n');
     }
    }
    int count=tiles[scenario]*side*side;
    System.out.printf(Locale.ROOT,"%s,%d,%d,%s,%d,%.6f,%.6f,%.6f,%016x%n",args[0],abi,seed,names[scenario],count,init,ns/1e6,ns/1e3/count,hash);
    if(vss) System.err.println("VSS_QUERY_STATS_AFTER="+RustWorldgenBackend.decorationQueryStats(h));
    Files.writeString(Path.of(args[4],args[0]+"-"+seed+"-"+names[scenario]+".tsv"),rows);
   } finally { if(Boolean.getBoolean("vss.stageProbe")) System.err.println(StageProbe.stats()); if(vss) RustWorldgenBackend.close(h); else MeridianNative.destroy(h); }
  }
 }
}
