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
    static final String ENGINE = "me/cortex/voxy/common/world/WorldEngine";
    static final String SAVER = "me/cortex/voxy/common/world/service/SectionSavingService";
    static final String CODEC = "me/cortex/voxy/common/world/SaveLoadSystem3";
    static final List<String> TARGETS = List.of(ENGINE, SAVER, CODEC, CLIENT);
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
                    && !call.name.startsWith("vss$")) result.add(call.owner+":"+call.name+call.desc);
            if (instruction instanceof InvokeDynamicInsnNode indy) for (var arg : indy.bsmArgs)
                if (arg instanceof Handle h && !h.getName().startsWith("vss$"))
                    result.add(h.getOwner()+":"+h.getName()+h.getDesc());
        }
        Collections.sort(result);
        return result;
    }

    @Test void actualMaintainedStorageAndIdentityMethodsKeepAllNativeCallsAndVerify() throws Exception {
        String jars = Objects.requireNonNull(System.getProperty("vss.coverageVoxyJars"), "Pass the actual Voxy matrix");
        int checked = 0;
        for (String path : jars.split("[|]")) {
            assertTrue(Files.exists(Path.of(path)), path);
            try (ZipFile zip = new ZipFile(path)) {
                assertFalse(VoxyStorageTransformer.instrument(read(zip, STORAGE)), "Config classes must remain untouched");
                for (String target : TARGETS) {
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
            ClassNode node = read(zip, CODEC);
            node.methods.removeIf(m -> m.name.equals("deserialize"));
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
        public RuntimeException deserializationFailure;
        public boolean deserializes = true;
        public final int lvl = 0, x = 0, y = 0, z = 0;
        public long[] _unsafeGetRawDataArray() { return new long[1]; }
        public RunSection(Object engine) { tracker = new VoxyRealCoveragePersistenceTest.Tracker(engine); }
    }
    public static final class Memory {
        public final ByteBuffer bytes = ByteBuffer.allocate(128);
        public ByteBuffer asByteBuffer() { return bytes; }
        public Memory createUntrackedUnfreeableReference() { return this; }
    }
    public static final class MemoryCache {
        public MemoryCache(long size) { }
        public Memory get() { return new Memory(); }
    }
    public static final class Log { public static void error(Object... message) { } }
    public interface SectionLoader { int load(RunSection section); }
    public interface BatchIo { void add(RunSection section); void commit(); }
    public static final class RunBatch implements BatchIo {
        public RuntimeException addFailure, commitFailure;
        public final Memory memory = new Memory();
        public int commits;
        @Override public void add(RunSection section) {
            if (addFailure != null) throw addFailure;
            VoxyRealCoveragePersistence.captureCodec(memory, section, true);
        }
        @Override public void commit() {
            if (commitFailure != null) throw commitFailure;
            commits++;
        }
    }
    public static final class Codec {
        public static Memory serialize(RunSection section) {
            if (section.serializationFailure != null) throw section.serializationFailure;
            return new Memory();
        }
        public static boolean deserialize(RunSection section, Memory memory) {
            if (section.deserializationFailure != null) throw section.deserializationFailure;
            return section.deserializes;
        }
    }
    public static final class Backend {
        public RuntimeException failure; public byte[] data;
        public void setSectionData(long key, Memory memory) {
            if (failure != null) throw failure;
            data = new byte[memory.bytes.remaining()]; memory.bytes.duplicate().get(data);
        }
        public Memory getSectionData(long key, Memory memory) {
            if (failure != null) throw failure;
            if (data == null) return null;
            memory.bytes.put(data).flip(); return memory;
        }
        public void deleteSectionData(long key) { data = null; }
    }

    @Test void generatedBatchWrappersExecuteNativeInterfaceCallsAndPublishOnlyAfterCommit() throws Exception {
        VoxyRealCoveragePersistence.reset();
        ClassNode saver = null;
        for (String path : System.getProperty("vss.coverageVoxyJars").split("[|]")) {
            try (ZipFile zip = new ZipFile(path)) {
                ClassNode candidate = read(zip, SAVER);
                if (VoxyStorageTransformer.instrument(candidate) && candidate.methods.stream()
                        .anyMatch(m -> m.name.equals("vss$coverageBatchCommit"))) { saver = candidate; break; }
            }
        }
        assertNotNull(saver, "Matrix must include a native batched saver");
        ClassNode callbacks = new ClassNode(); callbacks.version = Opcodes.V17;
        callbacks.access = Opcodes.ACC_PUBLIC; callbacks.name = SAVER; callbacks.superName = "java/lang/Object";
        callbacks.methods.addAll(saver.methods.stream().filter(m -> m.name.startsWith("vss$coverageBatch")).toList());
        String fixture = "dev/xantha/vss/compat/ExecutedCoverageBatchCalls";
        String test = "dev/xantha/vss/compat/VoxyStorageBytecodeTest$";
        ClassWriter writer = new ClassWriter(0);
        callbacks.accept(new ClassRemapper(writer, new Remapper() {
            @Override public String map(String name) {
                return switch (name) {
                    case SAVER -> fixture;
                    case "me/cortex/voxy/common/config/section/SectionStorage$SectionSaveBatch" -> test + "BatchIo";
                    case "me/cortex/voxy/common/world/WorldSection" -> test + "RunSection";
                    default -> name;
                };
            }
        }));
        Class<?> calls = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() { byte[] bytes = writer.toByteArray(); return defineClass(fixture.replace('/', '.'), bytes, 0, bytes.length); }
        }.define();
        var add = calls.getMethod("vss$coverageBatchAdd", BatchIo.class, RunSection.class);
        var commit = calls.getMethod("vss$coverageBatchCommit", BatchIo.class);
        Object storage = new Object(), engine = new Object();
        VoxyRealCoveragePersistence.register(storage, directory, "executed-batch");
        VoxyRealCoveragePersistence.bind(engine, storage);
        VoxyRealCoveragePersistence.committed(engine, 0, 0, 0);
        RunSection section = new RunSection(engine);
        RunBatch batch = new RunBatch();
        assertSame(batch, VoxyRealCoveragePersistence.trackBatch(batch, storage));
        RuntimeException failure = new RuntimeException("native batch failure");
        batch.addFailure = failure;
        assertSame(failure, assertThrows(InvocationTargetException.class, () -> add.invoke(null, batch, section)).getCause());
        batch.addFailure = null;
        add.invoke(null, batch, section);
        assertEquals(0, directory.toFile().list().length, "Native add cannot publish uncommitted data");
        batch.commitFailure = failure;
        assertSame(failure, assertThrows(InvocationTargetException.class, () -> commit.invoke(null, batch)).getCause());
        batch.commitFailure = null;
        commit.invoke(null, batch);
        assertEquals(0, directory.toFile().list().length, "Failed commit must discard its pending proof");
        add.invoke(null, batch, section);
        commit.invoke(null, batch);
        assertEquals(2, batch.commits, "Both successful calls must reach the native batch");
        Object reopenedStorage = new Object(), reopenedEngine = new Object();
        VoxyRealCoveragePersistence.register(reopenedStorage, directory, "executed-batch");
        VoxyRealCoveragePersistence.bind(reopenedEngine, reopenedStorage);
        RunSection reopened = new RunSection(reopenedEngine);
        Object token = VoxyRealCoveragePersistence.begin(reopenedStorage, reopened, false);
        VoxyRealCoveragePersistence.captureCodec(batch.memory, reopened, false);
        VoxyRealCoveragePersistence.finish(token, true);
        assertTrue(VoxyRealCoveragePersistence.contains(reopenedEngine, 0, 0, 0, 0));
    }

    @Test void actualNativeSaveBodyExecutesAndRethrowsFailuresWithoutPersistingFalseAuthority() throws Exception {
        VoxyRealCoveragePersistence.reset();
        String path = Arrays.stream(System.getProperty("vss.coverageVoxyJars").split("[|]"))
                .filter(p -> p.contains("neo-voxy-0.3.1-forge-client")).findFirst().orElseThrow();
        ClassNode nativeNode, saver, engineNode;
        try (ZipFile zip = new ZipFile(path)) {
            nativeNode = read(zip, STORAGE); saver = read(zip, SAVER); engineNode = read(zip, ENGINE);
        }
        assertTrue(VoxyStorageTransformer.instrument(saver));
        assertTrue(VoxyStorageTransformer.instrument(engineNode));
        String fixture = "dev/xantha/vss/compat/ExecutedCoverageStorage";
        String test = "dev/xantha/vss/compat/VoxyStorageBytecodeTest$";
        Remapper mapping = new Remapper() {
            @Override public String map(String name) {
                return switch (name) {
                    case STORAGE -> fixture;
                    case "me/cortex/voxy/common/config/section/SectionStorage" -> fixture;
                    case SAVER, ENGINE -> fixture + "Calls";
                    case "me/cortex/voxy/common/world/WorldSection" -> test + "RunSection";
                    case "me/cortex/voxy/common/util/MemoryBuffer" -> test + "Memory";
                    case CODEC -> fixture + "Codec";
                    case "me/cortex/voxy/common/util/ThreadLocalMemoryBuffer" -> test + "MemoryCache";
                    case "me/cortex/voxy/common/config/storage/StorageBackend" -> test + "Backend";
                    case "me/cortex/voxy/common/Logger" -> test + "Log";
                    case "me/cortex/voxy/common/world/ActiveSectionTracker$SectionLoader" -> test + "SectionLoader";
                    default -> name;
                };
            }
        };
        ClassNode selected = new ClassNode(); selected.version = Opcodes.V17;
        selected.access = Opcodes.ACC_PUBLIC; selected.name = STORAGE; selected.superName = "java/lang/Object";
        selected.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, "backend", "Lme/cortex/voxy/common/config/storage/StorageBackend;", null, null));
        selected.fields.addAll(nativeNode.fields.stream().filter(f -> f.name.equals("MEMORY_CACHE")).toList());
        selected.methods.addAll(nativeNode.methods.stream().filter(m -> Set.of("saveSection", "loadSection", "<clinit>").contains(m.name)).toList());
        MethodNode constructor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        constructor.instructions.add(new InsnNode(Opcodes.RETURN)); constructor.maxStack = 1; constructor.maxLocals = 1;
        selected.methods.add(constructor);
        ClassNode callbacks = new ClassNode(); callbacks.version = Opcodes.V17;
        callbacks.access = Opcodes.ACC_PUBLIC; callbacks.name = SAVER; callbacks.superName = "java/lang/Object";
        callbacks.methods.addAll(saver.methods.stream().filter(m -> m.name.equals("vss$coverageSave")).toList());
        callbacks.methods.addAll(engineNode.methods.stream().filter(m -> m.name.equals("vss$coverageLoad")).toList());
        InvokeDynamicInsnNode binding = engineNode.methods.stream()
                .flatMap(m -> java.util.Arrays.stream(m.instructions.toArray()))
                .filter(i -> i instanceof InvokeDynamicInsnNode).map(i -> (InvokeDynamicInsnNode) i)
                .filter(i -> java.util.Arrays.stream(i.bsmArgs).anyMatch(a -> a instanceof Handle h
                        && h.getName().equals("vss$coverageLoad"))).findFirst().orElseThrow();
        MethodNode bind = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "bind", binding.desc, null, null);
        bind.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        bind.instructions.add(binding.clone(new HashMap<>()));
        bind.instructions.add(new InsnNode(Opcodes.ARETURN)); bind.maxLocals = 1; bind.maxStack = 1;
        callbacks.methods.add(bind);
        // Execute the codec observers with a small in-memory codec body, retaining
        // the production transformer and the actual native storage save/load bodies.
        ClassNode codec = new ClassNode();
        try (var input = getClass().getClassLoader().getResourceAsStream(test + "Codec.class")) {
            new ClassReader(input).accept(codec, 0);
        }
        ClassWriter originalCodec = new ClassWriter(0);
        codec.accept(new ClassRemapper(originalCodec, new Remapper() {
            @Override public String map(String name) {
                if (name.equals(test + "Codec")) return CODEC;
                if (name.equals(test + "RunSection")) return "me/cortex/voxy/common/world/WorldSection";
                if (name.equals(test + "Memory")) return "me/cortex/voxy/common/util/MemoryBuffer";
                return name;
            }
        }));
        codec = new ClassNode(); new ClassReader(originalCodec.toByteArray()).accept(codec, 0);
        codec.nestHostClass = null;
        assertTrue(VoxyStorageTransformer.instrument(codec));
        var generated = new HashMap<String, byte[]>();
        for (var node : List.of(selected, callbacks, codec)) {
            ClassWriter writer = new ClassWriter(0); node.accept(new ClassRemapper(writer, mapping));
            generated.put(mapping.map(node.name).replace('/', '.'), writer.toByteArray());
        }
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = generated.get(name);
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        Class<?> type = loader.loadClass(fixture.replace('/', '.'));
        Class<?> calls = loader.loadClass((fixture + "Calls").replace('/', '.'));
        Object storage = type.getConstructor().newInstance(), engine = new Object();
        Backend backend = new Backend(); type.getField("backend").set(storage, backend);
        VoxyRealCoveragePersistence.register(storage, directory, "executed-native");
        VoxyRealCoveragePersistence.bind(engine, storage);
        VoxyRealCoveragePersistence.committed(engine, 0, 0, 0);
        RunSection section = new RunSection(engine);
        var save = calls.getMethod("vss$coverageSave", type, RunSection.class);
        var load = calls.getMethod("vss$coverageLoad", type, RunSection.class);
        RuntimeException original = new RuntimeException("native write failure"); backend.failure = original;
        assertSame(original, assertThrows(InvocationTargetException.class, () -> save.invoke(null, storage, section)).getCause());
        assertEquals(0, directory.toFile().list().length);
        backend.failure = null; section.serializationFailure = original;
        assertSame(original, assertThrows(InvocationTargetException.class, () -> save.invoke(null, storage, section)).getCause());
        section.serializationFailure = null;
        save.invoke(null, storage, section); // exception paths released the stripe
        assertNotNull(backend.data);
        Object otherStorage = type.getConstructor().newInstance(), otherEngine = new Object();
        Backend otherBackend = new Backend(); otherBackend.data = backend.data.clone();
        type.getField("backend").set(otherStorage, otherBackend);
        VoxyRealCoveragePersistence.register(otherStorage, directory, "executed-native");
        VoxyRealCoveragePersistence.bind(otherEngine, otherStorage);
        RunSection restored = new RunSection(otherEngine);
        restored.deserializationFailure = original;
        assertSame(original, assertThrows(InvocationTargetException.class, () -> load.invoke(null, otherStorage, restored)).getCause());
        assertFalse(VoxyRealCoveragePersistence.contains(otherEngine, 0, 0, 0, 0));
        restored.deserializationFailure = null;
        var boundRead = (SectionLoader) calls.getMethod("bind", type).invoke(null, otherStorage);
        assertEquals(0, boundRead.load(restored), "WorldEngine's real bound method handle must link and execute");
        assertTrue(VoxyRealCoveragePersistence.contains(otherEngine, 0, 0, 0, 0));
        otherBackend.data[0] ^= 1;
        assertEquals(0, load.invoke(null, otherStorage, restored));
        assertFalse(VoxyRealCoveragePersistence.contains(otherEngine, 0, 0, 0, 0), "Different image revokes restored coverage");
        otherBackend.data = backend.data.clone(); restored.deserializes = false;
        assertEquals(-1, load.invoke(null, otherStorage, restored));
        assertFalse(VoxyRealCoveragePersistence.contains(otherEngine, 0, 0, 0, 0));
        assertEquals(1, load.invoke(null, otherStorage, restored));
    }
}
