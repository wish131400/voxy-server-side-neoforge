import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Read-only bounded export of immutable published GPU words; no GL or world edits. */
public class ExportPredictionMeshes {
 static Object get(Object o,String n)throws Exception {var c=o instanceof Class<?> k?k:o.getClass();var f=c.getDeclaredField(n);f.setAccessible(true);return f.get(o instanceof Class<?>?null:o);}
 static Object call(Object o,String n)throws Exception {var m=o.getClass().getDeclaredMethod(n);m.setAccessible(true);return m.invoke(o);}
 public static void agentmain(String dir,Instrumentation inst) {
  var t=new Thread(()->{try {
   Path path=Path.of(dir);Files.createDirectories(path);
   Class<?> state=Arrays.stream(inst.getAllLoadedClasses()).filter(c->c.getName().equals("dev.xantha.vss.client.prediction.ClientPredictionState")).findFirst().orElseThrow();
   List<Object> tiles=new ArrayList<>();
   for(Object manager:((Map<?,?>)get(state,"MANAGERS")).values()) synchronized(manager) {tiles.addAll(((Map<?,?>)get(manager,"ready")).values());}
   Map<Integer,List<Object>> groups=new TreeMap<>();
   for(Object tile:tiles){int lod=((Number)call(call(tile,"key"),"lod")).intValue();groups.computeIfAbsent(lod,k->new ArrayList<>()).add(tile);}
   StringBuilder csv=new StringBuilder("file,lod,bytes,axis,terrainQuads,totalQuads\n");long total=0;int seq=0;
   for(var entry:groups.entrySet()) {
    var list=entry.getValue();list.sort(Comparator.comparing(Object::toString));
    for(int i=0;i<Math.min(12,list.size());i++) {
     Object tile=list.get(i*list.size()/Math.min(12,list.size()));Object payload=get(call(tile,"mesh"),"gpuPayload");if(payload==null)continue;
     long size=((Number)call(payload,"quadBytes")).longValue();if(size<65536||size>16*1024*1024||total+size>96*1024*1024L)continue;
     int[] words=(int[])call(payload,"quads");
     byte[] bytes=new byte[(int)size];ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(words);
     String name=String.format("mesh-%03d.bin",seq++);Files.write(path.resolve(name),bytes);total+=size;
     csv.append(name).append(',').append(entry.getKey()).append(',').append(size).append(',').append(call(payload,"cellAxis")).append(',').append(call(payload,"terrainQuadCount")).append(',').append(call(payload,"quadCount")).append('\n');
    }
   }
   Files.writeString(path.resolve("manifest.csv"),csv);Files.writeString(path.resolve("source.txt"),"time="+java.time.Instant.now()+"\nloaded="+state.getProtectionDomain().getCodeSource().getLocation()+"\nresidentTiles="+tiles.size()+"\nexportedBytes="+total);
  }catch(Throwable e){try{Files.writeString(Path.of(dir,"error.txt"),e.toString());}catch(Exception ignored){}}},"vss-readonly-mesh-export");t.setDaemon(true);t.setPriority(3);t.start();
 }
}
