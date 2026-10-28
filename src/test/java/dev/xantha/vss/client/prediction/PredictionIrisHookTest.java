package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;

class PredictionIrisHookTest {
    @Test void commonObserverCoversNormalAndShaderPipelines() throws Exception {
        try(var jar=new JarFile(System.getProperty("vss.voxyJar"))) {
            var render=new ClassNode();
            new ClassReader(jar.getInputStream(jar.getJarEntry("me/cortex/voxy/client/core/VoxyRenderSystem.class"))).accept(render,0);
            assertTrue(render.methods.stream().anyMatch(m->m.name.equals("renderOpaque")
                    && m.desc.equals("(Lme/cortex/voxy/client/core/rendering/Viewport;)V")));
        }
        var mixin=new ClassNode();
        new ClassReader(getClass().getResourceAsStream("/dev/xantha/vss/mixin/voxy/StrictVoxyVisibilityMixin.class")).accept(mixin,0);
        var observer=mixin.methods.stream().filter(m->m.name.equals("vss$strictFrame")).findFirst().orElseThrow();
        assertTrue(java.util.stream.StreamSupport.stream(observer.instructions.spliterator(),false)
                .anyMatch(i->i instanceof MethodInsnNode call && call.name.equals("observeUnifiedView")));
    }
}
