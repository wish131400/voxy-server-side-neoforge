package dev.xantha.vss.compat.transform;

import java.util.ArrayList;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Observes native IO at callers; config classes may already be loaded by Voxy's plugin. */
public final class VoxyStorageTransformer {
    private VoxyStorageTransformer() { }
    private static final String API = "dev/xantha/vss/compat/VoxyRealCoveragePersistence";
    private static final String WORLD = "Lme/cortex/voxy/common/world/WorldSection;";
    private static final String MEMORY = "Lme/cortex/voxy/common/util/MemoryBuffer;";
    private static final String OBJECT = "Ljava/lang/Object;";
    private static final String STORAGE = "me/cortex/voxy/common/config/section/SectionStorage";
    private static final String BATCH = STORAGE + "$SectionSaveBatch";
    private static final String ENGINE = "me/cortex/voxy/common/world/WorldEngine";
    private static final String SAVER = "me/cortex/voxy/common/world/service/SectionSavingService";
    private static final String CODEC = "me/cortex/voxy/common/world/SaveLoadSystem3";
    private static final String CLIENT = "me/cortex/voxy/client/VoxyClientInstance";

    public static boolean instrument(ClassNode node) {
        if (method(node, "vss$coveragePersistenceReady", "()Z") != null) return true;
        if (node.name.equals(ENGINE) || node.name.equals(SAVER)) {
            boolean direct = observeCalls(node, node.name.equals(SAVER));
            boolean batched = node.name.equals(SAVER) && observeBatches(node);
            if (!direct && !batched) return false;
        } else if (node.name.equals(CODEC)) {
            if (!observeCodec(node)) return false;
        } else if (node.name.equals(CLIENT)) {
            MethodNode create = method(node, "createStorage", "(Lme/cortex/voxy/commonImpl/WorldIdentifier;)L" + STORAGE + ";");
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

    private static boolean observeCalls(ClassNode node, boolean save) {
        String name = save ? "saveSection" : "loadSection";
        String descriptor = "(" + WORLD + ")" + (save ? "V" : "I");
        String wrapper = save ? "vss$coverageSave" : "vss$coverageLoad";
        String wrapped = "(L" + STORAGE + ";" + WORLD + ")" + (save ? "V" : "I");
        var direct = new ArrayList<MethodInsnNode>();
        var bound = new ArrayList<InvokeDynamicInsnNode>();
        for (var method : node.methods) for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode c && c.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && c.owner.equals(STORAGE) && c.name.equals(name) && c.desc.equals(descriptor)) direct.add(c);
            if (instruction instanceof InvokeDynamicInsnNode indy)
                for (var arg : indy.bsmArgs) if (arg instanceof Handle h && h.getTag() == Opcodes.H_INVOKEVIRTUAL
                        && h.getOwner().equals(STORAGE) && h.getName().equals(name) && h.getDesc().equals(descriptor))
                    bound.add(indy);
        }
        if (direct.isEmpty() && bound.isEmpty()) return false;
        for (var c : direct) {
            c.setOpcode(Opcodes.INVOKESTATIC); c.owner = node.name; c.name = wrapper; c.desc = wrapped;
        }
        // WorldEngine binds storage::loadSection in its tracker constructor. The
        // static handle keeps the captured storage and native section arguments.
        for (var indy : bound) for (int i = 0; i < indy.bsmArgs.length; i++)
            if (indy.bsmArgs[i] instanceof Handle h && h.getTag() == Opcodes.H_INVOKEVIRTUAL
                    && h.getOwner().equals(STORAGE) && h.getName().equals(name) && h.getDesc().equals(descriptor))
                indy.bsmArgs[i] = new Handle(Opcodes.H_INVOKESTATIC, node.name, wrapper, wrapped, false);
        node.methods.add(wrapper(wrapper, name, descriptor, wrapped, save, false));
        return true;
    }

    private static boolean observeBatches(ClassNode node) {
        var creates = new java.util.LinkedHashMap<MethodInsnNode, MethodNode>();
        var adds = new ArrayList<MethodInsnNode>();
        var commits = new ArrayList<MethodInsnNode>();
        for (var method : node.methods) for (var instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode c)) continue;
            if (c.getOpcode() == Opcodes.INVOKEVIRTUAL && c.owner.equals(STORAGE)
                    && c.name.equals("createSaveBatch") && c.desc.equals("()L" + BATCH + ";")) creates.put(c, method);
            if (c.getOpcode() == Opcodes.INVOKEINTERFACE && c.owner.equals(BATCH)) {
                if (c.name.equals("add") && c.desc.equals("(" + WORLD + ")V")) adds.add(c);
                if (c.name.equals("commit") && c.desc.equals("()V")) commits.add(c);
            }
        }
        if (creates.isEmpty() || adds.isEmpty() || commits.isEmpty()) return false;
        for (var site : creates.entrySet()) {
            var c = site.getKey(); var method = site.getValue();
            method.instructions.insertBefore(c, new InsnNode(Opcodes.DUP));
            InsnList tracked = new InsnList(); tracked.add(new InsnNode(Opcodes.SWAP));
            tracked.add(call("trackBatch", "(" + OBJECT + OBJECT + ")" + OBJECT));
            tracked.add(new TypeInsnNode(Opcodes.CHECKCAST, BATCH)); method.instructions.insert(c, tracked);
            method.maxStack++;
        }
        for (var c : adds) {
            c.setOpcode(Opcodes.INVOKESTATIC); c.owner = node.name; c.itf = false;
            c.name = "vss$coverageBatchAdd"; c.desc = "(L" + BATCH + ";" + WORLD + ")V";
        }
        for (var c : commits) {
            c.setOpcode(Opcodes.INVOKESTATIC); c.owner = node.name; c.itf = false;
            c.name = "vss$coverageBatchCommit"; c.desc = "(L" + BATCH + ";)V";
        }
        node.methods.add(wrapper("vss$coverageBatchAdd", "add", "(" + WORLD + ")V",
                "(L" + BATCH + ";" + WORLD + ")V", true, true));
        MethodNode commit = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "vss$coverageBatchCommit", "(L" + BATCH + ";)V", null, null);
        LabelNode start = new LabelNode(), end = new LabelNode(), failed = new LabelNode();
        commit.instructions.add(start); commit.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        commit.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, BATCH, "commit", "()V", true));
        commit.instructions.add(end); commit.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        commit.instructions.add(new InsnNode(Opcodes.ICONST_1));
        commit.instructions.add(call("completeBatch", "(" + OBJECT + "Z)V"));
        commit.instructions.add(new InsnNode(Opcodes.RETURN)); commit.instructions.add(failed);
        commit.instructions.add(new FrameNode(Opcodes.F_FULL, 1, new Object[]{BATCH}, 1, new Object[]{"java/lang/Throwable"}));
        commit.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1)); commit.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        commit.instructions.add(new InsnNode(Opcodes.ICONST_0));
        commit.instructions.add(call("completeBatch", "(" + OBJECT + "Z)V"));
        commit.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1)); commit.instructions.add(new InsnNode(Opcodes.ATHROW));
        commit.tryCatchBlocks.add(new TryCatchBlockNode(start, end, failed, "java/lang/Throwable"));
        commit.maxLocals = 2; commit.maxStack = 2; node.methods.add(commit);
        return true;
    }

    private static MethodNode wrapper(String wrapper, String name, String descriptor, String wrapped, boolean save, boolean batch) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                wrapper, wrapped, null, null);
        LabelNode start = new LabelNode(), end = new LabelNode(), failed = new LabelNode();
        InsnList code = method.instructions;
        code.add(new VarInsnNode(Opcodes.ALOAD, 0)); code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        if (!batch) code.add(new InsnNode(save ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
        code.add(call(batch ? "beginBatch" : "begin", "(" + OBJECT + OBJECT + (batch ? "" : "Z") + ")" + OBJECT));
        code.add(new VarInsnNode(Opcodes.ASTORE, 2)); code.add(start);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0)); code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(batch ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL,
                batch ? BATCH : STORAGE, name, descriptor, batch));
        if (!save) code.add(new VarInsnNode(Opcodes.ISTORE, 3));
        code.add(end);
        if (batch) code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        if (save) code.add(new InsnNode(Opcodes.ICONST_1));
        else {
            code.add(new VarInsnNode(Opcodes.ILOAD, 3));
            code.add(call("loadedSuccessfully", "(I)Z"));
        }
        code.add(call(batch ? "stageBatch" : "finish", "(" + OBJECT + (batch ? OBJECT : "") + "Z)V"));
        if (!save) code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new InsnNode(save ? Opcodes.RETURN : Opcodes.IRETURN));
        code.add(failed);
        code.add(new FrameNode(Opcodes.F_FULL, 3, new Object[]{batch ? BATCH : STORAGE, WORLD.substring(1, WORLD.length() - 1), "java/lang/Object"},
                1, new Object[]{"java/lang/Throwable"}));
        code.add(new VarInsnNode(Opcodes.ASTORE, 3));
        if (batch) code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2)); code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(call(batch ? "stageBatch" : "finish", "(" + OBJECT + (batch ? OBJECT : "") + "Z)V"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 3)); code.add(new InsnNode(Opcodes.ATHROW));
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, failed, "java/lang/Throwable"));
        method.maxStack = 3; method.maxLocals = 4; return method;
    }

    private static boolean observeCodec(ClassNode node) {
        MethodNode serialize = method(node, "serialize", "(" + WORLD + ")" + MEMORY);
        MethodNode deserialize = method(node, "deserialize", "(" + WORLD + MEMORY + ")Z");
        if (serialize == null || deserialize == null || (serialize.access & Opcodes.ACC_STATIC) == 0
                || (deserialize.access & Opcodes.ACC_STATIC) == 0
                || java.util.Arrays.stream(serialize.instructions.toArray()).noneMatch(i -> i.getOpcode() == Opcodes.ARETURN)) return false;
        for (var instruction : serialize.instructions.toArray()) if (instruction.getOpcode() == Opcodes.ARETURN) {
            InsnList capture = new InsnList(); capture.add(new InsnNode(Opcodes.DUP));
            capture.add(new VarInsnNode(Opcodes.ALOAD, 0)); capture.add(new InsnNode(Opcodes.ICONST_1));
            capture.add(call("captureCodec", "(" + OBJECT + OBJECT + "Z)V"));
            serialize.instructions.insertBefore(instruction, capture);
        }
        serialize.maxStack += 3;
        InsnList capture = new InsnList(); capture.add(new VarInsnNode(Opcodes.ALOAD, 1));
        capture.add(new VarInsnNode(Opcodes.ALOAD, 0)); capture.add(new InsnNode(Opcodes.ICONST_0));
        capture.add(call("captureCodec", "(" + OBJECT + OBJECT + "Z)V"));
        deserialize.instructions.insert(capture); deserialize.maxStack = Math.max(3, deserialize.maxStack);
        return true;
    }

    private static MethodInsnNode call(String name, String descriptor) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, API, name, descriptor, false);
    }
    private static MethodNode method(ClassNode node, String name, String descriptor) {
        return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(descriptor)).findFirst().orElse(null);
    }
}
