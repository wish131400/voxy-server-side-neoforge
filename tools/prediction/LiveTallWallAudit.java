import java.lang.instrument.Instrumentation;
import java.nio.file.*;
import java.util.*;

/** Bounded read-only published mesh audit; no GL calls or world changes. */
public class LiveTallWallAudit {
    static Object get(Object o,String n)throws Exception { return LiveMemoryAudit.get(o,n); }
    static Object call(Object o,String n)throws Exception { return LiveMemoryAudit.call(o,n); }
    public static void agentmain(String directory,Instrumentation inst) {
        Thread t=new Thread(()->{try {
            Path dir=Path.of(directory);Files.createDirectories(dir);
            Class<?> state=Arrays.stream(inst.getAllLoadedClasses()).filter(c->c.getName().equals(
                    "dev.xantha.vss.client.prediction.ClientPredictionState")).findFirst().orElseThrow();
            StringBuilder out=new StringBuilder("loaded="+state.getProtectionDomain().getCodeSource().getLocation()+"\n");
            Object registry=Class.forName("net.minecraft.core.registries.BuiltInRegistries",false,state.getClassLoader()).getField("BLOCK").get(null);
            var byId=registry.getClass().getMethod("byId",int.class);byId.setAccessible(true);
            long budget=64L*1024*1024;
            for(Object manager:((Map<?,?>)get(state,"MANAGERS")).values()) {
                List<Object> tiles;
                synchronized(manager) { tiles=new ArrayList<>(((Map<?,?>)get(manager,"ready")).values()); }
                int cx=((Number)get(manager,"cameraBlockX")).intValue(),cz=((Number)get(manager,"cameraBlockZ")).intValue();
                out.append("camera=").append(cx).append(',').append(cz).append('\n');
                tiles.sort(Comparator.comparingDouble(tile->{try {double dx=(int)call(tile,"baseBlockX")-cx,dz=(int)call(tile,"baseBlockZ")-cz;return dx*dx+dz*dz;}catch(Exception e){return Double.MAX_VALUE;}}));
                int selected=0;
                for(Object tile:tiles) {
                    int step=(int)call(tile,"spacingBlocks");if(step>4||selected++>=24)continue;
                    Object mesh=call(tile,"mesh"),payload=get(mesh,"gpuPayload");if(payload==null)continue;
                    long size=((Number)call(payload,"quadBytes")).longValue();if(size>budget)continue;budget-=size;
                    int[] words=(int[])call(payload,"quads");Object[] samples=(Object[])call(tile,"samples");
                    int axis=(int)call(tile,"cellAxis"),grid=axis+1,bx=(int)call(tile,"baseBlockX"),bz=(int)call(tile,"baseBlockZ");
                    out.append("TILE ").append(call(tile,"key")).append(" step=").append(step).append('\n');
                    int printed=0;
                    for(int q=0;q<words.length/12;q++) {
                        int at=q*12,attr=words[at+6],scale=(attr&(1<<20))!=0?16:4;
                        int lo=65535,hi=0;for(int k=4;k<6;k++){lo=Math.min(lo,Math.min(words[at+k]&65535,words[at+k]>>>16));hi=Math.max(hi,Math.max(words[at+k]&65535,words[at+k]>>>16));}
                        if(hi-lo<24*scale)continue;
                        int cell=words[at+8];if(cell<0||cell>=axis*axis)continue;
                        Object sample=samples[(cell/axis)*grid+cell%axis];
                        int rgb=words[at+7]&0xffffff;
                        if(printed++>=24)continue;
                        out.append("Q ").append(q).append(" world=").append(bx+cell%axis*step).append(',').append(bz+cell/axis*step)
                           .append(" y=").append((lo-32768)/(double)scale).append("..").append((hi-32768)/(double)scale)
                           .append(" rgb=").append(Integer.toHexString(rgb)).append(" attr=").append(Integer.toHexString(attr))
                           .append(" terrainWall=").append((words[at+9]&(1<<27))!=0).append(" ").append(sample).append('\n');
                        for(String field:List.of("topBlockIndex","underBlockIndex","deepBlockIndex")) out.append(field).append('=').append(byId.invoke(registry,call(sample,field))).append(' ');
                        out.append('\n');
                    }
                    out.append("tallQuadCount=").append(printed).append('\n');
                }
            }
            Files.writeString(dir.resolve("tall-walls.txt"),out);
        }catch(Throwable e){try{Files.writeString(Path.of(directory,"error.txt"),e+"\n"+Arrays.toString(e.getStackTrace()));}catch(Exception ignored){}}},"vss-readonly-tall-wall-audit");
        t.setDaemon(true);t.setPriority(3);t.start();
    }
}
