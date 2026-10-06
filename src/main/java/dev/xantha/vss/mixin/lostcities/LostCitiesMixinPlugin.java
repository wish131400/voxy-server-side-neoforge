package dev.xantha.vss.mixin.lostcities;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

/** Leaves absent mods and the newer ConcurrentMap/pinned-value caches untouched. */
public final class LostCitiesMixinPlugin implements IMixinConfigPlugin {
    private static final String CACHE = "mcjty.lostcities.varia.TimedCache";
    private static final String GET = "(Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String PUT = "(Ljava/lang/Object;Ljava/lang/Object;)V";
    private static final String COMPUTE = "(Ljava/lang/Object;Ljava/util/function/Function;)Ljava/lang/Object;";

    static boolean legacyCache(ClassNode node) {
        return node.fields.stream().anyMatch(f -> f.name.equals("cache") && f.desc.equals("Ljava/util/Map;"))
                && node.fields.stream().anyMatch(f -> f.name.equals("nextCleanupAt") && f.desc.equals("J"))
                && method(node, "clear", "()V") != null && method(node, "get", GET) != null
                && method(node, "put", PUT) != null && method(node, "computeIfAbsent", COMPUTE) != null
                && method(node, "maybeCleanup", "(J)V") != null && method(node, "cleanup", "(J)V") != null;
    }

    static boolean protectLegacyCache(ClassNode node) {
        if (!legacyCache(node)) return false;
        for (String[] signature : new String[][]{{"clear", "()V"}, {"get", GET}, {"put", PUT},
                {"maybeCleanup", "(J)V"}, {"cleanup", "(J)V"}})
            method(node, signature[0], signature[1]).access |= Opcodes.ACC_SYNCHRONIZED;

        // A factory can re-enter BuildingInfo or other caches. Never hold the
        // cache monitor while it runs: generation acquires those locks first.
        MethodNode compute = method(node, "computeIfAbsent", COMPUTE);
        compute.access &= ~Opcodes.ACC_SYNCHRONIZED;
        compute.instructions.clear();
        compute.tryCatchBlocks.clear();
        if (compute.localVariables != null) compute.localVariables.clear();
        compute.visibleLocalVariableAnnotations = null;
        compute.invisibleLocalVariableAnnotations = null;
        LabelNode missing = new LabelNode(), done = new LabelNode();
        var code = compute.instructions;
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, "get", GET, false));
        code.add(new VarInsnNode(Opcodes.ASTORE, 3));
        code.add(new VarInsnNode(Opcodes.ALOAD, 3));
        code.add(new JumpInsnNode(Opcodes.IFNULL, missing));
        code.add(new VarInsnNode(Opcodes.ALOAD, 3));
        code.add(new InsnNode(Opcodes.ARETURN));
        code.add(missing);
        code.add(new FrameNode(Opcodes.F_APPEND, 1, new Object[]{"java/lang/Object"}, 0, null));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/function/Function", "apply", GET, true));
        code.add(new VarInsnNode(Opcodes.ASTORE, 3));
        code.add(new VarInsnNode(Opcodes.ALOAD, 3));
        code.add(new JumpInsnNode(Opcodes.IFNULL, done));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new VarInsnNode(Opcodes.ALOAD, 3));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, "put", PUT, false));
        code.add(done);
        code.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        code.add(new VarInsnNode(Opcodes.ALOAD, 3));
        code.add(new InsnNode(Opcodes.ARETURN));
        compute.maxStack = 3;
        compute.maxLocals = 4;
        return true;
    }

    private static MethodNode method(ClassNode node, String name, String desc) {
        return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst().orElse(null);
    }

    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!CACHE.equals(targetClassName)) return false;
        try {
            return legacyCache(MixinService.getService().getBytecodeProvider().getClassNode(targetClassName, false));
        } catch (ClassNotFoundException | java.io.IOException absent) {
            return false;
        }
    }

    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo info) {
        if (CACHE.equals(targetClassName)) protectLegacyCache(targetClass);
    }

    @Override public void onLoad(String mixinPackage) { }
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> mine, Set<String> others) { }
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo info) { }
}
