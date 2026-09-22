import java.lang.instrument.Instrumentation;
import java.nio.file.*;
import java.util.*;

/** Bounded read-only snapshot of published terrain near the reported mountain. */
public class LiveSeamSnapshot {
    public static void agentmain(String directory, Instrumentation inst) {
        Thread worker = new Thread(() -> {
            try {
                Path dir = Path.of(directory); Files.createDirectories(dir);
                Class<?> state = Arrays.stream(inst.getAllLoadedClasses()).filter(c -> c.getName().equals(
                        "dev.xantha.vss.client.prediction.ClientPredictionState")).findFirst().orElseThrow();
                StringBuilder out = new StringBuilder("loaded=" + state.getProtectionDomain().getCodeSource().getLocation() + "\n");
                Object profile = LiveMemoryAudit.get(state, "acceptedProfile");
                for (Object d : (List<?>)LiveMemoryAudit.call(profile, "dimensions"))
                    out.append("dimension=").append(LiveMemoryAudit.call(d,"dimension")).append(" seed=")
                            .append(LiveMemoryAudit.call(d,"seed")).append('\n');
                for (Object manager : ((Map<?,?>)LiveMemoryAudit.get(state,"MANAGERS")).values()) {
                    List<?> ready;
                    synchronized(manager) { ready = List.copyOf(((Map<?,?>)LiveMemoryAudit.get(manager,"ready")).values()); }
                    out.append("camera=").append(LiveMemoryAudit.get(manager,"cameraBlockX")).append(',')
                            .append(LiveMemoryAudit.get(manager,"cameraBlockZ")).append('\n');
                    for (Object tile : ready) {
                        int x=(int)LiveMemoryAudit.call(tile,"baseBlockX"),z=(int)LiveMemoryAudit.call(tile,"baseBlockZ"),
                            span=(int)LiveMemoryAudit.call(tile,"spanBlocks"),step=(int)LiveMemoryAudit.call(tile,"spacingBlocks");
                        if (x > -4510 || x+span < -4670 || z > -1450 || z+span < -1610) continue;
                        Object[] samples=(Object[])LiveMemoryAudit.call(tile,"samples");
                        int grid=(int)Math.sqrt(samples.length);
                        out.append("TILE ").append(LiveMemoryAudit.call(tile,"key")).append(" step=").append(step)
                                .append(" axis=").append(grid).append('\n');
                        for(int dz=0;dz<grid;dz++)for(int dx=0;dx<grid;dx++) {
                            int wx=x+dx*step,wz=z+dz*step;
                            if(wx < -4670 || wx > -4510 || wz < -1610 || wz > -1450 || dx%Math.max(1,8/step)!=0 || dz%Math.max(1,8/step)!=0)continue;
                            out.append(wx).append(',').append(wz).append(' ').append(samples[dz*grid+dx]).append('\n');
                        }
                    }
                }
                Files.writeString(dir.resolve("live-seams.txt"),out);
            } catch(Throwable e) {try { Files.writeString(Path.of(directory,"error.txt"),e+"\n"+Arrays.toString(e.getStackTrace())); }catch(Exception ignored){} }
        },"vss-readonly-seam-snapshot"); worker.setDaemon(true);worker.setPriority(3);worker.start();
    }
}
