package dev.xantha.vss.compat.transform;

import java.util.List;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** No format changes; observes verified native serialization and its actual result. */
public final class VoxyStorageTransformer {
    private VoxyStorageTransformer() { }
    private static final String API = "dev/xantha/vss/compat/VoxyRealCoveragePersistence";
    private static final String WORLD = "Lme/cortex/voxy/common/world/WorldSection;";
    private static final String MEMORY = "Lme/cortex/voxy/common/util/MemoryBuffer;";
    private static final String OBJECT = "Ljava/lang/Object;";
    private static final String STORAGE = "me/cortex/voxy/common/config/section/SectionSerializationStorage";
    private static final String CLIENT = "me/cortex/voxy/client/VoxyClientInstance";

    public static boolean instrument(ClassNode node) {
        if (method(node, "vss$coveragePersistenceReady", "()Z") != null) return true;
        if (node.name.equals(STORAGE)) {
            MethodNode save = method(node, "saveSection", "(" + WORLD + ")V");
            MethodNode load = method(node, "loadSection", "(" + WORLD + ")I");
            if (save == null || load == null || !save.tryCatchBlocks.isEmpty()
                    || ((save.access | load.access) & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) != 0) return false;
            var serial = calls(save, "me/cortex/voxy/common/world/SaveLoadSystem3", "serialize", "(" + WORLD + ")" + MEMORY);
            var writes = calls(save, "me/cortex/voxy/common/config/storage/StorageBackend", "setSectionData", "(J" + MEMORY + ")V");
            var reads = calls(load, "me/cortex/voxy/common/config/storage/StorageBackend", "getSectionData", "(J" + MEMORY + ")" + MEMORY);
            if (serial.size() != 1 || writes.size() != 1 || reads.size() != 1
                    || calls(load, "me/cortex/voxy/common/world/SaveLoadSystem3", "deserialize", "(" + WORLD + MEMORY + ")Z").size() != 1) return false;
            for (var pair : List.of(new Object[]{save, serial.get(0)}, new Object[]{load, reads.get(0)})) {
                InsnList capture = new InsnList(); capture.add(new InsnNode(Opcodes.DUP));
                capture.add(new VarInsnNode(Opcodes.ALOAD, 0)); capture.add(new VarInsnNode(Opcodes.ALOAD, 1));
                capture.add(call("capture", "(" + OBJECT + OBJECT + OBJECT + ")V"));
                ((MethodNode) pair[0]).instructions.insert((AbstractInsnNode) pair[1], capture);
                ((MethodNode) pair[0]).maxStack += 3;
            }
            wrap(node, save, true); wrap(node, load, false);
        } else if (node.name.equals(CLIENT)) {
            MethodNode create = method(node, "createStorage", "(Lme/cortex/voxy/commonImpl/WorldIdentifier;)Lme/cortex/voxy/common/config/section/SectionStorage;");
            if (create == null || (create.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) != 0) return false;
            for (var instruction : create.instructions.toArray()) if (instruction.getOpcode() == Opcodes.ARETURN) {
                InsnList capture = new InsnList(); capture.add(new InsnNode(Opcodes.DUP));
                capture.add(new VarInsnNode(Opcodes.ALOAD, 0)); capture.add(new VarInsnNode(Opcodes.ALOAD, 1));
                capture.add(call("register", "(" + OBJECT + OBJECT + OBJECT + ")V"));
                create.instructions.insertBefore(instruction, capture);
            }
            create.maxStack += 3;
        } else return false;
        MethodNode marker = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "vss$coveragePersistenceReady", "()Z", null, null);
        marker.instructions.add(new InsnNode(Opcodes.ICONST_1)); marker.instructions.add(new InsnNode(Opcodes.IRETURN));
        marker.maxStack = 1; node.methods.add(marker); return true;
    }

    private static void wrap(ClassNode node, MethodNode body, boolean save) {
        MethodNode wrapper = new MethodNode(body.access, body.name, body.desc, body.signature,
                body.exceptions.toArray(String[]::new));
        body.name = save ? "vss$coverageSaveBody" : "vss$coverageLoadBody";
        body.access = (body.access | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC)
                & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_SYNCHRONIZED);
        LabelNode start = new LabelNode(), end = new LabelNode(), failed = new LabelNode();
        InsnList code = wrapper.instructions;
        code.add(new VarInsnNode(Opcodes.ALOAD, 0)); code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new InsnNode(save ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
        code.add(call("begin", "(" + OBJECT + OBJECT + "Z)" + OBJECT));
        code.add(new VarInsnNode(Opcodes.ASTORE, 2)); code.add(start);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0)); code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.name, body.name, body.desc, false));
        if (!save) code.add(new VarInsnNode(Opcodes.ISTORE, 3));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        if (save) code.add(new InsnNode(Opcodes.ICONST_1));
        else {
            code.add(new VarInsnNode(Opcodes.ILOAD, 3));
            // loadSection: zero alone means successfully deserialized native data.
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, API, "loadedSuccessfully", "(I)Z", false));
        }
        code.add(call("finish", "(" + OBJECT + "Z)V")); code.add(end);
        if (!save) code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new InsnNode(save ? Opcodes.RETURN : Opcodes.IRETURN));
        code.add(failed);
        code.add(new FrameNode(Opcodes.F_FULL, 3, new Object[]{node.name, WORLD.substring(1, WORLD.length() - 1), "java/lang/Object"},
                1, new Object[]{"java/lang/Throwable"}));
        code.add(new VarInsnNode(Opcodes.ASTORE, 3)); code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new InsnNode(Opcodes.ICONST_0)); code.add(call("finish", "(" + OBJECT + "Z)V"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 3)); code.add(new InsnNode(Opcodes.ATHROW));
        wrapper.tryCatchBlocks.add(new TryCatchBlockNode(start, end, failed, "java/lang/Throwable"));
        wrapper.maxStack = 3; wrapper.maxLocals = 4; node.methods.add(wrapper);
    }

    private static MethodInsnNode call(String name, String descriptor) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, API, name, descriptor, false);
    }
    private static MethodNode method(ClassNode node, String name, String descriptor) {
        return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(descriptor)).findFirst().orElse(null);
    }
    private static List<MethodInsnNode> calls(MethodNode method, String owner, String name, String descriptor) {
        return java.util.stream.StreamSupport.stream(method.instructions.spliterator(), false)
                .filter(i -> i instanceof MethodInsnNode).map(i -> (MethodInsnNode) i)
                .filter(i -> i.owner.equals(owner) && i.name.equals(name) && i.desc.equals(descriptor)).toList();
    }
}
