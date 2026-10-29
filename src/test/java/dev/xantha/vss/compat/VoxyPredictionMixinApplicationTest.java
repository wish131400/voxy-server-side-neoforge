package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.testutil.VoxyMixinHarness;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

class VoxyPredictionMixinApplicationTest {
    @Test void coordinatesAndChildOctantsMatchExecutableMethodsFromTheInstalledVoxyJar() throws Exception {
        var checks=new java.util.HashMap<String,java.lang.reflect.Method>();
        try(var jar=new java.util.zip.ZipFile(System.getProperty("vss.voxyJar"))) {
            for(String name:new String[]{"WorldEngine","WorldSection"}) {
                var node=new ClassNode();
                try(var input=jar.getInputStream(jar.getEntry("me/cortex/voxy/common/world/"+name+".class"))) {
                    new ClassReader(input).accept(node,0);
                }
                String owner=node.name, target="VssVoxy"+name+"Contract";
                var writer=new ClassWriter(0);
                writer.visit(Opcodes.V17,Opcodes.ACC_PUBLIC,target,null,"java/lang/Object",null);
                writer.visitField(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"VERIFY_WORLD_SECTION_EXECUTION","Z",null,null).visitEnd();
                var names=java.util.Set.of("getWorldSectionId","getLevel","getX","getY","getZ","getIndex","getChildIndex");
                for(var method:node.methods) if(names.contains(method.name)) {
                    for(var insn:method.instructions) {
                        if(insn instanceof FieldInsnNode field && field.owner.equals(owner)) field.owner=target;
                        if(insn instanceof MethodInsnNode call && call.owner.equals(owner)) call.owner=target;
                    }
                    method.accept(writer);
                }
                writer.visitEnd();
                Class<?> contract=new ClassLoader(getClass().getClassLoader()) {
                    Class<?> contract(byte[] bytes) { return defineClass(target,bytes,0,bytes.length); }
                }.contract(writer.toByteArray());
                if (name.equals("WorldEngine")) VoxySectionPosition.bind(contract);
                for(var method:contract.getMethods()) if(names.contains(method.getName()))checks.put(method.getName(),method);
            }
        }
        for(int lod=0;lod<=4;lod++) for(int sy:new int[]{-2048,-129,-128,-4,-1,0,1,9,127,128,300,2047}) {
            long pos=VoxyPredictionBridge.position(lod,-123,sy,789);
            assertEquals(pos,checks.get("getWorldSectionId").invoke(null,lod,-123,sy,789));
            assertEquals(lod,checks.get("getLevel").invoke(null,pos));
            assertEquals(-123,checks.get("getX").invoke(null,pos));
            assertEquals(VoxySectionPosition.extended() ? sy : (byte) sy,checks.get("getY").invoke(null,pos));
            assertEquals(checks.get("getY").invoke(null,pos), VoxySectionPosition.y(pos));
            assertEquals(789,checks.get("getZ").invoke(null,pos));
        }
        for(int octant=0;octant<8;octant++) {
            int x=(octant&1)*16,y=(octant>>2)*16,z=(octant>>1&1)*16;
            int index=(Integer)checks.get("getIndex").invoke(null,x,y,z);
            assertEquals(y<<10|z<<5|x,index);
            var data=new long[32768];data[index]=1L<<27;
            int child=(Integer)checks.get("getChildIndex").invoke(null,x>>4,y>>4,z>>4);
            assertEquals((byte)(1<<child),VoxyPredictionBridge.children(data,4));
        }
    }
    @Test void actualVoxyMeshAndAllSixNeighborAcquisitionsUseTheRenderOverlay() throws Exception {
        var config = """
            {"required":true,"minVersion":"0.8","package":"dev.xantha.vss.mixin",
             "plugin":"dev.xantha.vss.mixin.voxy.VoxyPredictionPlugin",
             "compatibilityLevel":"JAVA_17","client":["voxy.PredictionVoxyMeshSourceMixin",
             "voxy.PredictionVoxyNeighborSourceMixin","voxy.PredictionVoxyWorldEventMixin","voxy.PredictionVoxyDetailMixin","voxy.PredictionVoxyTraversalShaderMixin",
             "voxy.StrictVoxyMeshWorkMixin","voxy.StrictVoxyVisibilityMixin","voxy.StrictVoxyUploadMixin",
             "voxy.StrictVoxyBatchMixin","voxy.StrictVoxyMeshMixin","voxy.VoxyRenderDistanceMixin",
             "voxy.VoxyTraversalCapacityMixin","voxy.VoxyTraversalQueueShaderMixin"],"injectors":{"defaultRequire":1}}
            """;
        var harness = new VoxyMixinHarness(System.getProperty("vss.voxyJar"), config);
        assertTrue(dev.xantha.vss.mixin.voxy.VoxyPredictionPlugin.supports(name -> harness.getClassNode(name)), "Native render contract");
        var shaderLoader=new ClassNode();
        new ClassReader(harness.apply("me.cortex.voxy.client.core.gl.shader.ShaderLoader")).accept(shaderLoader,0);
        assertTrue(shaderLoader.methods.stream().flatMap(m->java.util.stream.StreamSupport.stream(m.instructions.spliterator(),false))
                .anyMatch(i->i instanceof MethodInsnNode call && call.owner.equals("dev/xantha/vss/compat/VoxyPredictionBridge") && call.name.equals("patchSubdivisionRequests")));
        assertTrue(shaderLoader.methods.stream().flatMap(m->java.util.stream.StreamSupport.stream(m.instructions.spliterator(),false))
                .anyMatch(i->i instanceof MethodInsnNode call && call.owner.equals("dev/xantha/vss/compat/VoxyTraversalShader")));
        var traversal=new ClassNode();
        new ClassReader(harness.apply("me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser")).accept(traversal,0);
        for(MethodNode method:traversal.methods)new Analyzer<>(new BasicVerifier()).analyze(traversal.name,method);
        for (String name : new String[]{"RenderGenerationService","RenderDataFactory","../hierachical/AsyncNodeManager","../hierachical/NodeManager","VoxyRenderSystem","BuiltSection","../hierachical/AsyncNodeManager$SyncResults"}) {
            var node = new ClassNode();
            String target = name.equals("VoxyRenderSystem") ? "me.cortex.voxy.client.core.VoxyRenderSystem"
                    : name.startsWith("../") ? "me.cortex.voxy.client.core.rendering.hierachical."+name.substring(name.lastIndexOf('/')+1)
                    : "me.cortex.voxy.client.core.rendering.building."+name;
            new ClassReader(harness.apply(target)).accept(node,0);
            if (name.equals("VoxyRenderSystem")) {
                assertTrue(node.methods.stream().flatMap(m -> java.util.stream.StreamSupport.stream(m.instructions.spliterator(), false))
                        .anyMatch(i -> i instanceof MethodInsnNode call && call.owner.equals("dev/xantha/vss/compat/VoxyPredictionBridge")
                                && call.name.equals("farPlane")), "Actual renderer projection must expand beyond the 48000-block legacy clip");
            }
            if (!name.equals("BuiltSection") && !name.endsWith("$SyncResults")) assertTrue(node.methods.stream().flatMap(m -> java.util.stream.StreamSupport.stream(m.instructions.spliterator(),false))
                    .anyMatch(i -> i instanceof MethodInsnNode call && call.owner.equals("dev/xantha/vss/compat/VoxyPredictionBridge")));
            if(name.equals("RenderDataFactory")) {
                boolean nativeFluid=node.methods.stream().anyMatch(m->m.name.equals("applyBiomeBlend"));
                try(var z=new java.util.zip.ZipFile(System.getProperty("vss.voxyJar"))) {
                    String shader=new String(z.getInputStream(z.getEntry("assets/voxy/shaders/lod/quad_util.glsl")).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
                    assertEquals(shader.contains("resolveFluidTopIndentation("),nativeFluid,"Shader and mesher must agree on ownership of fluid payload");
                }
                var method = node.methods.stream().filter(m->m.name.equals("acquireNeighborData")).findFirst().orElseThrow();
                long calls=java.util.stream.StreamSupport.stream(method.instructions.spliterator(),false)
                        .filter(i->i instanceof MethodInsnNode c && c.name.contains("vss$predictionNeighbor")).count();
                assertEquals(6,calls);
                for(String helper:java.util.List.of("pack","meshTransitions","legacyMesher"))
                    assertTrue(node.methods.stream().flatMap(m->java.util.stream.StreamSupport.stream(m.instructions.spliterator(),false))
                        .anyMatch(i->i instanceof MethodInsnNode c && c.owner.equals("dev/xantha/vss/compat/VoxyFluidSurface") && c.name.equals(helper)),helper);
            }
            if(name.equals("../hierachical/NodeManager")) {
                var store=node.fields.stream().filter(f->f.name.equals("nodeData")).findFirst().orElseThrow();
                var storeNode=harness.getClassNode(org.objectweb.asm.Type.getType(store.desc).getClassName());
                assertTrue(storeNode.methods.stream().anyMatch(m->m.name.equals("getNodeChildExistence") && m.desc.equals("(I)B")));
                assertTrue(node.fields.stream().anyMatch(f->f.name.equals("topLevelNodes")),"Native empty-root exception must remain available");
                for(String bridge:java.util.List.of("requestDetail","allowSubdivision","writeSubdivisionState","resumeSubdivision","forgetSubdivision"))
                    assertTrue(node.methods.stream().flatMap(m->java.util.stream.StreamSupport.stream(m.instructions.spliterator(),false))
                            .anyMatch(i->i instanceof MethodInsnNode c && c.owner.equals("dev/xantha/vss/compat/VoxyPredictionBridge") && c.name.equals(bridge)),bridge);
            }
            for(MethodNode method:node.methods) new Analyzer<>(new BasicVerifier()).analyze(node.name,method);
        }
    }
    @Test void shaderWaitGuardChangesOnlyRequestSubmissionAndLeavesNativeCleanerSemanticsIntact() throws Exception {
        try(var jar=new java.util.zip.ZipFile(System.getProperty("vss.voxyJar"))) {
            String path="voxy:lod/hierarchical/traversal_dev.comp";
            String original=new String(jar.getInputStream(jar.getEntry("assets/voxy/shaders/lod/hierarchical/traversal_dev.comp")).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            String patched=VoxyPredictionBridge.patchSubdivisionRequests(path,original);
            String guard="\n    // VSS deferred subdivision\n    if ((node.flags & 2u) != 0u) return;\n";
            assertNotEquals(original,patched);assertEquals(original,patched.replace(guard,""));
            assertEquals(patched,VoxyPredictionBridge.patchSubdivisionRequests(path,patched));
            assertEquals(original,VoxyPredictionBridge.patchSubdivisionRequests("voxy:lod/hierarchical/cleaner/sort_visibility.comp",original));
            String node=new String(jar.getInputStream(jar.getEntry("assets/voxy/shaders/lod/hierarchical/node.glsl")).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            node=node.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
            assertTrue(node.contains("(node.flags&1u) != 0u"),"Native hasRequested must continue to use only bit 0");
            assertFalse(node.contains("node.flags&2u"),"Waiting bit must be unused in the supported native node ABI");
        }
    }
}
