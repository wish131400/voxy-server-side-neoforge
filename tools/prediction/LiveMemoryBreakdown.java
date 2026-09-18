import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** One bounded pass over published tile metadata and exact-coverage metadata. */
public class LiveMemoryBreakdown {
 static Object get(Object o,String n)throws Exception {Class<?> c=o instanceof Class<?> k?k:o.getClass();Field f=c.getDeclaredField(n);f.setAccessible(true);return f.get(o instanceof Class<?>?null:o);}
 static Object call(Object o,String n)throws Exception {Method m=o.getClass().getDeclaredMethod(n);m.setAccessible(true);return m.invoke(o);}
 public static void agentmain(String path,Instrumentation inst){Thread t=new Thread(()->{try{
  Class<?> state=Arrays.stream(inst.getAllLoadedClasses()).filter(c->c.getName().equals("dev.xantha.vss.client.prediction.ClientPredictionState")).findFirst().orElseThrow();
  StringBuilder out=new StringBuilder(); Map<String,long[]> groups=new TreeMap<>();
  for(Object manager:((Map<?,?>)get(state,"MANAGERS")).values()){
   List<?> tiles;Set<?> desired,leaves;
   synchronized(manager){tiles=List.copyOf(((Map<?,?>)get(manager,"ready")).values());desired=Set.copyOf((Set<?>)get(manager,"desiredKeys"));leaves=Set.copyOf((Set<?>)get(manager,"terrainLeaves"));}
   for(Object tile:tiles){Object key=call(tile,"key");String category=desired.contains(key)?"desired":"notDesired";
    Object mesh=call(tile,"mesh"),payload=get(mesh,"gpuPayload");
    long bytes=((Number)call(tile,"retainedHeapBytes")).longValue();
    long packed=payload==null?0:((Number)call(payload,"uploadBytes")).longValue();
    for(String label:new String[]{category,"lod"+call(key,"lod"),leaves.contains(key)?"leaf":"notLeaf"}){
     long[] row=groups.computeIfAbsent(label,k->new long[3]);row[0]++;row[1]+=bytes;row[2]+=packed;
    }
   }
  }
  groups.forEach((k,v)->out.append(k).append(" tiles=").append(v[0]).append(" estimatedBytes=").append(v[1]).append(" packedBytes=").append(v[2]).append('\n'));
  Object index=get(state,"exactCoverage");
  if(index instanceof Map<?,?> coverage) {
  long pos=0,neg=0,keySize=0,valueSize=0;
  Method present=null;
  for(var entry:coverage.entrySet()){
   if(present==null){present=entry.getValue().getClass().getDeclaredMethod("present");present.setAccessible(true);keySize=inst.getObjectSize(entry.getKey());valueSize=inst.getObjectSize(entry.getValue());}
   if((boolean)present.invoke(entry.getValue()))pos++;else neg++;
  }
  out.append("coverage positive=").append(pos).append(" negative=").append(neg).append(" keyShallow=").append(keySize).append(" valueShallow=").append(valueSize).append('\n');
  } else {
   Map<?,?> pages=(Map<?,?>)get(index,"pages");long pos=0,arrays=0;
   for(Object page:pages.values())synchronized(page){for(long bits:(long[])get(page,"present"))pos+=Long.bitCount(bits);arrays+=inst.getObjectSize(get(page,"present"))+inst.getObjectSize(get(page,"starts"));}
   out.append("coverage pages=").append(pages.size()).append(" positive=").append(pos).append(" pageArrayBytes=").append(arrays).append('\n');
  }
  Object offsets=get(state,"sweepOffsetsReference");
  if(offsets instanceof List<?> old)out.append("offsets=").append(old.size()).append(" itemShallow=").append(old.isEmpty()?0:inst.getObjectSize(old.getFirst())).append('\n');
  else if(offsets!=null)out.append("offsetArrayBytes=").append(inst.getObjectSize(get(offsets,"values"))).append('\n');
  Files.writeString(Path.of(path),out);
 }catch(Throwable e){try{Files.writeString(Path.of(path),e.toString());}catch(Exception ignored){}}},"vss-readonly-memory-breakdown");t.setDaemon(true);t.start();}
}
