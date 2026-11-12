package dev.xantha.vss.mixin.thebeyond;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Guards the End-dimension lookup in The Beyond's merged StructureStart.placeInChunk handler. Before the load that
 * feeds level.getLevel(), the guard is asked once: a VSS prediction level that knows its dimension (1 = the End,
 * 0 = any other) takes the handler's own End or not-End branch; every other level (-1) runs the original five
 * instructions. The handler is identified by Mixin's merge annotation, its descriptor and its complete instruction
 * shape; every check runs before the single insertion, and a failed self-check afterwards removes it again.
 */
public final class BeyondHandlerPatch implements Opcodes {
    public static final String TARGET = "net/minecraft/world/level/levelgen/structure/StructureStart";
    public static final String BEYOND_MIXIN = "com.thebeyond.mixin.StructureStartSanctionMixin";
    public static final String HANDLER_DESC = "(Lnet/minecraft/world/level/WorldGenLevel;"
            + "Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/chunk/ChunkGenerator;"
            + "Lnet/minecraft/util/RandomSource;Lnet/minecraft/world/level/levelgen/structure/BoundingBox;"
            + "Lnet/minecraft/world/level/ChunkPos;Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;)V";
    public static final String GUARD_MIXIN = "dev.xantha.vss.mixin.thebeyond.StructureStartPredictionEndMixin";
    public static final String GUARD_DESC = "(Lnet/minecraft/world/level/WorldGenLevel;)I";
    public static final String MIXIN_MERGED = "Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;";
    /** SHA-256 of {@link #shape} of The Beyond 1.0.0's handler as merged by Sponge Mixin 0.8.7. */
    public static final String HANDLER_SHAPE = "1EEADAB238A02264D0720319A8F48F4F24E8E0820242E762690C9666E86CC988";
    /** LOOKUPSWITCH keys and their meaning; anything else (-1) falls through to The Beyond's own check. */
    public static final int NOT_END = 0;
    public static final int END = 1;

    static final String LEVEL = "net/minecraft/world/level/WorldGenLevel";
    static final String SERVER_LEVEL = "net/minecraft/server/level/ServerLevel";
    static final String RESOURCE_KEY = "Lnet/minecraft/resources/ResourceKey;";
    static final String OPERATION = "com/llamalad7/mixinextras/injector/wrapoperation/Operation";

    public enum Outcome { PATCHED, HANDLER_MISSING, HANDLER_CHANGED, GUARD_MISSING, PATCH_UNVERIFIED }

    public record Result(Outcome outcome, String handler, String detail) {
        public boolean patched() {
            return outcome == Outcome.PATCHED;
        }
    }

    /**
     * The dimension check {@code if (beyondGen || level.getLevel().dimension() == Level.END)}: the load feeding
     * getLevel(), the label of the "not the End" branch and the label of the End branch (the beyondGen jump target).
     */
    record Anchor(VarInsnNode levelLoad, LabelNode notEnd, LabelNode end) {
    }

    private BeyondHandlerPatch() {
    }

    public static Result apply(ClassNode target) {
        if (!TARGET.equals(target.name)) return new Result(Outcome.HANDLER_MISSING, null, "class " + target.name + " is not StructureStart");
        List<MethodNode> merged = mergedFrom(target, BEYOND_MIXIN);
        List<MethodNode> handlers = new ArrayList<>();
        for (MethodNode method : merged) if (HANDLER_DESC.equals(method.desc)) handlers.add(method);
        if (merged.isEmpty()) return new Result(Outcome.HANDLER_MISSING, null, "no method was merged from " + BEYOND_MIXIN);
        if (handlers.isEmpty()) {
            return new Result(Outcome.HANDLER_CHANGED, null, "no method merged from " + BEYOND_MIXIN
                    + " has the verified descriptor; merged: " + describe(merged));
        }
        if (handlers.size() > 1) return new Result(Outcome.HANDLER_CHANGED, null, "more than one candidate handler: " + describe(handlers));
        MethodNode handler = handlers.get(0);
        if ((handler.access & ACC_STATIC) != 0) return new Result(Outcome.HANDLER_CHANGED, handler.name, "the handler became static");
        String fingerprint = sha256(shape(handler));
        if (!HANDLER_SHAPE.equals(fingerprint)) {
            return new Result(Outcome.HANDLER_CHANGED, handler.name, "instruction shape " + fingerprint
                    + " is not the verified shape " + HANDLER_SHAPE);
        }
        Anchor anchor = anchor(handler);
        if (anchor == null) return new Result(Outcome.HANDLER_CHANGED, handler.name, "the End-dimension check was not found");
        MethodNode guard = null;
        for (MethodNode method : mergedFrom(target, GUARD_MIXIN)) {
            if (GUARD_DESC.equals(method.desc) && (method.access & ACC_STATIC) != 0) {
                if (guard != null) return new Result(Outcome.GUARD_MISSING, handler.name, "more than one merged guard method");
                guard = method;
            }
        }
        if (guard == null) return new Result(Outcome.GUARD_MISSING, handler.name, "the guard method of " + GUARD_MIXIN + " was not merged");
        // The new default target follows the switch, so it needs a stack map frame: Mixin's writer and ModLauncher's
        // writer for every Mixin-processed class both use COMPUTE_FRAMES (verified by the offline premises).
        LabelNode original = new LabelNode();
        InsnList insert = new InsnList();
        insert.add(new VarInsnNode(ALOAD, 1));
        insert.add(new MethodInsnNode(INVOKESTATIC, TARGET, guard.name, GUARD_DESC, false));
        insert.add(new LookupSwitchInsnNode(original, new int[] {NOT_END, END}, new LabelNode[] {anchor.notEnd(), anchor.end()}));
        insert.add(original);
        AbstractInsnNode[] added = insert.toArray();
        handler.instructions.insertBefore(anchor.levelLoad(), insert);
        String problem = verifyPatched(handler, guard.name, anchor);
        if (problem != null) {
            for (AbstractInsnNode node : added) handler.instructions.remove(node);
            return new Result(Outcome.PATCH_UNVERIFIED, handler.name, "the inserted dispatch failed its self-check (" + problem + ") and was removed");
        }
        return new Result(Outcome.PATCHED, handler.name, "level.getLevel() now runs only for levels that are not VSS prediction levels "
                + "with a known dimension; those take The Beyond's own End or not-End branch by the dimension VSS's server named");
    }

    /** The patched handler: one guard call, its dispatch right before the untouched check, one Operation.call. */
    static String verifyPatched(MethodNode handler, String guardName, Anchor anchor) {
        List<MethodInsnNode> guards = new ArrayList<>();
        int operations = 0;
        int getLevels = 0;
        for (AbstractInsnNode insn : handler.instructions) {
            if (!(insn instanceof MethodInsnNode call)) continue;
            if (call.owner.equals(TARGET) && call.name.equals(guardName)) guards.add(call);
            if (call.owner.equals(OPERATION) && call.name.equals("call")) operations++;
            if (call.owner.equals(LEVEL) && call.name.equals("getLevel")) getLevels++;
        }
        if (guards.size() != 1) return guards.size() + " guard calls";
        if (operations != 1) return operations + " Operation.call sites";
        if (getLevels != 1) return getLevels + " level.getLevel() calls";
        MethodInsnNode call = guards.get(0);
        if (call.getOpcode() != INVOKESTATIC || !call.desc.equals(GUARD_DESC)) return "guard call shape";
        if (!(previous(call) instanceof VarInsnNode load) || load.getOpcode() != ALOAD || load.var != 1) return "guard argument is not the level";
        if (!(previous(load) instanceof JumpInsnNode beyondGen) || beyondGen.getOpcode() != IFNE || beyondGen.label != anchor.end()) {
            return "guard does not directly follow the generator check";
        }
        if (!(next(call) instanceof LookupSwitchInsnNode dispatch)) return "no dispatch after the guard";
        if (!dispatch.keys.equals(List.of(NOT_END, END)) || dispatch.labels.size() != 2 || dispatch.labels.get(0) != anchor.notEnd()
                || dispatch.labels.get(1) != anchor.end()) {
            return "dispatch targets";
        }
        if (dispatch.getNext() != dispatch.dflt || next(dispatch.dflt) != anchor.levelLoad()) {
            return "the default target is not The Beyond's own check";
        }
        if (notEndAfter(anchor.levelLoad(), anchor.end()) != anchor.notEnd()) return "The Beyond's own check changed";
        return null;
    }

    public static List<MethodNode> mergedFrom(ClassNode target, String mixin) {
        List<MethodNode> found = new ArrayList<>();
        for (MethodNode method : target.methods) {
            if (method.visibleAnnotations == null) continue;
            for (AnnotationNode annotation : method.visibleAnnotations) {
                if (MIXIN_MERGED.equals(annotation.desc) && mixin.equals(value(annotation, "mixin"))) {
                    found.add(method);
                    break;
                }
            }
        }
        return found;
    }

    static Object value(AnnotationNode annotation, String key) {
        if (annotation.values == null) return null;
        for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        }
        return null;
    }

    static Anchor anchor(MethodNode handler) {
        Anchor found = null;
        for (AbstractInsnNode insn : handler.instructions) {
            if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != INVOKEINTERFACE || !LEVEL.equals(call.owner)
                    || !"getLevel".equals(call.name) || !"()Lnet/minecraft/server/level/ServerLevel;".equals(call.desc)) continue;
            if (found != null) return null;
            if (!(insn.getPrevious() instanceof VarInsnNode load) || load.getOpcode() != ALOAD || load.var != 1) return null;
            if (!(previous(load) instanceof JumpInsnNode beyondGen) || beyondGen.getOpcode() != IFNE) return null;
            LabelNode notEnd = notEndAfter(load, beyondGen.label);
            if (notEnd == null) return null;
            found = new Anchor(load, notEnd, beyondGen.label);
        }
        return found;
    }

    /**
     * From the load on: ALOAD 1, getLevel(), dimension(), Level.END, IF_ACMPNE to the not-End label, and the End
     * label directly after it. Returns the not-End label, or null when the sequence differs.
     */
    static LabelNode notEndAfter(VarInsnNode load, LabelNode endLabel) {
        if (load.getOpcode() != ALOAD || load.var != 1) return null;
        AbstractInsnNode getLevel = next(load);
        AbstractInsnNode dimension = next(getLevel);
        AbstractInsnNode end = next(dimension);
        AbstractInsnNode compare = next(end);
        if (!(getLevel instanceof MethodInsnNode g) || g.getOpcode() != INVOKEINTERFACE || !LEVEL.equals(g.owner)
                || !"getLevel".equals(g.name) || !("()L" + SERVER_LEVEL + ";").equals(g.desc)) return null;
        if (!(dimension instanceof MethodInsnNode d) || d.getOpcode() != INVOKEVIRTUAL || !SERVER_LEVEL.equals(d.owner)
                || !"dimension".equals(d.name) || !("()" + RESOURCE_KEY).equals(d.desc)) return null;
        if (!(end instanceof FieldInsnNode e) || e.getOpcode() != GETSTATIC || !"net/minecraft/world/level/Level".equals(e.owner)
                || !"END".equals(e.name) || !RESOURCE_KEY.equals(e.desc)) return null;
        if (!(compare instanceof JumpInsnNode notEnd) || notEnd.getOpcode() != IF_ACMPNE) return null;
        if (firstLabelAfter(notEnd) != endLabel || notEnd.label == endLabel) return null;
        return notEnd.label;
    }

    // The instruction before node, skipping only line numbers and frames: a label here would be a jump target.
    private static AbstractInsnNode previous(AbstractInsnNode node) {
        AbstractInsnNode p = node.getPrevious();
        while (p instanceof LineNumberNode || p instanceof FrameNode) p = p.getPrevious();
        return p;
    }

    private static AbstractInsnNode next(AbstractInsnNode node) {
        AbstractInsnNode n = node.getNext();
        while (n instanceof LineNumberNode || n instanceof FrameNode) n = n.getNext();
        return n;
    }

    private static LabelNode firstLabelAfter(AbstractInsnNode node) {
        AbstractInsnNode n = node.getNext();
        while (n instanceof LineNumberNode || n instanceof FrameNode) n = n.getNext();
        return n instanceof LabelNode label ? label : null;
    }

    /** Opcodes and operands in order; only branch-target labels, no line numbers or frames; then the try blocks. */
    public static String shape(MethodNode method) {
        Set<LabelNode> targets = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof JumpInsnNode jump) targets.add(jump.label);
            if (insn instanceof TableSwitchInsnNode table) {
                targets.add(table.dflt);
                targets.addAll(table.labels);
            }
            if (insn instanceof LookupSwitchInsnNode lookup) {
                targets.add(lookup.dflt);
                targets.addAll(lookup.labels);
            }
        }
        if (method.tryCatchBlocks != null) {
            for (TryCatchBlockNode block : method.tryCatchBlocks) {
                targets.add(block.start);
                targets.add(block.end);
                targets.add(block.handler);
            }
        }
        Map<LabelNode, Integer> ids = new IdentityHashMap<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof LabelNode label && targets.contains(label)) ids.put(label, ids.size());
        }
        StringBuilder out = new StringBuilder();
        for (AbstractInsnNode insn : method.instructions) {
            String line = line(insn, ids);
            if (line != null) out.append(line).append('\n');
        }
        if (method.tryCatchBlocks != null) {
            for (TryCatchBlockNode block : method.tryCatchBlocks) {
                out.append("TRY ").append(label(ids, block.start)).append(' ').append(label(ids, block.end)).append(' ')
                        .append(label(ids, block.handler)).append(' ').append(block.type).append('\n');
            }
        }
        return out.toString();
    }

    private static String line(AbstractInsnNode insn, Map<LabelNode, Integer> ids) {
        int op = insn.getOpcode();
        if (insn instanceof LabelNode label) return ids.containsKey(label) ? label(ids, label) : null;
        if (insn instanceof LineNumberNode || insn instanceof FrameNode) return null;
        if (insn instanceof InsnNode) return Integer.toString(op);
        if (insn instanceof IntInsnNode n) return op + " " + n.operand;
        if (insn instanceof VarInsnNode n) return op + " " + n.var;
        if (insn instanceof TypeInsnNode n) return op + " " + n.desc;
        if (insn instanceof FieldInsnNode n) return op + " " + n.owner + "." + n.name + ":" + n.desc;
        if (insn instanceof MethodInsnNode n) return op + " " + n.owner + "." + n.name + n.desc + (n.itf ? " itf" : "");
        if (insn instanceof JumpInsnNode n) return op + " " + label(ids, n.label);
        if (insn instanceof LdcInsnNode n) return op + " " + constant(n.cst);
        if (insn instanceof IincInsnNode n) return op + " " + n.var + " " + n.incr;
        if (insn instanceof InvokeDynamicInsnNode n) {
            StringJoiner args = new StringJoiner(",");
            for (Object arg : n.bsmArgs) args.add(constant(arg));
            return op + " " + n.name + n.desc + " " + constant(n.bsm) + " [" + args + "]";
        }
        if (insn instanceof TableSwitchInsnNode n) {
            StringJoiner labels = new StringJoiner(",");
            for (LabelNode l : n.labels) labels.add(label(ids, l));
            return op + " " + n.min + ".." + n.max + " " + label(ids, n.dflt) + " [" + labels + "]";
        }
        if (insn instanceof LookupSwitchInsnNode n) {
            StringJoiner cases = new StringJoiner(",");
            for (int i = 0; i < n.keys.size(); i++) cases.add(n.keys.get(i) + "=" + label(ids, n.labels.get(i)));
            return op + " " + label(ids, n.dflt) + " [" + cases + "]";
        }
        if (insn instanceof MultiANewArrayInsnNode n) return op + " " + n.desc + " " + n.dims;
        throw new IllegalArgumentException("unexpected instruction node " + insn.getClass().getName());
    }

    private static String label(Map<LabelNode, Integer> ids, LabelNode label) {
        Integer id = ids.get(label);
        return id == null ? "L?" : "L" + id;
    }

    private static String constant(Object value) {
        if (value instanceof String s) return "s:" + s.replace("\\", "\\\\").replace("\n", "\\n");
        if (value instanceof Type t) return "t:" + t.getDescriptor();
        if (value instanceof Handle h) return "h:" + h.getTag() + ":" + h.getOwner() + "." + h.getName() + h.getDesc() + (h.isInterface() ? ":itf" : "");
        return value == null ? "null" : value.getClass().getSimpleName() + ":" + value;
    }

    public static String sha256(String text) {
        try {
            return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException(missing);
        }
    }

    private static String describe(List<MethodNode> methods) {
        StringJoiner out = new StringJoiner(", ");
        for (MethodNode method : methods) out.add(method.name + method.desc);
        return out.toString();
    }
}
