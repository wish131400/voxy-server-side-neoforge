import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Read-only export of the accepted prediction profile for offline profiling.
 * Does not transform classes, replace samplers, or read/write live chunks. */
public class LivePredictionSnapshot {
    static Object call(Object object, String name) throws Exception {
        Method method = object.getClass().getMethod(name);
        method.setAccessible(true);
        return method.invoke(object);
    }
    static Object field(Object object, String name) throws Exception {
        Class<?> type = object instanceof Class<?> c ? c : object.getClass();
        Field field = type.getDeclaredField(name); field.setAccessible(true);
        return field.get(object instanceof Class<?> ? null : object);
    }
    public static void agentmain(String output, Instrumentation instrumentation) throws Exception {
        Path directory = Path.of(output); Files.createDirectories(directory);
        try {
            Class<?> state = Arrays.stream(instrumentation.getAllLoadedClasses())
                    .filter(c -> c.getName().equals("dev.xantha.vss.client.prediction.ClientPredictionState"))
                    .findFirst().orElseThrow();
            ClassLoader loader = state.getClassLoader();
            Object payload = field(state, "acceptedProfile");
            Class<?> compression = loader.loadClass("dev.xantha.vss.common.processing.LodByteCompression");
            Method decompress = compression.getMethod("decompress", byte[].class, int.class, int.class, int.class);
            byte[] registry = (byte[]) decompress.invoke(null, call(payload,"registries"),
                    call(payload,"registriesCompression"), call(payload,"registriesRawSize"), 32 * 1024 * 1024);
            Class<?> decoder = loader.loadClass("dev.xantha.vss.client.prediction.ClientWorldgenProfileDecoder");
            Method parse = decoder.getDeclaredMethod("parse", byte[].class); parse.setAccessible(true);
            Object registries = parse.invoke(null, (Object) registry);
            Map<?,?> managers = (Map<?,?>) field(state,"MANAGERS");
            for (Object profile : (List<?>) call(payload,"dimensions")) {
                if (!call(profile,"dimension").toString().equals("minecraft:overworld")) continue;
                Object manager = managers.get(call(profile,"levelKey"));
                Object sampler = field(manager,"sampler");
                Method contextMethod = sampler.getClass().getDeclaredMethod("decorationContext");
                contextMethod.setAccessible(true); Object context = contextMethod.invoke(sampler);
                byte[] generator = (byte[]) decompress.invoke(null, call(profile,"generatorData"),
                        call(profile,"generatorCompression"), call(profile,"generatorRawSize"), 8 * 1024 * 1024);
                Object generatorJson = parse.invoke(null, (Object) generator);
                Class<?> document = loader.loadClass("dev.xantha.vss.client.prediction.RustWorldgenDocument");
                Method create = Arrays.stream(document.getDeclaredMethods()).filter(m -> m.getName().equals("create")).findFirst().orElseThrow();
                create.setAccessible(true);
                Object json = create.invoke(null, generatorJson, registries, context);
                Files.writeString(directory.resolve("worldgen.json"), json.toString());
                Files.writeString(directory.resolve("world.txt"), "seed="+call(profile,"seed")
                        +"\nx="+field(manager,"cameraBlockX")+"\nz="+field(manager,"cameraBlockZ")+"\n");
            }
            Files.writeString(directory.resolve("snapshot-status.txt"), "Snapshot export complete; no game state modified.\n");
        } catch (Throwable error) {
            Files.writeString(directory.resolve("snapshot-error.txt"), error.toString()+"\n"+Arrays.toString(error.getStackTrace()));
            throw error;
        }
    }
}
