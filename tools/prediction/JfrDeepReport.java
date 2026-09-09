import jdk.jfr.consumer.*;
import java.nio.file.*;
import java.util.*;

/** Profile categories are mutually exclusive; inclusive call stacks are separate. */
class JfrDeepReport {
    static String method(RecordedFrame f) { return f.getMethod().getType().getName()+"."+f.getMethod().getName(); }
    static void add(Map<String,Double> map,String key,double v) { map.merge(key,v,Double::sum); }
    static void print(String title,Map<String,Double> data,int limit) {
        System.out.println("\n"+title);
        data.entrySet().stream().sorted(Map.Entry.<String,Double>comparingByValue().reversed()).limit(limit)
                .forEach(e->System.out.printf(Locale.ROOT,"%.4f %s%n",e.getValue(),e.getKey()));
    }
    public static void main(String[] args) throws Exception {
        var categories=new TreeMap<String,Double>(); var nativeTop=new TreeMap<String,Double>();
        var workerWait=new TreeMap<String,Double>(); var threadCpu=new TreeMap<String,Double>();
        var cpuCount=new TreeMap<String,Double>(); var render=new TreeMap<String,Double>();
        var types=new TreeMap<String,Double>(); var allocation=new TreeMap<String,Double>();
        var seenAlloc=new HashSet<Long>(); var first=java.time.Instant.MAX; var last=java.time.Instant.MIN;
        // ThreadStart metadata can predate recording by minutes. Use actual
        // execution samples for the observation window and clip waits to it.
        try(var r=new RecordingFile(Path.of(args[0]))) { while(r.hasMoreEvents()) {
            var e=r.readEvent();String n=e.getEventType().getName();
            if(n.equals("jdk.ExecutionSample")||n.equals("jdk.NativeMethodSample")) {
                if(e.getStartTime().isBefore(first))first=e.getStartTime();
                if(e.getEndTime().isAfter(last))last=e.getEndTime();
            }
        }}
        try(var r=new RecordingFile(Path.of(args[0]))) { while(r.hasMoreEvents()) {
            var e=r.readEvent(); var name=e.getEventType().getName();
            var stack=e.getStackTrace();var frames=stack==null?List.<RecordedFrame>of():stack.getFrames();
            if(name.equals("jdk.ExecutionSample")||name.equals("jdk.NativeMethodSample")) {
                var thread=e.getThread("sampledThread");if(thread==null)continue;
                String t=thread.getJavaName(); add(types,name,1);
                if(t.equals("vss-prediction-overworld")) {
                    String group="other";
                    for(var f:frames) {String m=method(f);if(m.contains("RustWorldgenBackend.")){group=m.substring(m.lastIndexOf('.')+1);break;}}
                    if(group.equals("other")&&frames.stream().anyMatch(f->method(f).contains("PredictionSurfaceFeatureAdapters")))group="Java-feature";
                    add(categories,group,1); add(nativeTop,name+" | "+group,1);
                }
                if(t.equals("Render thread"))for(var m:frames.stream().map(JfrDeepReport::method).distinct().toList())
                    if(m.startsWith("dev.xantha")||m.startsWith("java.util.TimSort"))add(render,m,1);
            } else if(name.equals("jdk.ThreadCPULoad")) {
                var t=e.getThread("eventThread");if(t==null)continue;String id=t.getJavaName()+" #"+t.getJavaThreadId();
                add(threadCpu,id,e.getFloat("user")+e.getFloat("system"));add(cpuCount,id,1);
            } else if(name.equals("jdk.JavaMonitorEnter")||name.equals("jdk.ThreadPark")) {
                var t=e.getThread("eventThread"); if(t!=null&&t.getJavaName().startsWith("vss-")) {
                    String site=frames.stream().map(JfrDeepReport::method).filter(m->m.startsWith("dev.xantha")).findFirst().orElse("other");
                    var from=e.getStartTime().isBefore(first)?first:e.getStartTime();
                    var to=e.getEndTime().isAfter(last)?last:e.getEndTime();
                    add(workerWait,t.getJavaName()+" | "+name+" | "+site,Math.max(0,java.time.Duration.between(from,to).toNanos()/1e6));
                }
            } else if(name.equals("jdk.ObjectAllocationSample")) {
                var t=e.getThread("eventThread");if(t==null||seenAlloc.add(t.getJavaThreadId()))continue;
                if(t.getJavaName().equals("vss-worldgen-profile")) {
                    String site=frames.stream().map(JfrDeepReport::method).filter(m->m.startsWith("dev.xantha")).findFirst().orElse("other");
                    add(allocation,site+" | "+e.getClass("objectClass").getName(),e.getLong("weight")/1048576.0);
                }
            }
        }}
        System.out.println("first="+first+" last="+last+" seconds="+java.time.Duration.between(first,last).toNanos()/1e9);
        print("VSS_WORKER_EXCLUSIVE_SAMPLES",categories,30);print("SAMPLE_TYPES",nativeTop,35);
        print("VSS_LOCK_PARK_TOTAL_MS",workerWait,25);print("RENDER_VSS_INCLUSIVE",render,30);
        threadCpu.replaceAll((k,v)->v/cpuCount.get(k));print("THREAD_CPU_LOAD_MEAN_MACHINE_FRACTION",threadCpu,30);
        print("COVERAGE_ALLOCATION_MIB",allocation,20);
    }
}
