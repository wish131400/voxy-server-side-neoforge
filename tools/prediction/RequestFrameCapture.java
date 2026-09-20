import java.lang.instrument.Instrumentation;
import java.nio.file.*;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

/** Requests the existing bounded, single-frame capture without changing game settings. */
public class RequestFrameCapture {
    public static void main(String[] args) throws Exception {
        var vm = com.sun.tools.attach.VirtualMachine.attach(args[0]);
        try { vm.loadAgent(args[1], args[2]); } finally { vm.detach(); }
    }
    public static void agentmain(String output, Instrumentation instrumentation) throws Exception {
        Path directory = Path.of(output); Files.createDirectories(directory);
        Class<?> capture = Arrays.stream(instrumentation.getAllLoadedClasses())
                .filter(c -> c.getName().equals("dev.xantha.vss.client.prediction.PredictionRenderCapture"))
                .findFirst().orElseThrow();
        Class<?> minecraft = capture.getClassLoader().loadClass("net.minecraft.client.Minecraft");
        Object client = minecraft.getMethod("getInstance").invoke(null);
        minecraft.getMethod("execute", Runnable.class).invoke(client, (Runnable) () -> {
            try {
                var future = (CompletableFuture<?>) capture.getMethod("request", double.class, double.class)
                        .invoke(null, 0.42, 0.58);
                future.whenComplete((path, failure) -> {
                    try { Files.writeString(directory.resolve("result.txt"), failure == null ? path.toString() : failure.toString()); }
                    catch (Exception ignored) { }
                });
            } catch (Throwable failure) {
                try { Files.writeString(directory.resolve("error.txt"), failure.toString()); }
                catch (Exception ignored) { }
            }
        });
    }
}
