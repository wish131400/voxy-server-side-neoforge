import java.nio.file.Files;
import java.nio.file.Path;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordingFile;

/** Compact server-thread samples. Run with the JDK source launcher after the opt-in profile. */
class JfrSamples {
    public static void main(String[] args) throws Exception {
        try (var input = new RecordingFile(Path.of(args[0]));
             var output = Files.newBufferedWriter(Path.of(args[1]))) {
            output.write("[\n");
            boolean first = true;
            while (input.hasMoreEvents()) {
                RecordedEvent event = input.readEvent();
                String type = event.getEventType().getName();
                boolean sample = type.equals("jdk.ExecutionSample") || type.equals("jdk.NativeMethodSample");
                boolean gc = type.equals("jdk.GarbageCollection") || type.equals("jdk.GCPhasePause");
                if (!sample && !gc) continue;
                if (sample && (event.getThread("sampledThread") == null
                        || !event.getThread("sampledThread").getJavaName().equals("Server thread"))) continue;
                if (!first) output.write(",\n");
                first = false;
                output.write("{\"type\":\"" + type + "\",\"start\":\"" + event.getStartTime() + "\"");
                if (gc) output.write(",\"durationNanos\":" + event.getDuration().toNanos());
                if (sample) {
                    output.write(",\"frames\":[");
                    boolean firstFrame = true;
                    if (event.getStackTrace() != null) for (RecordedFrame frame : event.getStackTrace().getFrames()) {
                        if (!firstFrame) output.write(",");
                        firstFrame = false;
                        output.write("\"" + frame.getMethod().getType().getName() + "."
                                + frame.getMethod().getName() + ":" + frame.getLineNumber() + "\"");
                    }
                    output.write("]");
                }
                output.write("}");
            }
            output.write("\n]\n");
        }
    }
}
