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

/** Apply retained frame/depth hooks to executable Voxy classes, including optional injection sites. */
class VoxyRenderPipelineMixinApplicationTest {
    @Test void normalAndIrisPipelinesHaveEveryRetainedObserverAndDepthRestore() throws Exception {
        var config="""
            {"required":true,"minVersion":"0.8","package":"dev.xantha.vss.mixin",
             "compatibilityLevel":"JAVA_17","client":["voxy.NormalRenderPipelineDepthMaskMixin",
             "voxy.StrictVoxyVisibilityMixin","voxy.IrisVoxyRenderPipelineDepthMaskMixin"],
             "injectors":{"defaultRequire":1}}
            """;
        var harness=new VoxyMixinHarness(System.getProperty("vss.voxyJar"),config);
        var render=new ClassNode();
        new ClassReader(harness.apply("me.cortex.voxy.client.core.VoxyRenderSystem")).accept(render,0);
        var opaque=render.methods.stream().filter(m->m.name.equals("renderOpaque")).findFirst().orElseThrow();
        assertCall(opaque,"vss$strictFrame");
        var common=render.methods.stream().filter(m->m.name.contains("vss$strictFrame")).findFirst().orElseThrow();
        assertCall(common,"observeUnifiedView");
        assertAuxiliaryViewportsIgnored(common);
        for (String pipeline : new String[]{"NormalRenderPipeline","IrisVoxyRenderPipeline"}) {
            var node=new ClassNode();
            new ClassReader(harness.apply("me.cortex.voxy.client.core."+pipeline)).accept(node,0);
            var observe=node.methods.stream().filter(m->m.name.equals("postOpaquePreTranslucent")).findFirst().orElseThrow();
            var finish=node.methods.stream().filter(m->m.name.equals("finish")).findFirst().orElseThrow();
            if(pipeline.equals("NormalRenderPipeline")) {
                assertNoObserver(node);
                assertCall(finish,"vss$enableDepthWrites");
                assertCall(finish,"vss$bindFog");
                assertCall(finish,"vss$restoreDepthWrites");
            } else {
                assertNoObserver(node);
                assertCall(finish,"vss$enableDepthWritesForFinalBlit");
                assertCall(finish,"vss$restoreDepthWritesAfterFinalBlit");
            }
            for(var method:node.methods) new Analyzer<>(new BasicVerifier()).analyze(node.name,method);
        }
    }
    /** Execute the transformed hook; merely finding its call sites missed Iris's null shadow pass. */
    private void assertAuxiliaryViewportsIgnored(MethodNode common) throws Exception {
        String target="VssExecutableFrameHook";
        var writer=new ClassWriter(0);
        writer.visit(Opcodes.V17,Opcodes.ACC_PUBLIC,target,null,"java/lang/Object",null);
        var constructor=writer.visitMethod(Opcodes.ACC_PUBLIC,"<init>","()V",null,null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD,0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL,"java/lang/Object","<init>","()V",false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1,1);constructor.visitEnd();
        common.access=Opcodes.ACC_PUBLIC;
        common.name="frame";
        common.accept(writer);writer.visitEnd();
        Class<?> executable=new ClassLoader(getClass().getClassLoader()) {
            Class<?> define(byte[] bytes) { return defineClass(target,bytes,0,bytes.length); }
        }.define(writer.toByteArray());
        var hook=executable.getMethod("frame",Object.class,org.spongepowered.asm.mixin.injection.callback.CallbackInfo.class);
        var instance=executable.getConstructor().newInstance();
        var observer=dev.xantha.vss.client.prediction.PredictionRenderer.class.getDeclaredField("observations");
        observer.setAccessible(true);
        var observations=observer.get(null);
        var observe=observations.getClass().getDeclaredMethod("observe",org.joml.Matrix4f.class,
                org.joml.Matrix4f.class,net.minecraft.world.phys.Vec3.class,int.class,boolean.class,boolean.class,long.class);
        var current=observations.getClass().getDeclaredMethod("current");
        var reset=observations.getClass().getDeclaredMethod("reset");
        observe.setAccessible(true);current.setAccessible(true);reset.setAccessible(true);
        var pending=StrictLodVisibility.class.getDeclaredField("invalidated");pending.setAccessible(true);
        var invalidated=(java.util.concurrent.atomic.AtomicBoolean)pending.get(null);
        boolean wasInvalidated=invalidated.get();
        try {
            // Repeat around observation resets, as on Overworld -> Nether -> Overworld transitions.
            for(int dimension=0;dimension<3;dimension++) {
                reset.invoke(observations);
                assertNull(current.invoke(observations));
                for(Object viewport:auxiliaryViewports())
                    assertDoesNotThrow(()->hook.invoke(instance,viewport,null),"Empty view during world startup");
                assertNull(current.invoke(observations));
                var projection=new org.joml.Matrix4f().perspective((float)Math.toRadians(10),2,.1f,1000);
                var main=observe.invoke(observations,projection,new org.joml.Matrix4f(),
                        net.minecraft.world.phys.Vec3.ZERO,540,true,true,System.nanoTime());
                assertNotNull(main);
                var coverage=StrictLodVisibility.snapshot();
                invalidated.set(true);
                for(Object viewport:auxiliaryViewports()) {
                    assertDoesNotThrow(()->hook.invoke(instance,viewport,null),"Iris auxiliary view must be skipped before client or matrix access");
                    assertSame(main,current.invoke(observations),"Keep the last drawable projection");
                    assertSame(coverage,StrictLodVisibility.snapshot(),"Keep the main view's coverage");
                    assertTrue(invalidated.get(),"Keep pending main-view restart");
                }
            }
        } finally {
            reset.invoke(observations);invalidated.set(wasInvalidated);
        }
    }
    private static Object[] auxiliaryViewports() {
        return new Object[]{null,new EmptyViewport(0,540),new EmptyViewport(960,0),
                new EmptyViewport(-1,540),new EmptyViewport(960,-1),new EmptyViewport(0,0)};
    }
    // Deliberately no matrices/camera: an empty viewport must never read them.
    public record EmptyViewport(int width,int height) { }
    private static void assertNoObserver(ClassNode node) {
        for (var method:node.methods) for(var instruction:method.instructions)
            if(instruction instanceof MethodInsnNode call) assertNotEquals("observeUnifiedView",call.name);
    }
    private static void assertCall(MethodNode method,String name) {
        long matches=java.util.stream.StreamSupport.stream(method.instructions.spliterator(),false)
                .filter(i->i instanceof MethodInsnNode call && call.name.contains(name)).count();
        assertEquals(1,matches,method.name+method.desc+" must apply "+name+" exactly once");
    }
}
