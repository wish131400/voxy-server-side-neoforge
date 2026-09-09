import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Read-only scheduler snapshot. No transforms or live world/chunk edits. */
public class LiveRefinementSnapshot {
    static Object field(Object target,String name) throws Exception {
        Class<?> c=target instanceof Class<?> t?t:target.getClass();
        Field f=c.getDeclaredField(name); f.setAccessible(true);
        return f.get(target instanceof Class<?>?null:target);
    }
    static Object call(Object target,String name,Object... args) throws Exception {
        if(name.equals("refinementReady") && Arrays.stream(target.getClass().getDeclaredMethods())
                .noneMatch(m->m.getName().equals(name))) return true;
        Method m=Arrays.stream(target.getClass().getDeclaredMethods())
                .filter(v->v.getName().equals(name)&&v.getParameterCount()==args.length).findFirst().orElseThrow();
        m.setAccessible(true);return m.invoke(target,args);
    }
    public static void agentmain(String output,Instrumentation inst) throws Exception {
        Path dir=Path.of(output);Files.createDirectories(dir);
        Class<?> state=Arrays.stream(inst.getAllLoadedClasses()).filter(c->c.getName()
                .equals("dev.xantha.vss.client.prediction.ClientPredictionState")).findFirst().orElseThrow();
        Map<?,?> managers=(Map<?,?>)field(state,"MANAGERS");
        for(var entry:managers.entrySet()) {
            if(!entry.getKey().toString().contains("overworld")) continue;
            Object manager=entry.getValue();StringBuilder out=new StringBuilder("x\tz\tlod\tleaf\taxis\ttarget\tneeded\tcanRefine\tpending\tpriority\n");
            String diag;
            synchronized(manager) {
                Map<?,?> ready=(Map<?,?>)field(manager,"ready");
                Set<?> desired=(Set<?>)field(manager,"desiredKeys"),leaves=(Set<?>)field(manager,"terrainLeaves"),pending=(Set<?>)field(manager,"pending");
                for(Object key:desired) {
                    Object tile=ready.get(key);
                    out.append(call(key,"tileX")).append('\t').append(call(key,"tileZ")).append('\t')
                       .append(call(key,"lod")).append('\t').append(leaves.contains(key)).append('\t')
                       .append(tile==null?0:call(tile,"cellAxis")).append('\t').append(call(manager,"targetCellAxis",key)).append('\t')
                       .append(call(manager,"terrainBuildNeeded",key)).append('\t').append(call(manager,"refinementReady",key)).append('\t')
                       .append(pending.contains(key)).append('\t').append(call(manager,"workPriority",key,false)).append('\n');
                }
                diag="x="+field(manager,"cameraBlockX")+",z="+field(manager,"cameraBlockZ")+"\n"+call(manager,"surfaceDiagnostics");
            }
            Files.writeString(dir.resolve("refinement.tsv"),out);
            Files.writeString(dir.resolve("diagnostics.txt"),diag);
        }
    }
}
