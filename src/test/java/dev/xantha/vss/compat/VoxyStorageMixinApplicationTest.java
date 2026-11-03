package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.testutil.VoxyMixinHarness;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import java.nio.charset.StandardCharsets;

class VoxyStorageMixinApplicationTest {
    @Test void realSpongeAppliesTheStorageWrappersAndIdentityHook() throws Exception {
        String configuration;
        try (var input = getClass().getClassLoader().getResourceAsStream("vss.strict.mixins.json")) {
            configuration = new String(java.util.Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
        }
        var harness = new VoxyMixinHarness(System.getProperty("vss.voxyJar"), configuration);
        for (String target : new String[]{VoxyStorageBytecodeTest.STORAGE, VoxyStorageBytecodeTest.CLIENT}) {
            byte[] transformed = harness.apply(target.replace('/', '.'));
            ClassNode node = new ClassNode(); new ClassReader(transformed).accept(node, 0);
            assertTrue(node.methods.stream().anyMatch(m -> m.name.equals("vss$coveragePersistenceReady")), target);
            for (MethodNode method : node.methods) new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
        }
        // Plugin loading itself uses a bootstrap exception to the reserved Mixin
        // package rule. An ordinary helper must survive the normal transformer.
        ClassNode plugin = new ClassNode();
        try (var input = getClass().getClassLoader().getResourceAsStream(
                "dev/xantha/vss/mixin/voxy/StrictVoxyIngestPlugin.class")) {
            new ClassReader(input).accept(plugin, 0);
        }
        MethodNode post = plugin.methods.stream().filter(m -> m.name.equals("postApply")).findFirst().orElseThrow();
        String helper = java.util.stream.StreamSupport.stream(post.instructions.spliterator(), false)
                .filter(i -> i instanceof MethodInsnNode call && call.name.equals("instrument") && !call.owner.equals(plugin.name))
                .map(i -> ((MethodInsnNode) i).owner.replace('/', '.')).findFirst().orElseThrow();
        assertDoesNotThrow(() -> harness.apply(helper), "Runtime storage helper must load with the production Mixin config");
    }
}
