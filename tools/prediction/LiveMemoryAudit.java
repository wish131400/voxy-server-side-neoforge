import java.lang.instrument.Instrumentation;
import java.lang.management.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Bounded read-only snapshots: no heap walk, GC, GL calls, transforms or world changes. */
public class LiveMemoryAudit {
    static Object get(Object target, String name) throws Exception {
        Class<?> type = target instanceof Class<?> c ? c : target.getClass();
        Field field = type.getDeclaredField(name); field.setAccessible(true);
        return field.get(target instanceof Class<?> ? null : target);
    }
    static Object call(Object target, String name) throws Exception {
        Class<?> type = target instanceof Class<?> c ? c : target.getClass();
        Method method = type.getDeclaredMethod(name); method.setAccessible(true);
        return method.invoke(target instanceof Class<?> ? null : target);
    }
    static void counts(StringBuilder out, String prefix, Object target) throws Exception {
        Class<?> type = target instanceof Class<?> c ? c : target.getClass();
        for (Field f : type.getDeclaredFields()) {
            if (!(Map.class.isAssignableFrom(f.getType()) || Collection.class.isAssignableFrom(f.getType()))) continue;
            if (target instanceof Class<?> && !Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true); Object value = f.get(target instanceof Class<?> ? null : target);
            if (value != null) out.append(prefix).append('.').append(f.getName()).append(" count=")
                    .append(value instanceof Map<?,?> m ? m.size() : ((Collection<?>)value).size()).append('\n');
        }
    }
    static void arrays(Instrumentation inst, Object object, Set<Object> seen, Map<String,Long> sizes) throws Exception {
        if (object == null || !seen.add(object)) return;
        for (Field f : object.getClass().getDeclaredFields()) {
            if (!f.getType().isArray() || Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true); Object a = f.get(object);
            if (a != null && seen.add(a)) sizes.merge(object.getClass().getSimpleName()+"."+f.getName(),inst.getObjectSize(a),Long::sum);
        }
    }
    public static void agentmain(String directory, Instrumentation inst) {
        Thread worker = new Thread(() -> {
            try {
                Path dir=Path.of(directory); Files.createDirectories(dir);
                Map<String,Class<?>> classes = new HashMap<>();
                for(Class<?> c:inst.getAllLoadedClasses()) if(c.getName().startsWith("dev.xantha.vss.")) classes.put(c.getName(),c);
                Class<?> state=classes.get("dev.xantha.vss.client.prediction.ClientPredictionState");
                Class<?> renderer=classes.get("dev.xantha.vss.client.prediction.PredictionRenderer");
                for(int round=0;round<4;round++) {
                    StringBuilder out=new StringBuilder("time="+Instant.now()+"\nloadedFrom="+state.getProtectionDomain().getCodeSource().getLocation()+"\n");
                    out.append("heap=").append(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage()).append('\n');
                    out.append("nonheap=").append(ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage()).append('\n');
                    for(var gc:ManagementFactory.getGarbageCollectorMXBeans()) out.append("gc ").append(gc.getName()).append(" count=").append(gc.getCollectionCount()).append(" timeMs=").append(gc.getCollectionTime()).append('\n');
                    for(var pool:ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) out.append("buffer ").append(pool.getName()).append(" count=").append(pool.getCount()).append(" used=").append(pool.getMemoryUsed()).append(" capacity=").append(pool.getTotalCapacity()).append('\n');
                    out.append("diagnostics=").append(call(state,"diagnostics")).append('\n');
                    counts(out,"state",state);
                    Object exact=get(state,"exactCoverage");
                    if(!(exact instanceof Map<?,?>)) counts(out,"exactCoverage",exact);
                    Set<Object> seen=Collections.newSetFromMap(new IdentityHashMap<>());
                    Map<String,Long> sizes=new TreeMap<>();
                    long estimated=0, sampleRefs=0, sampleSize=0, tiles=0;
                    for(Object manager:List.copyOf(((Map<?,?>)get(state,"MANAGERS")).values())) {
                        List<?> ready;
                        synchronized(manager) { ready=List.copyOf(((Map<?,?>)get(manager,"ready")).values()); counts(out,"manager",manager); }
                        for(String name:new String[]{"sampleCache","sampleStore","vegetation","sampler","diskCache"}) {
                            try { Object v=get(manager,name); if(v!=null) synchronized(v) { counts(out,name,v); } } catch(NoSuchFieldException ignored) {}
                        }
                        for(Object tile:ready) {
                            tiles++; estimated+=((Number)call(tile,"retainedHeapBytes")).longValue();
                            Object mesh=call(tile,"mesh"), packed=get(mesh,"packed"), payload=get(mesh,"gpuPayload"), seams=get(mesh,"seamMesh");
                            for(Object o:new Object[]{tile,mesh,packed,payload,seams}) arrays(inst,o,seen,sizes);
                            Object[] samples=(Object[])call(tile,"samples"); sampleRefs+=samples.length;
                            if(sampleSize==0) for(Object s:samples) if(s!=null) {sampleSize=inst.getObjectSize(s); break;}
                        }
                    }
                    out.append("residentTiles=").append(tiles).append(" retainedEstimate=").append(estimated).append(" sampleReferences=").append(sampleRefs).append(" sampleObjectBytes=").append(sampleSize).append('\n');
                    long gpu=0, gpuQuads=0;
                    var gpuMap=(Map<?,?>)get(renderer,"gpuTiles");
                    for(Object tile:List.copyOf(gpuMap.values())) {
                        Object packed=get(tile,"packed"); if(packed==null) continue;
                        gpu+=((Number)call(packed,"uploadBytes")).longValue(); gpuQuads+=((Number)call(packed,"quadCount")).longValue();
                        arrays(inst,packed,seen,sizes);
                    }
                    out.append("gpuTerrainTiles=").append(gpuMap.size()).append(" uploadPayloadBytes=").append(gpu).append(" quads=").append(gpuQuads).append('\n');
                    var mask=get(renderer,"exactMask"); var snapshot=get(mask,"current"); arrays(inst,snapshot,seen,sizes);
                    long sum=sizes.values().stream().mapToLong(Long::longValue).sum();
                    out.append("uniqueArrayBytes=").append(sum).append('\n');
                    sizes.forEach((k,v)->out.append(k).append(" bytes=").append(v).append('\n'));
                    out.append("Array sizes exclude referenced sample objects, map nodes, in-flight jobs, native/Rust memory and GPU memory. Resident estimates and arrays overlap; do not add them. GPU payload is logical allocation, not measured VRAM residency.\n");
                    Files.writeString(dir.resolve("audit-"+round+".txt"),out);
                    if(round<3) Thread.sleep(15000);
                }
            } catch(Throwable t) { try { Files.writeString(Path.of(directory,"audit-error.txt"),t.toString()+"\n"+Arrays.toString(t.getStackTrace())); } catch(Exception ignored) {} }
        },"vss-readonly-memory-audit");
        worker.setDaemon(true); worker.start();
    }
}
