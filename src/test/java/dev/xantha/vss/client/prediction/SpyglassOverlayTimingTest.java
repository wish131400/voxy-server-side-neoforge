package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class SpyglassOverlayTimingTest {
    @Test void mixinTargetsTheActualScopeInterpolationInGameBytecode() throws Exception {
        verifyTarget("dev/xantha/vss/mixin/client/SpyglassOverlayTimingMixin");
    }

    private static void verifyTarget(String mixinName) throws Exception {
        var mixin = readClass(mixinName);
        var type = annotation(mixin.invisibleAnnotations, "Lorg/spongepowered/asm/mixin/Mixin;");
        var targetType = (org.objectweb.asm.Type) ((java.util.List<?>) value(type, "value")).get(0);
        var target = readClass(targetType.getInternalName());
        var handler = mixin.methods.stream().filter(m -> m.name.equals("vss$boundScopeAnimation")).findFirst().orElseThrow();
        var modify = annotation(handler.visibleAnnotations, "Lorg/spongepowered/asm/mixin/injection/ModifyArg;");
        String selector = (String) ((java.util.List<?>) value(modify, "method")).get(0);
        String name = selector.split("\\(", 2)[0];
        String descriptor = selector.contains("(") ? selector.substring(selector.indexOf('(')) : null;
        var method = target.methods.stream().filter(m -> m.name.equals(name)
                && (descriptor == null || m.desc.equals(descriptor))).findFirst().orElseThrow();
        var at = (org.objectweb.asm.tree.AnnotationNode) value(modify, "at");
        String call = (String) value(at, "target");
        assertEquals(0, value(modify, "index"));
        assertEquals(0, value(at, "ordinal"));
        for (var instruction : method.instructions) {
            if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode invoke
                    && call.equals("L" + invoke.owner + ";" + invoke.name + invoke.desc)) {
                var next = instruction.getNext();
                while (next != null && next.getOpcode() < 0) next = next.getNext();
                assertTrue(next instanceof org.objectweb.asm.tree.FieldInsnNode field
                        && field.getOpcode() == org.objectweb.asm.Opcodes.PUTFIELD && field.name.equals("scopeScale"),
                        "the selected lerp must update only the spyglass aperture");
                return;
            }
        }
        fail("scope interpolation injection target missing: " + selector);
    }

    private static org.objectweb.asm.tree.ClassNode readClass(String name) throws Exception {
        try (var input = SpyglassOverlayTimingTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(input, name);
            var node = new org.objectweb.asm.tree.ClassNode();
            new org.objectweb.asm.ClassReader(input).accept(node, 0);
            return node;
        }
    }

    private static org.objectweb.asm.tree.AnnotationNode annotation(
            java.util.List<org.objectweb.asm.tree.AnnotationNode> annotations, String descriptor) {
        assertNotNull(annotations);
        return annotations.stream().filter(a -> a.desc.equals(descriptor)).findFirst().orElseThrow();
    }

    private static Object value(org.objectweb.asm.tree.AnnotationNode annotation, String key) {
        for (int i = 0; i < annotation.values.size(); i += 2)
            if (key.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        throw new AssertionError("Missing annotation member " + key);
    }

    @Test void longFramesCannotTurnTheScopeApertureNegative() {
        float original = .5F, fixed = .5F;
        boolean originalNegative = false;
        // Same interpolation as Gui: 0.5 * elapsed game ticks.
        for (float ms : new float[]{16, 468, 523, 468, 523, 150, 16, 16}) {
            float factor = .5F * ms / 50;
            original += factor * (1.125F - original);
            originalNegative |= original < 0;
            fixed += SpyglassOverlayTiming.interpolation(factor) * (1.125F - fixed);
            assertTrue(fixed >= .5F && fixed <= 1.125F, "scope aperture must remain visible");
        }
        assertTrue(originalNegative, "fixture must reproduce the full-screen black scope border");
    }

    @Test void ordinaryFramesRetainTheirExactAnimationFactor() {
        for (float factor : new float[]{0, .01F, .083333F, .5F, .99F, 1})
            assertEquals(factor, SpyglassOverlayTiming.interpolation(factor));
        for (float factor : new float[]{Float.NaN, Float.POSITIVE_INFINITY, 5.23F})
            assertEquals(1, SpyglassOverlayTiming.interpolation(factor));
    }
}
