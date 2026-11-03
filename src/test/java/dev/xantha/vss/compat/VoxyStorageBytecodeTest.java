package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.compat.transform.VoxyStorageTransformer;
import java.nio.file.*;
import java.nio.ByteBuffer;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

class VoxyStorageBytecodeTest {
    @TempDir Path directory;
    static final String STORAGE = "me/cortex/voxy/common/config/section/SectionSerializationStorage";
    static final String CLIENT = "me/cortex/voxy/client/VoxyClientInstance";
    static ClassNode read(ZipFile zip, String name) throws Exception {
        ClassNode node = new ClassNode();
        try (var input = zip.getInputStream(Objects.requireNonNull(zip.getEntry(name + ".class"), name))) {
            new ClassReader(input).accept(node, 0);
        }
        return node;
    }
    static List<String> nativeCalls(ClassNode node) {
        List<String> result = new ArrayList<>();
        for (MethodNode method : node.methods) for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && !call.owner.startsWith("dev/xantha/vss/")
                    && !call.name.startsWith("vss$")) result.add(call.getOpcode()+":"+call.owner+":"+call.name+call.desc);
        }
        return result;
    }

    @Test void actualMaintainedStorageAndIdentityMethodsKeepAllNativeCallsAndVerify() throws Exception {
        String jars = Objects.requireNonNull(System.getProperty("vss.coverageVoxyJars"), "Pass the actual Voxy matrix");
        int checked = 0;
        for (String path : jars.split("[|]")) {
            assertTrue(Files.exists(Path.of(path)), path);
            try (ZipFile zip = new ZipFile(path)) {
                for (String target : List.of(STORAGE, CLIENT)) {
                    ClassNode node = read(zip, target);
                    List<String> before = nativeCalls(node);
                    assertTrue(VoxyStorageTransformer.instrument(node), path + ":" + target);
                    assertEquals(before, nativeCalls(node), path + ":" + target);
                    for (MethodNode method : node.methods) new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
                    assertTrue(VoxyStorageTransformer.instrument(node)); // idempotent
                    assertEquals(1, node.methods.stream().filter(m -> m.name.equals("vss$coveragePersistenceReady")).count());
                    ClassWriter writer = new ClassWriter(0); node.accept(writer);
                    assertTrue(writer.toByteArray().length > 0);
                    if (target.equals(CLIENT)) {
                        var identity = read(zip, "me/cortex/voxy/commonImpl/WorldIdentifier");
                        for (String field : List.of("key", "dimension", "biomeSeed"))
                            assertTrue(identity.fields.stream().anyMatch(f -> f.name.equals(field)), path + ":" + field);
                        assertTrue(node.fields.stream().anyMatch(f -> f.name.equals("basePath"))
                                || node.methods.stream().anyMatch(m -> m.name.equals("getStorageBasePath")), path + ":base path");
                        ClassNode section = read(zip, "me/cortex/voxy/common/world/WorldSection");
                        for (String field : List.of("key", "tracker"))
                            assertTrue(section.fields.stream().anyMatch(f -> f.name.equals(field)), path + ":section." + field);
                        ClassNode tracker = read(zip, "me/cortex/voxy/common/world/ActiveSectionTracker");
                        assertTrue(tracker.fields.stream().anyMatch(f -> f.name.equals("engine")), path + ":tracker.engine");
                    }
                    checked++;
                }
            }
        }
        assertTrue(checked >= 2);
        System.out.println("Verified native storage and identity bytecode targets: " + checked);
    }

    @Test void unknownStorageLayoutsReceiveNoPartialMutation() throws Exception {
        String path = System.getProperty("vss.coverageVoxyJars").split("[|]")[0];
        try (ZipFile zip = new ZipFile(path)) {
            ClassNode node = read(zip, STORAGE);
            node.methods.removeIf(m -> m.name.equals("loadSection"));
            List<String> before = nativeCalls(node);
            assertFalse(VoxyStorageTransformer.instrument(node));
            assertEquals(before, nativeCalls(node));
            assertFalse(node.methods.stream().anyMatch(m -> m.name.startsWith("vss$")));
        }
    }

    public static final class RunSection {
        public final long key = VoxySectionPosition.key(0, 0, 0, 0);
        public final VoxyRealCoveragePersistenceTest.Tracker tracker;
        public RuntimeException serializationFailure;
        public RunSection(Object engine) { tracker = new VoxyRealCoveragePersistenceTest.Tracker(engine); }
    }
    public static final class Memory {
        public final ByteBuffer bytes = ByteBuffer.allocate(128);
        public ByteBuffer asByteBuffer() { return bytes; }
    }
    public static final class Codec {
        public static Memory serialize(RunSection section) {
            if (section.serializationFailure != null) throw section.serializationFailure;
            return new Memory();
        }
    }
    public static final class Backend {
        public RuntimeException failure; public byte[] data;
        public void setSectionData(long key, Memory memory) {
            if (failure != null) throw failure;
            data = new byte[memory.bytes.remaining()]; memory.bytes.duplicate().get(data);
        }
    }

    @Test void actualNativeSaveBodyExecutesAndRethrowsFailuresWithoutPersistingFalseAuthority() throws Exception {
        VoxyRealCoveragePersistence.reset();
        String path = System.getProperty("vss.coverageVoxyJars").split("[|]")[0];
        ClassNode nativeNode;
        try (ZipFile zip = new ZipFile(path)) { nativeNode = read(zip, STORAGE); }
        assertTrue(VoxyStorageTransformer.instrument(nativeNode));
        String fixture = "dev/xantha/vss/compat/ExecutedCoverageStorage";
        String test = "dev/xantha/vss/compat/VoxyStorageBytecodeTest$";
        Remapper mapping = new Remapper() {
            @Override public String map(String name) {
                return switch (name) {
                    case STORAGE -> fixture;
                    case "me/cortex/voxy/common/world/WorldSection" -> test + "RunSection";
                    case "me/cortex/voxy/common/util/MemoryBuffer" -> test + "Memory";
                    case "me/cortex/voxy/common/world/SaveLoadSystem3" -> test + "Codec";
                    case "me/cortex/voxy/common/config/storage/StorageBackend" -> test + "Backend";
                    default -> name;
                };
            }
        };
        ClassNode selected = new ClassNode(); selected.version = Opcodes.V17;
        selected.access = Opcodes.ACC_PUBLIC; selected.name = STORAGE; selected.superName = "java/lang/Object";
        selected.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, "backend", "Lme/cortex/voxy/common/config/storage/StorageBackend;", null, null));
        selected.methods.addAll(nativeNode.methods.stream().filter(m -> m.name.equals("saveSection") || m.name.equals("vss$coverageSaveBody")).toList());
        MethodNode constructor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        constructor.instructions.add(new InsnNode(Opcodes.RETURN)); constructor.maxStack = 1; constructor.maxLocals = 1;
        selected.methods.add(constructor);
        ClassWriter writer = new ClassWriter(0); selected.accept(new ClassRemapper(writer, mapping));
        Class<?> type = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() { byte[] bytes = writer.toByteArray(); return defineClass(fixture.replace('/', '.'), bytes, 0, bytes.length); }
        }.define();
        Object storage = type.getConstructor().newInstance(), engine = new Object();
        Backend backend = new Backend(); type.getField("backend").set(storage, backend);
        VoxyRealCoveragePersistence.register(storage, directory, "executed-native");
        VoxyRealCoveragePersistence.bind(engine, storage);
        VoxyRealCoveragePersistence.committed(engine, 0, 0, 0);
        RunSection section = new RunSection(engine);
        var save = type.getMethod("saveSection", RunSection.class);
        RuntimeException original = new RuntimeException("native write failure"); backend.failure = original;
        assertSame(original, assertThrows(InvocationTargetException.class, () -> save.invoke(storage, section)).getCause());
        assertEquals(0, directory.toFile().list().length);
        backend.failure = null; section.serializationFailure = original;
        assertSame(original, assertThrows(InvocationTargetException.class, () -> save.invoke(storage, section)).getCause());
        section.serializationFailure = null;
        save.invoke(storage, section); // proves exception paths released the stripe and native execution can resume
        assertNotNull(backend.data);
        Object otherStorage = new Object(), otherEngine = new Object();
        VoxyRealCoveragePersistence.register(otherStorage, directory, "executed-native");
        VoxyRealCoveragePersistence.bind(otherEngine, otherStorage);
        Object token = VoxyRealCoveragePersistence.begin(otherStorage, new RunSection(otherEngine), false);
        VoxyRealCoveragePersistence.capture(ByteBuffer.wrap(backend.data));
        VoxyRealCoveragePersistence.finish(token, true);
        assertTrue(VoxyRealCoveragePersistence.contains(otherEngine, 0, 0, 0, 0));
    }
}
