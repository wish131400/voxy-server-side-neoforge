package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.testutil.VoxyMixinHarness;
import java.nio.file.Path;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

class PredictionStructureTerrainMixinTest {
    @TempDir Path directory;
    @Test void actualSpongeInjectionMatchesThisMinecraftGeneratorAndBothReturnTypes() throws Exception {
        Path empty = directory.resolve("classpath.zip");
        try (var zip = new ZipOutputStream(java.nio.file.Files.newOutputStream(empty))) { }
        var harness = new VoxyMixinHarness(empty.toString(), """
            {"required":true,"minVersion":"0.8","package":"dev.xantha.vss.mixin",
             "compatibilityLevel":"JAVA_17","client":["client.PredictionStructureTerrainMixin"],
             "injectors":{"defaultRequire":1}}
            """);
        ClassNode transformed = new ClassNode();
        new ClassReader(harness.apply("net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator")).accept(transformed, 0);
        for (String name : new String[]{"getBaseHeight", "getBaseColumn"}) {
            var target = transformed.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
            int callbacks = 0;
            for (var instruction : target.instructions) if (instruction instanceof MethodInsnNode call && call.name.contains("vss$")) callbacks++;
            assertTrue(callbacks >= 2, "head and return hooks must both apply to " + name);
            new Analyzer<>(new BasicVerifier()).analyze(transformed.name, target);
        }
    }
}
