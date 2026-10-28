package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URLClassLoader;
import java.nio.file.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Executes the reviewed upstream coordinate method bodies, without replacing their algorithms. */
class VoxySourceContractTest {
    @TempDir Path directory;

    @Test void reviewedSourceCodecsAgreeAtLegacyAndExtendedHeightBoundaries() throws Exception {
        int count = 0;
        for (String path : Files.readAllLines(Path.of(System.getProperty("vss.voxySourceList")))) {
            if (path.isBlank()) continue;
            String source = Files.readString(Path.of(path)), name = "UpstreamVoxyCodec" + count++;
            var code = new StringBuilder("public class " + name + " {\n");
            for (String method : new String[]{"getWorldSectionId", "getLevel", "getX", "getY", "getZ"})
                code.append(extract(source, method)).append('\n');
            code.append("}\n");
            Path file = directory.resolve(name + ".java"); Files.writeString(file, code);
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-d", directory.toString(), file.toString()), path);
            try (var loader = new URLClassLoader(new java.net.URL[]{directory.toUri().toURL()})) {
                Class<?> engine = loader.loadClass(name);
                VoxySectionPosition.bind(engine);
                var encode = engine.getMethod("getWorldSectionId", int.class, int.class, int.class, int.class);
                for (int l = 0; l <= 4; l++) for (int y : new int[]{-2048, -300, -129, -128, -1, 0, 127, 128, 300, 2047}) {
                    long actual = (long) encode.invoke(null, l, -123, y, -789);
                    assertEquals(actual, VoxySectionPosition.key(l, -123, y, -789), path);
                    assertEquals(engine.getMethod("getY", long.class).invoke(null, actual), VoxySectionPosition.y(actual), path);
                    assertEquals(engine.getMethod("getX", long.class).invoke(null, actual), VoxySectionPosition.x(actual), path);
                    assertEquals(engine.getMethod("getZ", long.class).invoke(null, actual), VoxySectionPosition.z(actual), path);
                }
            }
        }
        assertTrue(count >= 2);
    }

    private static String extract(String source, String name) {
        var pattern = java.util.regex.Pattern.compile("public\\s+static\\s+(?:long|int)\\s+" + name + "\\s*\\(");
        var match = pattern.matcher(source);
        if (!match.find()) throw new IllegalArgumentException("Missing upstream method " + name);
        int start = source.indexOf('{', match.end()), depth = 1, end = start + 1;
        while (depth != 0) { char c = source.charAt(end++); if (c == '{') depth++; else if (c == '}') depth--; }
        return source.substring(match.start(), end);
    }
}
