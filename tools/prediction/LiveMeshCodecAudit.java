import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Read existing VSS codec state and published blob headers; never compress, decode or change the world. */
public class LiveMeshCodecAudit {
 static Object get(Object o,String n)throws Exception {var c=o instanceof Class<?> k?k:o.getClass();var f=c.getDeclaredField(n);f.setAccessible(true);return f.get(o instanceof Class<?>?null:o);}
 static Object call(Object o,String n)throws Exception {var m=o.getClass().getDeclaredMethod(n);m.setAccessible(true);return m.invoke(o);}
 static String source(Class<?> c){try{return String.valueOf(c.getProtectionDomain().getCodeSource().getLocation());}catch(Exception e){return "unavailable";}}
 public static void agentmain(String file,Instrumentation inst){var t=new Thread(()->{try{
  var classes=new HashMap<String,Class<?>>();for(var c:inst.getAllLoadedClasses()) classes.put(c.getName(),c);
  var codec=classes.get("dev.xantha.vss.client.prediction.PredictionMeshCompression");
  StringBuilder out=new StringBuilder("time="+java.time.Instant.now()+"\n");
  out.append("codecClassLoaded=").append(codec!=null).append('\n');
  if(codec!=null){out.append("vssSource=").append(source(codec)).append('\n');Object bridge=get(codec,"ZSTD");out.append("zstdSelfTestPassed=").append(bridge!=null).append('\n');
   if(bridge!=null){Method method=(Method)get(bridge,"compress");Class<?> owner=method.getDeclaringClass();out.append("selectedZstdClass=").append(owner.getName()).append("\nselectedZstdSource=").append(source(owner)).append('\n').append("selectedZstdClassResource=").append(owner.getResource("Zstd.class")).append('\n');}
  }
  for(var e:classes.entrySet()) if(e.getKey().toLowerCase(Locale.ROOT).contains("zstd") && e.getKey().endsWith("Zstd")) out.append("loadedZstd=").append(e.getKey()).append(" source=").append(source(e.getValue())).append('\n');
  var state=classes.get("dev.xantha.vss.client.prediction.ClientPredictionState");
  long raw=0,compressed=0,zstd=0,other=0,rawBytes=0,compressedBytes=0,totalBytes=0;
  if(state!=null) for(var manager:((Map<?,?>)get(state,"MANAGERS")).values()){
   List<?> ready;synchronized(manager){ready=List.copyOf(((Map<?,?>)get(manager,"ready")).values());}
   for(var tile:ready){var mesh=call(tile,"mesh");var packed=get(mesh,"gpuPayload");if(packed==null)continue;
    totalBytes+=((Number)call(tile,"retainedHeapBytes")).longValue();
    Object blob=get(packed,"compressed");
    if(blob==null){raw++;int[] words=(int[])get(packed,"quads");if(words!=null)rawBytes+=words.length*4L;}
    else {compressed++;if((boolean)call(blob,"zstd"))zstd++;else other++;rawBytes+=((Number)call(blob,"rawBytes")).longValue();compressedBytes+=((byte[])call(blob,"bytes")).length;}
   }
  }
  out.append("rawTiles=").append(raw).append(" compressedTiles=").append(compressed).append(" zstdTiles=").append(zstd).append(" fallbackTiles=").append(other).append(" rawEquivalentBytes=").append(rawBytes).append(" compressedBlobBytes=").append(compressedBytes).append(" tileRetainedEstimate=").append(totalBytes).append('\n');
  Files.writeString(Path.of(file),out);
 }catch(Throwable e){try{Files.writeString(Path.of(file),e.toString());}catch(Exception ignored){}}},"vss-readonly-codec-audit");t.setDaemon(true);t.start();}
}
