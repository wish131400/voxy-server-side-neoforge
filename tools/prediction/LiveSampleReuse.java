import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Read-only per-tile reuse estimate. Does not alter live samples or retain them after the pass. */
public class LiveSampleReuse {
 static Object get(Object o,String n)throws Exception{Class<?> c=o instanceof Class<?> k?k:o.getClass();Field f=c.getDeclaredField(n);f.setAccessible(true);return f.get(o instanceof Class<?>?null:o);}
 static Object call(Object o,String n)throws Exception{Method m=o.getClass().getDeclaredMethod(n);m.setAccessible(true);return m.invoke(o);}
 public static void agentmain(String path,Instrumentation inst){Thread t=new Thread(()->{try{
  var state=Arrays.stream(inst.getAllLoadedClasses()).filter(c->c.getName().equals("dev.xantha.vss.client.prediction.ClientPredictionState")).findFirst().orElseThrow();
  long refs=0,identities=0,values=0,tiles=0,nanos=0,heightBytes=0,objectBytes=0;
  for(Object manager:((Map<?,?>)get(state,"MANAGERS")).values()){
   List<?> ready;synchronized(manager){ready=List.copyOf(((Map<?,?>)get(manager,"ready")).values());}
   for(Object tile:ready){
    Object[] samples=(Object[])call(tile,"samples");long start=System.nanoTime();
    Set<Object> identity=Collections.newSetFromMap(new IdentityHashMap<>());Set<Object> equal=new HashSet<>();
    for(Object s:samples)if(s!=null){identity.add(s);equal.add(s);if(objectBytes==0)objectBytes=inst.getObjectSize(s);}
    refs+=samples.length;identities+=identity.size();values+=equal.size();nanos+=System.nanoTime()-start;tiles++;
    int[] h=(int[])call(tile,"heights"),g=(int[])call(tile,"groundHeights");if(h!=g && Arrays.equals(h,g))heightBytes+=inst.getObjectSize(g);
    if(tiles%64==0)Thread.sleep(5);
   }
  }
  Files.writeString(Path.of(path),"tiles="+tiles+" sampleRefs="+refs+" perTileIdentitySum="+identities+" perTileValueSum="+values+" sampleObjectBytes="+objectBytes+" potentialBytesBeforeCrossTileSharing="+((identities-values)*objectBytes)+" identicalHeightArrayBytes="+heightBytes+" inspectionMs="+nanos/1e6+"\nNot a measured retained-heap reduction: cross-tile/cache sharing is excluded. No samples were changed.\n");
 }catch(Throwable e){try{Files.writeString(Path.of(path),e.toString());}catch(Exception ignored){}}},"vss-readonly-sample-reuse");t.setPriority(Thread.MIN_PRIORITY);t.setDaemon(true);t.start();}
}
