import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Read-only runtime snapshot for an explicitly investigated prediction session. */
public class PredictionSnapshotAgent {
    public static void main(String[] args) throws Exception {
        var vm = com.sun.tools.attach.VirtualMachine.attach(args[0]);
        try { vm.loadAgent(args[1], args[2]); } finally { vm.detach(); }
    }
    public static void agentmain(String directory, Instrumentation instrumentation) throws Exception {
        Path output=Path.of(directory); Files.createDirectories(output);
        try {
            Class<?> state=Arrays.stream(instrumentation.getAllLoadedClasses()).filter(c->c.getName().equals(
                    "dev.xantha.vss.client.prediction.ClientPredictionState")).findFirst().orElseThrow();
            Object profile=field(state,null,"acceptedProfile");
            var compression=state.getClassLoader().loadClass("dev.xantha.vss.common.processing.LodByteCompression")
                    .getMethod("decompress",byte[].class,int.class,int.class,int.class);
            Files.write(output.resolve("registries.json"),(byte[])compression.invoke(null,call(profile,"registries"),call(profile,"registriesCompression"),call(profile,"registriesRawSize"),33554432));
            for(Object dimension:(List<?>)call(profile,"dimensions")) {
                String id=call(dimension,"dimension").toString().replace(':','_');
                Files.write(output.resolve(id+".json"),(byte[])compression.invoke(null,call(dimension,"generatorData"),call(dimension,"generatorCompression"),call(dimension,"generatorRawSize"),8388608));
                Files.writeString(output.resolve(id+"-seed.txt"),call(dimension,"seed").toString());
            }
            StringBuilder summary=new StringBuilder();
            for(Object manager:((Map<?,?>)field(state,null,"MANAGERS")).values()) {
                summary.append(call(manager,"surfaceDiagnostics")).append('\n');
                var executor=(java.util.concurrent.ThreadPoolExecutor)field(manager.getClass(),manager,"executor");
                summary.append("queue=").append(executor.getQueue().size()).append(" completed=").append(executor.getCompletedTaskCount()).append('\n');
            }
            Files.writeString(output.resolve("summary.txt"),summary);
        } catch(Throwable failure) {
            Files.writeString(output.resolve("error.txt"),failure.toString()); throw failure;
        }
    }
    private static Object field(Class<?> type,Object target,String name)throws Exception {
        Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(target);
    }
    private static Object call(Object target,String name)throws Exception {
        Method m=target.getClass().getMethod(name);m.setAccessible(true);return m.invoke(target);
    }
}
