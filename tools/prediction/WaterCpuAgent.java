import java.lang.instrument.*;
import java.lang.management.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
public class WaterCpuAgent {
 public static void main(String[] a)throws Exception {var vm=com.sun.tools.attach.VirtualMachine.attach(a[0]);try{vm.loadAgent(a[1],a[2]);}finally{vm.detach();}}
 public static void agentmain(String directory,Instrumentation inst){Thread t=new Thread(()->{try{run(Path.of(directory),inst);}catch(Throwable e){e.printStackTrace();}},"VSS-readonly-profiler");t.setDaemon(true);t.start();}
 static void run(Path p,Instrumentation inst)throws Exception{
 Files.createDirectories(p); var mx=ManagementFactory.getThreadMXBean();
 Map<Long,Long> before=new HashMap<>(); Map<Long,String> names=new HashMap<>();
 snapshot(p.resolve("before.txt"),inst);
 for(long id:mx.getAllThreadIds()){before.put(id,mx.getThreadCpuTime(id));var ti=mx.getThreadInfo(id);if(ti!=null)names.put(id,ti.getThreadName());}
 long start=System.nanoTime(); Thread.sleep(30000);
 List<String> out=new ArrayList<>();out.add("wallSeconds="+(System.nanoTime()-start)/1e9);
 for(long id:mx.getAllThreadIds()){long c=mx.getThreadCpuTime(id);var ti=mx.getThreadInfo(id,24);if(ti!=null){long delta=c-before.getOrDefault(id,0L);out.add(String.format(java.util.Locale.ROOT,"%.3f ms | %s | %s",delta/1e6,ti.getThreadName(),ti.getThreadState()));}}
 Files.write(p.resolve("cpu.txt"),out);snapshot(p.resolve("after.txt"),inst);
 }
 static void snapshot(Path p,Instrumentation inst)throws Exception{
 var state=Arrays.stream(inst.getAllLoadedClasses()).filter(c->c.getName().equals("dev.xantha.vss.client.prediction.ClientPredictionState")).findFirst().orElseThrow();
 var f=state.getDeclaredField("MANAGERS");f.setAccessible(true);StringBuilder s=new StringBuilder();
 for(Object m:((Map<?,?>)f.get(null)).values()){var method=m.getClass().getMethod("surfaceDiagnostics");method.setAccessible(true);s.append(method.invoke(m)).append('\n');}
 var mc=state.getClassLoader().loadClass("net.minecraft.client.Minecraft"); Object client=mc.getMethod("getInstance").invoke(null);s.append("paused=").append(mc.getMethod("isPaused").invoke(client)).append('\n');
 Files.writeString(p,s.toString());
 }
}
