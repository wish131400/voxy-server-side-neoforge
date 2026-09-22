package dev.xantha.vss.client.prediction;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL31;

/** Rejected performance candidate: kept outside shipped shaders for reproducibility. */
final class PredictionInstanceExperiment {
    static boolean enabled;
    private static int countLocation, rangesLocation;
    private static PredictionDrawRanges bound;
    private static int[] boundPairs;

    static PredictionTerrainProgram create() {
        return compile(source("TERRAIN_VERTEX"), source("TERRAIN_FRAGMENT"));
    }

    static PredictionTerrainProgram createIris(String taa, java.util.function.UnaryOperator<String> patch) {
        return compile(PredictionTerrainProgram.irisVertex(taa), patch.apply(PredictionTerrainProgram.irisFragment()));
    }

    private static String source(String name) {
        try {
            var field = PredictionTerrainProgram.class.getDeclaredField(name); field.setAccessible(true);
            return (String) field.get(null);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static PredictionTerrainProgram compile(String vertex, String fragment) {
        if (enabled) vertex = vertex.replace("uniform usamplerBuffer QuadPayload;", """
                uniform usamplerBuffer QuadPayload;
                uniform int InstanceRangeCount;
                uniform ivec2 InstanceRanges[5];
                """).replace("int quad = gl_VertexID >> 2;", """
                int quad = gl_VertexID >> 2;
                for (int r = 0; r < InstanceRangeCount; r++) {
                    if (gl_InstanceID < InstanceRanges[r].x) {
                        quad = gl_InstanceID + InstanceRanges[r].y;
                        break;
                    }
                }
                """);
        try {
            var constructor = PredictionTerrainProgram.class.getDeclaredConstructor(String.class, String.class);
            constructor.setAccessible(true);
            return constructor.newInstance(vertex, fragment);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    static int[] pairs(PredictionDrawRanges ranges) {
        int[] result = new int[ranges.first.length * 2];
        int end = 0;
        for (int i = 0; i < ranges.first.length; i++) {
            result[i * 2 + 1] = ranges.first[i] - end;
            end += ranges.count[i]; result[i * 2] = end;
        }
        return result;
    }

    static void bind() {
        int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        countLocation = GL20.glGetUniformLocation(program, "InstanceRangeCount");
        rangesLocation = GL20.glGetUniformLocation(program, "InstanceRanges[0]");
        indexed();
    }

    static void indexed() { GL20.glUniform1i(countLocation, 0); bound = null; boundPairs = null; }

    static void submit(PredictionDrawRanges ranges) {
        if (ranges.quads == 0) return;
        if (bound != ranges) {
            int[] data = pairs(ranges);
            if (bound == null || bound.first.length != ranges.first.length)
                GL20.glUniform1i(countLocation, ranges.first.length);
            if (!java.util.Arrays.equals(boundPairs, data)) GL20.glUniform2iv(rangesLocation, data);
            bound = ranges; boundPairs = data;
        }
        GL31.glDrawElementsInstanced(GL11.GL_TRIANGLES, 6, GL11.GL_UNSIGNED_INT, 0L, ranges.quads);
    }
}
