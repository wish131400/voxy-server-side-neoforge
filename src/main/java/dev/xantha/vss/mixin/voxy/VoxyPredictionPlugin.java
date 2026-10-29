package dev.xantha.vss.mixin.voxy;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

/** Selects the render overlay as one unit before any of its target classes are loaded. */
public final class VoxyPredictionPlugin implements IMixinConfigPlugin {
    private Boolean overlay;
    private Boolean capacity;
    private static final String ROOT = "me/cortex/voxy/";
    private static final String SECTION = "L" + ROOT + "common/world/WorldSection;";

    @FunctionalInterface public interface Classes { ClassNode get(String name) throws Exception; }

    public static boolean supports(Classes classes) throws Exception {
        ClassNode sections = classes.get(ROOT + "common/world/WorldSection");
        boolean data = method(sections, "_unsafeGetRawDataArray", "()[J")
                || method(sections, "_rawOrNull", "()[J") && method(sections, "getUniformValue", "()J")
                && method(sections, "materialize", "()[J");
        if (!data || !method(sections, "_createRawUntrackedUnsafeSection", "(IIII)" + SECTION)) return false;
        ClassNode render = classes.get(ROOT + "client/core/VoxyRenderSystem");
        if (!method(render, "setRenderDistance", "(F)V") && !method(render, "setRenderDistance", "(I)V")) return false;
        ClassNode mesh = classes.get(ROOT + "client/core/rendering/building/RenderGenerationService");
        if (!method(mesh, "acquireSection", "(J)" + SECTION)
                || !method(mesh, "setResultConsumer", "(Ljava/util/function/Consumer;)V")
                || !calls(mesh, "processJob", ROOT + "client/core/rendering/building/RenderDataFactory",
                        "generateMesh", "(" + SECTION + ")L" + ROOT + "client/core/rendering/building/BuiltSection;")) return false;
        ClassNode factory = classes.get(ROOT + "client/core/rendering/building/RenderDataFactory");
        // Liquid remainders must enter before merging; never enable a partial mesher hook.
        if(!method(factory,"prepareSectionData","([J)I") || !method(factory,"packPartialQuadData","(IJJ)J")
                || !method(factory,"generateXFaces","()V"))return false;
        int acquire = 0, exists = 0;
        for (var m : factory.methods) if (m.name.equals("acquireNeighborData"))
            for (var i : m.instructions) if (i instanceof MethodInsnNode call
                    && call.owner.equals(ROOT + "common/world/WorldEngine") && call.desc.equals("(IIII)" + SECTION)) {
                if (call.name.equals("acquire")) acquire++;
                if (call.name.equals("acquireIfExists")) exists++;
            }
        if (!(acquire == 6 && exists == 0 || exists == 6 && acquire == 0)) return false;
        ClassNode nodes = classes.get(ROOT + "client/core/rendering/hierachical/NodeManager");
        return method(nodes, "processRequest", "(J)V") && method(nodes, "makeLeafChildRequest", "(I)V")
                && method(classes.get(ROOT + "client/core/gl/shader/ShaderLoader"), "parse", "(Ljava/lang/String;)Ljava/lang/String;")
                && method(nodes, "invalidateNode", "(I)V")
                && method(nodes, "writeNode", "(IJ)V") && method(nodes, "processChildChange", "(JB)V")
                && method(nodes, "_recurseRemoveNode", "(JZ)V")
                && method(classes.get(ROOT + "client/core/rendering/hierachical/AsyncNodeManager"),
                        "submitRequestBatch", "(L" + ROOT + "common/util/MemoryBuffer;)V")
                && calls(classes.get(ROOT + "client/core/rendering/hierachical/AsyncNodeManager"),
                        "worldEvent", ROOT + "client/core/rendering/SectionUpdateRouter", "forwardEvent", "(" + SECTION + "I)V");
    }
    private static boolean calls(ClassNode node, String method, String owner, String name, String desc) {
        for (var m : node.methods) if (m.name.equals(method))
            for (var i : m.instructions) if (i instanceof MethodInsnNode c
                    && c.owner.equals(owner) && c.name.equals(name) && c.desc.equals(desc)) return true;
        return false;
    }
    private static boolean method(ClassNode node, String name, String desc) {
        return node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc));
    }
    @Override public boolean shouldApplyMixin(String target, String mixin) {
        if (mixin.endsWith(".VoxyTraversalCapacityMixin") || mixin.endsWith(".VoxyTraversalQueueShaderMixin")) {
            if (capacity == null) {
                try { capacity = supportsCapacity(name -> MixinService.getService().getBytecodeProvider().getClassNode(name.replace('/', '.'))); }
                catch (Exception missing) { capacity = false; }
            }
            return capacity;
        }
        if (mixin.endsWith(".VoxyRenderDistanceMixin")) {
            try {
                ClassNode render = MixinService.getService().getBytecodeProvider().getClassNode(target);
                if (method(render, "currentFarPlane", "()F") || method(render, "getLodFarPlane", "()F")) return true;
                return render.methods.stream().filter(m -> m.name.equals("computeProjectionMat"))
                        .flatMap(m -> java.util.stream.StreamSupport.stream(m.instructions.spliterator(), false))
                        .anyMatch(i -> i instanceof LdcInsnNode ldc && Float.valueOf(48000F).equals(ldc.cst));
            } catch (Exception unavailable) { return false; }
        }
        if (!mixin.substring(mixin.lastIndexOf('.') + 1).startsWith("PredictionVoxy")) return true;
        if (overlay == null) {
            try { overlay = supports(name -> MixinService.getService().getBytecodeProvider().getClassNode(name.replace('/', '.'))); }
            catch (Exception missing) { overlay = false; }
        }
        return overlay;
    }
    public static boolean supportsCapacity(Classes classes) throws Exception {
        ClassNode node = classes.get(ROOT + "client/core/rendering/hierachical/HierarchicalOcclusionTraverser");
        String buffer = "L" + ROOT + "client/core/gl/GlBuffer;";
        return node.fields.stream().anyMatch(f -> f.name.equals("idx2topNodeMapping") && f.desc.equals("[I"))
                && node.fields.stream().anyMatch(f -> f.name.equals("topNodeCount") && f.desc.equals("I"))
                && node.fields.stream().anyMatch(f -> f.name.equals("topNode2idxMapping")
                        && f.desc.equals("Lit/unimi/dsi/fastutil/ints/Int2IntOpenHashMap;"))
                && node.fields.stream().anyMatch(f -> f.name.equals("nodeManager")
                        && f.desc.equals("L" + ROOT + "client/core/rendering/hierachical/AsyncNodeManager;"))
                && java.util.stream.Stream.of("topNodeIds", "scratchQueueA", "scratchQueueB").allMatch(
                        name -> node.fields.stream().anyMatch(f -> f.name.equals(name) && f.desc.equals(buffer)))
                && constants(node, Integer.valueOf(200000)) == 1 && constants(node, Long.valueOf(800000)) == 3
                && classes.get(ROOT + "client/core/VoxyRenderSystem").fields.stream().anyMatch(f -> f.name.equals("traversal")
                        && f.desc.equals("L" + ROOT + "client/core/rendering/hierachical/HierarchicalOcclusionTraverser;"))
                && method(node, "addTLN", "(I)V") && method(node, "remTLN", "(I)V")
                && method(node, "traverseInternal", "()V")
                && method(classes.get(ROOT + "client/core/rendering/hierachical/AsyncNodeManager"), "getCurrentMaxNodeId", "()I")
                && method(classes.get(ROOT + "client/core/gl/shader/ShaderLoader"), "parse", "(Ljava/lang/String;)Ljava/lang/String;");
    }
    private static long constants(ClassNode node, Object value) {
        return node.methods.stream().filter(m -> m.name.equals("<init>"))
                .flatMap(m -> java.util.stream.StreamSupport.stream(m.instructions.spliterator(), false))
                .filter(i -> i instanceof LdcInsnNode c && value.equals(c.cst)).count();
    }
    @Override public void onLoad(String mixinPackage) { }
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> mine, Set<String> others) { }
    @Override public List<String> getMixins() { return null; }
    /** Only this optional fast path requires an exact verified plane-reader contract. */
    public static String faceLayoutFingerprint(ClassNode node) {
        var method=node.methods.stream().filter(m->m.name.equals("acquireNeighborData")).findFirst().orElseThrow();
        var labels=new java.util.IdentityHashMap<LabelNode,Integer>();int label=0;
        for(var i:method.instructions)if(i instanceof LabelNode l)labels.put(l,label++);
        var text=new StringBuilder();
        for(var i:method.instructions) {
            if(i.getOpcode()<0)continue;
            text.append(i.getOpcode()).append(':');
            if(i instanceof VarInsnNode v)text.append(v.var);
            else if(i instanceof IntInsnNode v)text.append(v.operand);
            else if(i instanceof IincInsnNode v)text.append(v.var).append(',').append(v.incr);
            else if(i instanceof FieldInsnNode v)text.append(v.owner).append('.').append(v.name).append(v.desc);
            else if(i instanceof MethodInsnNode v)text.append(v.owner).append('.').append(v.name).append(v.desc);
            else if(i instanceof JumpInsnNode v)text.append(labels.get(v.label));
            else if(i instanceof LdcInsnNode v)text.append(v.cst);
            else if(i instanceof TypeInsnNode v)text.append(v.desc);
            else if(i instanceof TableSwitchInsnNode || i instanceof LookupSwitchInsnNode || i instanceof InvokeDynamicInsnNode)
                return "unsupported";
            text.append(';');
        }
        try {return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible) {throw new AssertionError(impossible);}
    }
    public static boolean supportsFaceLayout(ClassNode node) {
        return node.methods.stream().anyMatch(m->m.name.equals("acquireNeighborData") && m.desc.endsWith(")V"))
                && VERIFIED_FACE_READERS.contains(faceLayoutFingerprint(node));
    }
    private static final Set<String> VERIFIED_FACE_READERS=Set.of(
            "3b02a023d767e48f34a9d3fef4df5493bd6818be986676f758cb6e49e75ee1dc",
            "3657673b4c1f290b56d712a4f0056986935f6eebb336afc15d2f5cec7bcd33e2",
            "9d57c81f5eff892ce8b76caf4bc5bf9e0492a8d194fba0f3ab8c4575d334aa1c",
            "a0a4bb3e4bbf46c757a902d3878cc2ff70390712f9cca900d0459796e67717f5",
            "972e02c5767f0cd86d67ea269a9154dcaa6529ce22a42348f445bdab86c496b6",
            "e525a19803d0589d9c2a7c03d7e797e67b0bd4489d063600801cffc8e812790e",
            "37eedd25cbbb473b74d026e99bf657688d1ba77202e69df4edb00d974cc3b039");
    @Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {
        if(mixin.endsWith(".PredictionVoxyNeighborSourceMixin"))
            dev.xantha.vss.compat.VoxyFaceData.nativeLayoutVerified=supportsFaceLayout(node);
    }
    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {
        if (mixin.endsWith(".VoxyTraversalCapacityMixin")) {
            // Mutable resource handles are updated together on the render thread during growth.
            var handles = Set.of("idx2topNodeMapping", "topNodeIds", "scratchQueueA", "scratchQueueB");
            node.fields.stream().filter(f -> handles.contains(f.name)).forEach(f -> f.access &= ~org.objectweb.asm.Opcodes.ACC_FINAL);
        }
    }
}
