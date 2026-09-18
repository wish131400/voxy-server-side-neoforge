import java.lang.instrument.Instrumentation;
import java.nio.file.*;
import java.util.*;

/** Bounded read-only capture of scope ray and focus state. */
public class ScopeRaySnapshot {
    public static void agentmain(String output, Instrumentation inst) throws Exception {
        Class<?> state = Arrays.stream(inst.getAllLoadedClasses()).filter(c -> c.getName()
                .equals("dev.xantha.vss.client.prediction.ClientPredictionState")).findFirst().orElseThrow();
        Class<?> renderer = Arrays.stream(inst.getAllLoadedClasses()).filter(c -> c.getName()
                .equals("dev.xantha.vss.client.prediction.PredictionRenderer")).findFirst().orElseThrow();
        StringBuilder out = new StringBuilder();
        for (String name : List.of("viewRay", "selectionScoping", "selectionScale"))
            out.append(name).append('=').append(LiveRefinementSnapshot.field(renderer, name)).append('\n');
        for (String name : List.of("viewFocus", "lastFocus", "viewFocusUpdating"))
            out.append(name).append('=').append(LiveRefinementSnapshot.field(state, name)).append('\n');
        Files.writeString(Path.of(output), out);
    }
}
