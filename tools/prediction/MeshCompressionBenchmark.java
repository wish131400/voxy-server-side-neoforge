import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import net.jpountz.lz4.*;
import com.github.luben.zstd.Zstd;

/** End-to-end int[] -> compressed bytes -> int[], using exported production meshes. */
public class MeshCompressionBenchmark {
 static volatile Object sink;
 static final LZ4Factory LZ=LZ4Factory.fastestJavaInstance();
 static byte[] raw(int[] words){byte[] b=new byte[words.length*4];ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(words);return b;}
 static byte[] compress(int[] words,int mode){byte[] b=raw(words);if(mode==0)return LZ.fastCompressor().compress(b);if(mode==1)return Zstd.compress(b,1);Deflater d=new Deflater(1);try{d.setInput(b);d.finish();byte[] out=new byte[b.length+b.length/100+1024];int n=d.deflate(out);if(!d.finished())throw new AssertionError();return Arrays.copyOf(out,n);}finally{d.end();}}
 static int[] decode(byte[] b,int len,int mode)throws Exception{byte[] raw=new byte[len];if(mode==0){int n=LZ.safeDecompressor().decompress(b,0,b.length,raw,0,len);if(n!=len)throw new AssertionError();}else if(mode==1){long n=Zstd.decompress(raw,b);if(n!=len)throw new AssertionError();}else {Inflater i=new Inflater();try{i.setInput(b);int n=i.inflate(raw);if(n!=len||!i.finished())throw new AssertionError();}finally{i.end();}}int[] words=new int[len/4];ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(words);return words;}
 static double pct(List<Long> v,double q){var s=v.stream().mapToLong(x->x).sorted().toArray();return s[(int)Math.min(s.length-1,Math.ceil(s.length*q)-1)]/1e6;}
 public static void main(String[] args)throws Exception{
  Path dir=Path.of(args[0]);var files=Files.list(dir).filter(p->p.toString().endsWith(".bin")).sorted().toList();List<int[]> input=new ArrayList<>();long bytes=0;
  for(var p:files){byte[] b=Files.readAllBytes(p);int[] w=new int[b.length/4];ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(w);input.add(w);bytes+=b.length;}
  StringBuilder result=new StringBuilder("codec,tiles,rawBytes,compressedBytes,compressMsPerBatch,decodeMsPerBatch,compressP50Ms,compressP95Ms,decodeP50Ms,decodeP95Ms,decodeP99Ms,decodeMaxMs\n");
  for(int mode=0;mode<3;mode++){
   long compressed=0;List<byte[]> blobs=new ArrayList<>();for(int[] w:input){byte[] b=compress(w,mode);assert Arrays.equals(w,decode(b,w.length*4,mode));blobs.add(b);compressed+=b.length;}
   List<Long> ct=new ArrayList<>(),dt=new ArrayList<>();long totalC=0,totalD=0;
   for(int round=-5;round<15;round++)for(int j=0;j<input.size();j++){
    int index=(j+Math.max(0,round)*7)%input.size();int[] w=input.get(index);long t=System.nanoTime();sink=compress(w,mode);long c=System.nanoTime()-t;t=System.nanoTime();int[] restored=decode(blobs.get(index),w.length*4,mode);long d=System.nanoTime()-t;sink=restored;if(!Arrays.equals(w,restored))throw new AssertionError("mismatch");if(round>=0){ct.add(c);dt.add(d);totalC+=c;totalD+=d;}
   }
   String row=String.format(Locale.ROOT,"%s,%d,%d,%d,%.3f,%.3f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f%n",new String[]{"lz4-java-fast","zstd-1","deflate-1"}[mode],input.size(),bytes,compressed,totalC/15e6,totalD/15e6,pct(ct,.5),pct(ct,.95),pct(dt,.5),pct(dt,.95),pct(dt,.99),pct(dt,1));System.out.print(row);result.append(row);
  }Files.writeString(dir.getParent().resolve("benchmark.csv"),result);
 }
}
