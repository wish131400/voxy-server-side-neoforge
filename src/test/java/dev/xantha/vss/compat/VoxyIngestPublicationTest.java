package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.mixin.voxy.StrictVoxyIngestPlugin;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

class VoxyIngestPublicationTest {
    private static final String FIXTURE = "dev/xantha/vss/compat/VoxyIngestPublicationFixture";
    private static final String TASK = "L" + FIXTURE + "$IngestSection;";
    private static final String TRACKER = "dev/xantha/vss/compat/VoxyIngestTracker";

    @BeforeEach void reset() { VoxyIngestTracker.reset(); }

    @Test void delegatedPublicationWithRawOverloadCompletesOnlyAfterActualWorker() throws Throwable {
        Probe probe = prepare(false);
        Object section = new Object();
        assertTrue(probe.offer(section));
        assertEquals(1, VoxyIngestTracker.pendingSection(section));
        assertEquals(1, VoxyIngestTracker.taskCount());
        probe.process();
        assertEquals(0, VoxyIngestTracker.pendingSection(section));
        assertEquals(0, VoxyIngestTracker.taskCount());
        assertFalse(VoxyIngestTracker.workerScopeActive());
        assertEquals(1, probe.type.getField("processed").getInt(probe.instance));
        probe.process(); // Empty-worker return cannot complete a different task.
        assertFalse(VoxyIngestTracker.workerScopeActive());
    }

    @Test void directPublicationWithForwardingRawOverloadRemainsSupported() throws Throwable {
        Probe probe = prepare(true);
        Object section = new Object();
        assertTrue(probe.offer(section));
        assertEquals(1, VoxyIngestTracker.pendingSection(section));
        probe.process();
        assertEquals(0, VoxyIngestTracker.pendingSection(section));
        assertEquals(0, VoxyIngestTracker.taskCount());
    }

    @Test void nativeSubmitOutsideRawScopeAndExceptionalWorkerPreserveOwnership() throws Throwable {
        Probe probe = prepare(false);
        Object nativeSection = new Object();
        assertTrue(probe.nativeOffer(nativeSection));
        assertEquals(0, VoxyIngestTracker.pendingSection(nativeSection));
        probe.process();
        RuntimeException failure = new RuntimeException("native worker failure");
        assertTrue(probe.offer(failure));
        assertSame(failure, assertThrows(RuntimeException.class, probe::process));
        assertEquals(0, VoxyIngestTracker.pendingSection(failure));
        assertEquals(0, VoxyIngestTracker.taskCount());
        assertFalse(VoxyIngestTracker.workerScopeActive());
    }

    @Test void unknownOrAmbiguousPublicationsCannotAdvertiseCompletion() throws Exception {
        ClassNode missingWorker = node();
        missingWorker.methods.removeIf(m -> m.name.equals("processJob"));
        assertRejected(missingWorker);
        ClassNode staticSubmit = node();
        method(staticSubmit, "submit", "(" + TASK + ")Z").access |= Opcodes.ACC_STATIC;
        assertRejected(staticSubmit);
        ClassNode repeated = node();
        MethodNode publish = method(repeated, "submit", "(" + TASK + ")Z");
        var call = new InsnList();
        call.add(new VarInsnNode(Opcodes.ALOAD, 0));
        call.add(new FieldInsnNode(Opcodes.GETFIELD, FIXTURE, "ingestQueue", "Ljava/util/concurrent/ConcurrentLinkedDeque;"));
        call.add(new VarInsnNode(Opcodes.ALOAD, 1));
        call.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/concurrent/ConcurrentLinkedDeque", "add", "(Ljava/lang/Object;)Z", false));
        call.add(new InsnNode(Opcodes.POP));
        publish.instructions.insert(call);
        assertRejected(repeated);
        ClassNode ambiguous = node();
        MethodNode another = method(ambiguous, "rawIngest0", "(Ljava/lang/Object;" + TASK + ")Z");
        another.instructions.clear();
        another.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        another.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        another.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, FIXTURE, "submit", "(" + TASK + ")Z", false));
        another.instructions.add(new InsnNode(Opcodes.IRETURN));
        assertRejected(ambiguous);
    }

    private static void assertRejected(ClassNode node) {
        int methods = node.methods.size();
        assertFalse(StrictVoxyIngestPlugin.instrument(node));
        assertEquals(methods, node.methods.size());
        assertFalse(node.methods.stream().anyMatch(m -> m.name.equals("vss$ingestTrackingReady")));
    }

    private static Probe prepare(boolean direct) throws Exception {
        ClassNode node = node();
        if (direct) {
            MethodNode raw = method(node, "rawIngest0", "(" + TASK + ")Z");
            raw.instructions.clear();
            raw.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            raw.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, FIXTURE, "ingestQueue", "Ljava/util/concurrent/ConcurrentLinkedDeque;"));
            raw.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            raw.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/concurrent/ConcurrentLinkedDeque", "add", "(Ljava/lang/Object;)Z", false));
            raw.instructions.add(new InsnNode(Opcodes.IRETURN));
        }
        Map<String, List<String>> before = nativeCalls(node);
        assertTrue(StrictVoxyIngestPlugin.instrument(node));
        assertEquals(before, nativeCalls(node), "Queue operations, scheduling and native task access stay ordered");
        for (MethodNode m : node.methods) new Analyzer<>(new BasicVerifier()).analyze(node.name, m);
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        byte[] transformed = writer.toByteArray();
        ClassLoader loader = new ClassLoader(VoxyIngestPublicationTest.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.startsWith(FIXTURE.replace('/', '.'))) return super.loadClass(name, resolve);
                synchronized (getClassLoadingLock(name)) {
                    Class<?> found = findLoadedClass(name);
                    if (found == null) try {
                        byte[] data = name.equals(FIXTURE.replace('/', '.')) ? transformed
                                : Objects.requireNonNull(getParent().getResourceAsStream(name.replace('.', '/') + ".class")).readAllBytes();
                        found = defineClass(name, data, 0, data.length);
                    } catch (java.io.IOException failure) { throw new ClassNotFoundException(name, failure); }
                    if (resolve) resolveClass(found);
                    return found;
                }
            }
        };
        return new Probe(loader.loadClass(FIXTURE.replace('/', '.')));
    }

    private static ClassNode node() throws Exception {
        ClassNode node = new ClassNode();
        new ClassReader(Objects.requireNonNull(VoxyIngestPublicationTest.class.getClassLoader()
                .getResourceAsStream(FIXTURE + ".class"))).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String name, String desc) {
        return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst().orElseThrow();
    }

    private static Map<String, List<String>> nativeCalls(ClassNode node) {
        Map<String, List<String>> result = new TreeMap<>();
        for (MethodNode m : node.methods) {
            if (m.name.equals("vss$ingestTrackingReady") || m.name.equals("processJob")
                    && node.methods.stream().anyMatch(v -> v.name.equals("vss$processTrackedIngestBody"))) continue;
            String name = m.name.equals("vss$processTrackedIngestBody") ? "processJob" : m.name;
            List<String> calls = new ArrayList<>();
            for (var instruction : m.instructions) {
                if (instruction instanceof MethodInsnNode call && !call.owner.equals(TRACKER))
                    calls.add(call.getOpcode() + ":" + call.owner + "." + call.name + call.desc);
                if (instruction instanceof FieldInsnNode field)
                    calls.add(field.getOpcode() + ":" + field.owner + "." + field.name + field.desc);
            }
            result.put(name + m.desc, calls);
        }
        return result;
    }

    private static final class Probe {
        final Class<?> type, task;
        final Object instance;
        Probe(Class<?> type) throws Exception {
            this.type = type;
            instance = type.getConstructor().newInstance();
            task = type.getClassLoader().loadClass(FIXTURE.replace('/', '.') + "$IngestSection");
        }
        boolean offer(Object section) throws Throwable {
            Object value = task.getConstructor(Object.class).newInstance(section);
            return VoxyIngestTracker.runRawIngest(section, 4, 7, () -> invoke("rawIngest0", value));
        }
        boolean nativeOffer(Object section) throws Throwable {
            return invoke("submit", task.getConstructor(Object.class).newInstance(section));
        }
        boolean invoke(String name, Object value) throws Throwable {
            try { return (boolean) type.getMethod(name, task).invoke(instance, value); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
        }
        void process() throws Throwable {
            try { type.getMethod("processJob").invoke(instance); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
        }
    }
}
