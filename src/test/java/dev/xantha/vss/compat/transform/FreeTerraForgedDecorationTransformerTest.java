package dev.xantha.vss.compat.transform;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

class FreeTerraForgedDecorationTransformerTest {
    @Test
    @EnabledIfSystemProperty(named="vss.freeTerraForgedJar", matches=".+")
    void releasedPlacementKeepsAlgorithmsAndGetsOnlyReadOnlyContextBridges() throws Exception {
        int classes = 0, changes = 0;
        try (var jar = new JarFile(Path.of(System.getProperty("vss.freeTerraForgedJar")).toFile())) {
            for (var entry : jar.stream().filter(e -> e.getName().endsWith(".class")).toList()) {
                var node = new ClassNode();
                try (var in = jar.getInputStream(entry)) { new ClassReader(in).accept(node, 0); }
                int replaced = FreeTerraForgedDecorationTransformer.instrument(node);
                if (replaced == 0) continue;
                classes++; changes += replaced;
                assertEquals(1, replaced, node.name);
                assertEquals(0, FreeTerraForgedDecorationTransformer.instrument(node), "adaptation is idempotent");
                for (var method : node.methods) {
                    boolean adapted = java.util.stream.StreamSupport.stream(method.instructions.spliterator(), false)
                            .anyMatch(i -> i instanceof MethodInsnNode c && c.owner.endsWith("/FreeTerraForgedDecorationContext"));
                    if (adapted) new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
                }
            }
        }
        assertTrue(classes >= 1, "released template/poisson classes must expose the verified context contract");
        assertEquals(classes, changes);
    }

    @Test void unknownClassesAndLookalikeCallsAreUntouched() {
        var node = new ClassNode();node.name = "example/mod/world/worldgen/feature/RiverGasketFeature";
        assertEquals(0, FreeTerraForgedDecorationTransformer.instrument(node));
        node.name = "etcodehome/freeterraforged/world/worldgen/feature/RiverGasketFeature";
        var method = new MethodNode(1,"place","(Ljava/lang/Object;)Z",null,null);
        node.methods.add(method);
        assertEquals(0, FreeTerraForgedDecorationTransformer.instrument(node));
    }
}
