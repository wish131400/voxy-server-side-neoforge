import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Read-only bounded accounting of published prediction meshes; never walks the JVM heap or forces GC. */
public class LiveMeshMemory {
    static Object get(Object target, String name) throws Exception {
        Class<?> type = target instanceof Class<?> c ? c : target.getClass();
        Field field = type.getDeclaredField(name); field.setAccessible(true);
        return field.get(target instanceof Class<?> ? null : target);
    }
    static Object call(Object target, String name) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name); method.setAccessible(true);
        return method.invoke(target);
    }
    public static void agentmain(String directory, Instrumentation instrumentation) throws Exception {
        Path out = Path.of(directory); Files.createDirectories(out);
        new Thread(() -> {
            try {
                Class<?> state = Arrays.stream(instrumentation.getAllLoadedClasses()).filter(c -> c.getName()
                        .equals("dev.xantha.vss.client.prediction.ClientPredictionState")).findFirst().orElseThrow();
                StringBuilder result = new StringBuilder("loadedFrom=" + state.getProtectionDomain().getCodeSource().getLocation() + "\n");
                List<?> managers = List.copyOf(((Map<?,?>) get(state,"MANAGERS")).values());
                Map<String,Long> sizes = new TreeMap<>(); long quads = 0, tiles = 0;
                Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
                for (Object manager : managers) {
                    List<?> ready;
                    synchronized (manager) { ready = List.copyOf(((Map<?,?>)get(manager,"ready")).values()); }
                    for (Object tile : ready) {
                        tiles++;
                        Object mesh = call(tile,"mesh"), packed = get(mesh,"packed"), payload = get(mesh,"gpuPayload");
                        Object seams = null;
                        try { seams = get(mesh,"seamMesh"); } catch (NoSuchFieldException legacy) { }
                        for (Object object : new Object[]{tile,mesh,packed,payload,seams}) {
                            if (object == null) continue;
                            for (Field field : object.getClass().getDeclaredFields()) {
                                if (!field.getType().isArray() || Modifier.isStatic(field.getModifiers())) continue;
                                field.setAccessible(true); Object array = field.get(object);
                                if (array != null && seen.add(array)) sizes.merge(object.getClass().getSimpleName()+"."+field.getName(),
                                        instrumentation.getObjectSize(array),Long::sum);
                            }
                        }
                        quads += payload != null ? ((Number)call(payload,"quadCount")).longValue()
                                : ((Number)call(packed,"quadCount")).longValue()+((Number)call(packed,"waterQuadCount")).longValue();
                    }
                }
                result.append("tiles=").append(tiles).append(", quads=").append(quads).append('\n');
                sizes.forEach((k,v) -> result.append(k).append(" bytes=").append(v).append('\n'));
                result.append("These are unique array shallow sizes, not total retained heap; sample objects and native/GPU memory excluded.\n");
                Files.writeString(out.resolve("mesh-memory.txt"),result);
            } catch (Throwable failure) {
                try { Files.writeString(out.resolve("mesh-memory-error.txt"),failure.toString()); } catch (Exception ignored) { }
            }
        }, "vss-readonly-mesh-accounting").start();
    }
}
