package dev.xantha.vss.mixin.voxy;

import dev.xantha.vss.common.VSSLogger;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Adapts verified direct-task and pending-map queues without changing native scheduling/refcounts. */
public final class StrictVoxyIngestPlugin implements IMixinConfigPlugin {
    private static final String TARGET = "me.cortex.voxy.common.world.service.VoxelIngestService";
    private static final String TRACKER = "dev/xantha/vss/compat/VoxyIngestTracker";
    private static final String DEQUE = "java/util/concurrent/ConcurrentLinkedDeque";
    private static final String MAP = "java/util/concurrent/ConcurrentHashMap";
    private static final String OBJECT = "Ljava/lang/Object;";
    private static final String REMOVE = "(" + OBJECT + ")" + OBJECT;
    private static final String PUT = "(" + OBJECT + OBJECT + ")" + OBJECT;

    public static boolean instrument(ClassNode node) {
        if (method(node, "vss$ingestTrackingReady", "()Z") != null) return true;
        MethodNode worker = method(node, "processJob", "()V");
        if (worker == null || (worker.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) != 0) return false;
        String task = node.name + "$IngestSection";
        List<MethodInsnNode> pops = calls(worker, DEQUE, "pop", "()" + OBJECT);
        pops.addAll(calls(worker, DEQUE, "poll", "()" + OBJECT));
        if (pops.size() != 1) return false;
        AbstractInsnNode cast = nextCode(pops.get(0));
        if (!(cast instanceof TypeInsnNode type) || type.getOpcode() != Opcodes.CHECKCAST) return false;
        FieldInsnNode section = null;
        for (var insn : worker.instructions) {
            if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
                    && field.owner.equals(task) && field.name.equals("section")) section = field;
        }
        boolean pending = type.desc.equals(node.name + "$IngestNode");
        MethodNode enqueue;
        if (pending) {
            enqueue = method(node, "enqueueJob", "(Lme/cortex/voxy/common/world/WorldEngine;L" + task + ";)Z");
            if (section == null || enqueue == null || !hasField(node, "pending", "L" + MAP + ";")
                    || calls(worker, MAP, "remove", REMOVE).size() != 1
                    || calls(enqueue, MAP, "put", PUT).size() != 1
                    || calls(enqueue, MAP, "remove", REMOVE).size() != 2
                    || calls(enqueue, DEQUE, "add", "(" + OBJECT + ")Z").size() != 1) return false;
        } else {
            if (!type.desc.equals(task) || !calls(worker, MAP, "remove", REMOVE).isEmpty()) return false;
            List<MethodNode> raw = node.methods.stream().filter(m -> m.name.equals("rawIngest0")
                    && calls(m, DEQUE, "add", "(" + OBJECT + ")Z").size() == 1).toList();
            if (raw.size() != 1) return false;
            enqueue = raw.get(0);
        }

        // All shape checks precede mutation. Unknown ports receive no readiness marker.
        MethodInsnNode publish = pending ? calls(enqueue, MAP, "put", PUT).get(0)
                : calls(enqueue, DEQUE, "add", "(" + OBJECT + ")Z").get(0);
        var bind = new InsnList();
        // (..., receiver, [key,] task) -> (..., receiver, [key,] task, task, section).
        // submitted consumes only the last two values, leaving the native call's stack intact.
        bind.add(new InsnNode(Opcodes.DUP));
        if (pending) {
            bind.add(new InsnNode(Opcodes.DUP));
            bind.add(new FieldInsnNode(Opcodes.GETFIELD, task, section.name, section.desc));
            bind.add(call("submitted", "(" + OBJECT + OBJECT + ")V"));
        } else {
            // rawIngest0's single publication belongs to the active VSS raw scope,
            // including ports that snapshot the section or delegate processing.
            bind.add(call("submittedSnapshot", "(" + OBJECT + ")V"));
        }
        enqueue.instructions.insertBefore(publish, bind);
        if (pending) {
            after(enqueue, publish, "discarded");
            for (var remove : calls(enqueue, MAP, "remove", REMOVE)) after(enqueue, remove, "discarded");
            after(worker, calls(worker, MAP, "remove", REMOVE).get(0), "workerTask");
        } else after(worker, pops.get(0), "workerTask");
        enqueue.maxStack += 2;
        worker.maxStack += 1;
        wrapWorker(node, worker);

        MethodNode marker = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "vss$ingestTrackingReady", "()Z", null, null);
        marker.instructions.add(new InsnNode(Opcodes.ICONST_1));
        marker.instructions.add(new InsnNode(Opcodes.IRETURN));
        marker.maxStack = 1;
        node.methods.add(marker);
        return true;
    }

    private static void wrapWorker(ClassNode node, MethodNode body) {
        // Keep the original body, its handlers and its normal Mixin RETURN callback intact.
        // A wrapper catches only failures that have escaped all native handlers/finally.
        MethodNode wrapper = new MethodNode(body.access, body.name, body.desc, null, null);
        body.name = "vss$processTrackedIngestBody";
        body.access = (body.access | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC)
                & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_SYNCHRONIZED);
        var start = new LabelNode();
        var end = new LabelNode();
        var failed = new LabelNode();
        var code = wrapper.instructions;
        code.add(call("enterWorker", "()" + OBJECT));
        code.add(new VarInsnNode(Opcodes.ASTORE, 1));
        code.add(start);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.name, body.name, body.desc, false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(call("finishWorker", "(" + OBJECT + ")V"));
        code.add(end);
        code.add(new InsnNode(Opcodes.RETURN));
        code.add(failed);
        code.add(new FrameNode(Opcodes.F_FULL, 2, new Object[]{node.name, "java/lang/Object"},
                1, new Object[]{"java/lang/Throwable"}));
        code.add(new VarInsnNode(Opcodes.ASTORE, 2));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(call("workerFailed", "(" + OBJECT + "Ljava/lang/Throwable;)V"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new InsnNode(Opcodes.ATHROW));
        wrapper.tryCatchBlocks.add(new TryCatchBlockNode(start, end, failed, "java/lang/Throwable"));
        wrapper.maxStack = 2;
        wrapper.maxLocals = 3;
        node.methods.add(wrapper);
    }

    private static void after(MethodNode method, MethodInsnNode nativeCall, String helper) {
        var code = new InsnList();
        code.add(new InsnNode(Opcodes.DUP));
        code.add(call(helper, "(" + OBJECT + ")V"));
        method.instructions.insert(nativeCall, code);
    }

    private static MethodInsnNode call(String name, String desc) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, TRACKER, name, desc, false);
    }

    private static AbstractInsnNode nextCode(AbstractInsnNode node) {
        do { node = node.getNext(); } while (node != null && node.getOpcode() < 0);
        return node;
    }

    private static boolean hasField(ClassNode node, String name, String desc) {
        return node.fields.stream().anyMatch(f -> f.name.equals(name) && f.desc.equals(desc));
    }

    private static MethodNode method(ClassNode node, String name, String desc) {
        return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst().orElse(null);
    }

    private static List<MethodInsnNode> calls(MethodNode method, String owner, String name, String desc) {
        var result = new java.util.ArrayList<MethodInsnNode>();
        for (var insn : method.instructions) if (insn instanceof MethodInsnNode call
                && call.owner.equals(owner) && call.name.equals(name) && call.desc.equals(desc)) result.add(call);
        return result;
    }

    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {
        if (TARGET.equals(target) && mixin.endsWith(".StrictVoxyIngestMixin") && !instrument(node))
            VSSLogger.warn("Unsupported Voxy ingest queue layout; task completion tracking disabled, using mesh/GPU readiness");
    }

    private Boolean rendererPresent;
    @Override public boolean shouldApplyMixin(String target, String mixin) {
        // Ingest is independent of the renderer. Incomplete server/data-only ports must
        // not receive half of the mutually dependent mesh/batch/upload tracking group.
        if (mixin.endsWith(".StrictVoxyIngestMixin")) return true;
        if (rendererPresent == null) {
            try {
                var provider = org.spongepowered.asm.service.MixinService.getService().getBytecodeProvider();
                for (String name : List.of("building.RenderGenerationService", "building.BuiltSection",
                        "hierachical.AsyncNodeManager", "hierachical.AsyncNodeManager$SyncResults"))
                    provider.getClassNode("me.cortex.voxy.client.core.rendering." + name);
                provider.getClassNode("me.cortex.voxy.client.core.VoxyRenderSystem");
                rendererPresent = true;
            } catch (Exception absent) { rendererPresent = false; }
        }
        return rendererPresent;
    }
    @Override public void onLoad(String mixinPackage) { }
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> mine, Set<String> others) { }
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) { }
}
