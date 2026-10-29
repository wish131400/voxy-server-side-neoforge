package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.testutil.VoxyMixinHarness;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

class VoxyTraversalCapacityMixinTest {
    @Test void actualBackendGetsCapacityHooksMutableHandlesAndShaderProtection() throws Exception {
        String config="""
            {"required":true,"minVersion":"0.8","package":"dev.xantha.vss.mixin",
             "plugin":"dev.xantha.vss.mixin.voxy.VoxyPredictionPlugin","compatibilityLevel":"JAVA_17",
             "client":["voxy.VoxyTraversalCapacityMixin","voxy.VoxyTraversalQueueShaderMixin"],"injectors":{"defaultRequire":1}}
            """;
        var harness=new VoxyMixinHarness(System.getProperty("vss.voxyJar"),config);
        assertTrue(dev.xantha.vss.mixin.voxy.VoxyPredictionPlugin.supportsCapacity(harness::getClassNode));
        var node=new ClassNode();new ClassReader(harness.apply("me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser")).accept(node,0);
        for(String field:java.util.List.of("idx2topNodeMapping","topNodeIds","scratchQueueA","scratchQueueB"))
            assertEquals(0,node.fields.stream().filter(f->f.name.equals(field)).findFirst().orElseThrow().access&Opcodes.ACC_FINAL,field);
        for(String method:java.util.List.of("beforeAdd","beforeRemove","changed","beforeTraversal"))
            assertTrue(node.methods.stream().flatMap(m->java.util.stream.StreamSupport.stream(m.instructions.spliterator(),false))
                    .anyMatch(i->i instanceof MethodInsnNode call&&call.owner.equals("dev/xantha/vss/compat/VoxyTraversalCapacity")&&call.name.equals(method)),method);
        for(var m:node.methods)new Analyzer<>(new BasicVerifier()).analyze(node.name,m);
        for(var m:node.methods)if(m.name.equals("<init>")) {
            long replacements=java.util.stream.StreamSupport.stream(m.instructions.spliterator(),false)
                    .filter(i->i instanceof MethodInsnNode call && (call.name.contains("vss$initialRootArray") || call.name.contains("vss$initialQueues"))).count();
            assertEquals(4,replacements,"All four allocations are planned in the original constructor");
        }
        executeNativeLifecycle(node);
        var shader=new ClassNode();new ClassReader(harness.apply("me.cortex.voxy.client.core.gl.shader.ShaderLoader")).accept(shader,0);
        assertTrue(shader.methods.stream().flatMap(m->java.util.stream.StreamSupport.stream(m.instructions.spliterator(),false))
                .anyMatch(i->i instanceof MethodInsnNode call&&call.owner.equals("dev/xantha/vss/compat/VoxyTraversalShader")));
    }

    /** Run transformed native add/rem methods past 200000 with real GL writes and live growth. */
    private static void executeNativeLifecycle(ClassNode original) throws Exception {
        assertTrue(org.lwjgl.glfw.GLFW.glfwInit());
        org.lwjgl.glfw.GLFW.glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_VISIBLE,0);
        long window=org.lwjgl.glfw.GLFW.glfwCreateWindow(16,16,"Native root lifecycle",0,0);assertNotEquals(0,window);
        long scratch=org.lwjgl.system.MemoryUtil.nmemAlloc(4);
        Object tree=null;Class<?> type=null;
        try {
            org.lwjgl.glfw.GLFW.glfwMakeContextCurrent(window);org.lwjgl.opengl.GL.createCapabilities();
            var fields=java.util.Set.of("topNodeCount","topNode2idxMapping","idx2topNodeMapping","topNodeIds","scratchQueueA","scratchQueueB","nodeManager","SCRATCH");
            original.fields.removeIf(f->!fields.contains(f.name)&&!f.name.contains("vss$capacity"));
            original.methods.removeIf(m->!m.name.equals("addTLN")&&!m.name.equals("remTLN")
                    &&!m.name.equals("vss$capacity")&&!java.util.stream.StreamSupport.stream(m.instructions.spliterator(),false)
                    .anyMatch(i->i instanceof MethodInsnNode call&&call.owner.equals("dev/xantha/vss/compat/VoxyTraversalCapacity")
                    && java.util.Set.of("beforeAdd","beforeRemove","changed").contains(call.name)));
            original.fields.forEach(f->f.access&=~Opcodes.ACC_FINAL);
            original.innerClasses.clear();original.nestMembers=null;original.nestHostClass=null;
            original.interfaces=java.util.List.of("dev/xantha/vss/compat/VoxyTraversalCapacity$Owner");
            String name="dev/xantha/vss/compat/NativeTraversalLifecycleProbe";
            var writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);
            original.accept(new ClassRemapper(writer,new SimpleRemapper(java.util.Map.of(original.name,name,
                    "me/cortex/voxy/client/core/gl/GlBuffer",VoxyTraversalQueueGpuTest.Buffer.class.getName().replace('.','/'),
                    "me/cortex/voxy/client/core/rendering/hierachical/AsyncNodeManager",VoxyTraversalCapacityTest.Nodes.class.getName().replace('.','/')))));
            var probe=new ClassNode();new ClassReader(writer.toByteArray()).accept(probe,0);
            // The Java 21 fixture's isolated int/GL lifecycle methods also execute on Forge's Java 17.
            probe.version=Opcodes.V17;
            MethodNode ctor=new MethodNode(Opcodes.ACC_PUBLIC,"<init>","()V",null,null);
            ctor.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));
            ctor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,"java/lang/Object","<init>","()V",false));
            ctor.instructions.add(new InsnNode(Opcodes.RETURN));probe.methods.add(ctor);
            ClassWriter output=new ClassWriter(ClassWriter.COMPUTE_MAXS);probe.accept(output);
            class Loader extends ClassLoader {Class<?> create(byte[] bytes){return defineClass(name.replace('/','.'),bytes,0,bytes.length);}}
            type=new Loader().create(output.toByteArray());tree=type.getConstructor().newInstance();
            VoxyTraversalCapacity.field(type,"SCRATCH").setLong(null,scratch);
            VoxyTraversalCapacity.field(type,"idx2topNodeMapping").set(tree,new int[200000]);
            for(String f:java.util.List.of("topNodeIds","scratchQueueA","scratchQueueB"))
                VoxyTraversalCapacity.field(type,f).set(tree,new VoxyTraversalQueueGpuTest.Buffer(800000));
            VoxyTraversalCapacity.field(type,"nodeManager").set(tree,new VoxyTraversalCapacityTest.Nodes());
            var ids=new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();ids.defaultReturnValue(-1);
            VoxyTraversalCapacity.field(type,"topNode2idxMapping").set(tree,ids);
            var manager=new VoxyTraversalCapacity(tree,VoxyCapacityPlan.create(16,0,8192,-64,2031));
            for(var f:type.getDeclaredFields())if(f.getType()==VoxyTraversalCapacity.class){f.setAccessible(true);f.set(tree,manager);}
            var add=type.getDeclaredMethod("addTLN",int.class);add.setAccessible(true);
            var remove=type.getDeclaredMethod("remTLN",int.class);remove.setAccessible(true);
            for(int i=0;i<200005;i++)add.invoke(tree,i);
            assertEquals(200005,ids.size());assertTrue(((int[])VoxyTraversalCapacity.field(type,"idx2topNodeMapping").get(tree)).length>200000);
            remove.invoke(tree,7);remove.invoke(tree,200003);add.invoke(tree,7);
            int count=VoxyTraversalCapacity.field(type,"topNodeCount").getInt(tree);assertEquals(200004,count);
            int[] gpu=new int[count];
            var buffer=(VoxyTraversalQueueGpuTest.Buffer)VoxyTraversalCapacity.field(type,"topNodeIds").get(tree);
            org.lwjgl.opengl.GL45.glGetNamedBufferSubData(buffer.id,0,gpu);
            int[] cpu=(int[])VoxyTraversalCapacity.field(type,"idx2topNodeMapping").get(tree);
            for(int i=0;i<count;i++){assertEquals(cpu[i],gpu[i]);assertEquals(i,ids.get(cpu[i]));}
            manager.beforeTraversal();assertEquals(200006L,manager.snapshot().get("addedRoots"));
            assertEquals(2L,manager.snapshot().get("removedRoots"));
        } finally {
            if(tree!=null)for(String f:java.util.List.of("topNodeIds","scratchQueueA","scratchQueueB")) {
                var buffer=(VoxyTraversalQueueGpuTest.Buffer)VoxyTraversalCapacity.field(type,f).get(tree);if(buffer!=null)buffer.free();
            }
            org.lwjgl.system.MemoryUtil.nmemFree(scratch);
            org.lwjgl.glfw.GLFW.glfwMakeContextCurrent(0);org.lwjgl.glfw.GLFW.glfwDestroyWindow(window);org.lwjgl.glfw.GLFW.glfwTerminate();
        }
    }
}
