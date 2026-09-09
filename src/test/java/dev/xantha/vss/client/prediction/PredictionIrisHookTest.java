package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;

class PredictionIrisHookTest {
    @Test
    @EnabledIfSystemProperty(named = "vss.voxyJar", matches = ".+")
    void opaqueHookMatchesInstalledPipelineBeforeTranslucentDepthCopy() throws Exception {
        ClassNode pipeline = new ClassNode();
        try (var jar = new JarFile(System.getProperty("vss.voxyJar"));
             var input = jar.getInputStream(jar.getJarEntry("me/cortex/voxy/client/core/IrisVoxyRenderPipeline.class"))) {
            new ClassReader(input).accept(pipeline, 0);
        }
        ClassNode mixin = new ClassNode();
        try (var input = getClass().getResourceAsStream("/dev/xantha/vss/mixin/voxy/IrisVoxyPredictionMixin.class")) {
            new ClassReader(input).accept(mixin, 0);
        }
        var phase = pipeline.methods.stream().filter(method -> method.name.equals("postOpaquePreTranslucent"))
                .findFirst().orElseThrow();
        int matchingHooks = 0;
        for (var method : mixin.methods) {
            if (method.visibleAnnotations == null) continue;
            for (var annotation : method.visibleAnnotations) {
                if (!annotation.desc.endsWith("/Inject;")) continue;
                var targets = (java.util.List<?>) value(annotation, "method");
                if (targets == null || !targets.contains(phase.name + phase.desc)) continue;
                matchingHooks++;
                if (phase.desc.contains(";I)")) {
                    var at = (AnnotationNode) ((java.util.List<?>) value(annotation, "at")).getFirst();
                    assertEquals("INVOKE", value(at, "value"));
                    String target = (String) value(at, "target");
                    int copies = 0;
                    boolean repaired = false;
                    for (var instruction : phase.instructions) {
                        if (instruction instanceof MethodInsnNode call) {
                            if (call.name.equals("blit")) repaired = true;
                            if (target.equals("L" + call.owner + ";" + call.name + call.desc)) {
                                assertTrue(repaired, "prediction must run after shader depth repair");
                                copies++;
                            }
                        }
                    }
                    assertEquals(1, copies, "new hook must run exactly once before the depth copy");
                }
            }
        }
        assertEquals(1, matchingHooks, "the old optional injection silently missed Voxy 0.2.15");
    }

    private static Object value(AnnotationNode annotation, String key) {
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        }
        return null;
    }
}
