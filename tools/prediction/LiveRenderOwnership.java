import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Read-only render ownership snapshot; never changes game state. */
public final class LiveRenderOwnership {
    public static void agentmain(String output, Instrumentation instrumentation) {
        try {
            Path path = Path.of(output);
            Files.createDirectories(path.getParent());
            ClassLoader loader = Arrays.stream(instrumentation.getAllLoadedClasses())
                    .filter(type -> type.getName().equals("dev.xantha.vss.client.prediction.ClientPredictionState"))
                    .findFirst().orElseThrow().getClassLoader();
            StringBuilder report = new StringBuilder();
            for (String name : new String[]{
                    "dev.xantha.vss.client.prediction.PredictionRenderer",
                    "dev.xantha.vss.compat.StrictLodVisibility",
                    "dev.xantha.vss.compat.ModCompat",
                    "dev.xantha.vss.networking.client.VSSClientNetworking"}) {
                Class<?> type = Class.forName(name, false, loader);
                report.append(name).append('\n');
                for (String methodName : new String[]{"diagnostics", "voxyLocalIndexDiagnostics", "getVoxyViewDistanceChunks", "isPredictionActive", "getEffectiveLodDistanceChunks"}) {
                    try {
                        Method method = type.getDeclaredMethod(methodName);
                        method.setAccessible(true);
                        report.append(methodName).append('=').append(method.invoke(null)).append('\n');
                    } catch (NoSuchMethodException ignored) {
                    } catch (Throwable failure) {
                        report.append(methodName).append("=<").append(failure.getClass().getSimpleName()).append(">\n");
                    }
                }
                for (String fieldName : new String[]{"uploadedNodes", "failed", "renderHookSeen", "dimension", "nodeOwner", "revision", "renderedRevision", "snapshot"}) {
                    try {
                        Field field = type.getDeclaredField(fieldName);
                        field.setAccessible(true);
                        report.append(fieldName).append('=').append(field.get(null)).append('\n');
                    } catch (NoSuchFieldException ignored) {
                    } catch (Throwable failure) {
                        report.append(fieldName).append("=<").append(failure.getClass().getSimpleName()).append(">\n");
                    }
                }
                report.append('\n');
            }
            Files.writeString(path, report);
        } catch (Throwable failure) {
            try { Files.writeString(Path.of(output), failure.toString()); } catch (Exception ignored) { }
        }
    }
}
