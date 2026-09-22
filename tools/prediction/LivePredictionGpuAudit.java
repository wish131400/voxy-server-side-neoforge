import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
/** Read-only logical buffer inventory scheduled on Minecraft's render thread. */
public class LivePredictionGpuAudit {
    static Object field(Object owner,String name)throws Exception {
        Class<?> type=owner instanceof Class<?> c?c:owner.getClass();
        Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(owner instanceof Class<?>?null:owner);
    }
    static long number(Object owner,String name)throws Exception{return ((Number)field(owner,name)).longValue();}
    public static void agentmain(String output,Instrumentation inst)throws Exception {
        Map<String,Class<?>> classes=new HashMap<>();for(Class<?> c:inst.getAllLoadedClasses())classes.put(c.getName(),c);
        Class<?> mc=classes.get("net.minecraft.client.Minecraft");
        Object client=mc.getMethod("getInstance").invoke(null);
        mc.getMethod("execute",Runnable.class).invoke(client,(Runnable)()->{
            try {
                Class<?> renderer=classes.get("dev.xantha.vss.client.prediction.PredictionRenderer");
                Set<Object> tiles=Collections.newSetFromMap(new IdentityHashMap<>());
                tiles.addAll(((Map<?,?>)field(renderer,"gpuTiles")).values());
                int terrain=tiles.size();
                tiles.addAll(((Map<?,?>)field(field(renderer,"seamBatch"),"tiles")).values());
                tiles.addAll(((Map<?,?>)field(field(renderer,"realSeamBatch"),"tiles")).values());
                int seams=tiles.size()-terrain;
                tiles.addAll((Collection<?>)field(renderer,"retiredTiles"));
                long independent=0,mask=0,logical=0;int arenaTiles=0;
                for(Object tile:tiles){Object a=field(tile,"quadAllocation");if(a!=null)independent+=number(a,"capacity");
                    if(field(tile,"arenaSlice")!=null)arenaTiles++;
                    long axis=number(tile,"yieldAxis");mask+=axis*axis;
                    Object packed=field(tile,"packed");if(packed!=null){Method m=packed.getClass().getDeclaredMethod("uploadBytes");m.setAccessible(true);logical+=((Number)m.invoke(packed)).longValue();}
                }
                Object arena=field(classes.get("dev.xantha.vss.client.prediction.PredictionTerrainArena"),"SHARED");
                long pages=((List<?>)field(arena,"pages")).size()*8L*1024*1024;
                Object pool=field(classes.get("dev.xantha.vss.client.prediction.PredictionQuadBufferPool"),"SHARED");
                long spare=number(pool,"bytes");
                String result="time="+java.time.Instant.now()+"\nterrainTiles="+terrain+" seams="+seams+" totalIncludingRetired="+tiles.size()+" arenaTiles="+arenaTiles+
                    "\nlogicalPayloadBytes="+logical+" independentCapacity="+independent+" arenaPageBytes="+pages+" spareBytes="+spare+" maskR8Bytes="+mask+
                    "\nknownBufferAndMaskBytes="+(independent+pages+spare+mask)+"\n";
                new Thread(()->{try{Files.writeString(Path.of(output),result);}catch(Exception e){e.printStackTrace();}},"vss-gpu-audit-write").start();
            }catch(Throwable e){try{Files.writeString(Path.of(output),e.toString());}catch(Exception ignored){}}
        });
    }
}
