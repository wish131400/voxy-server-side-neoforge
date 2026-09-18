package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Check external bytecode, not a local imitation of Voxy's method signatures. */
@EnabledIfSystemProperty(named="vss.voxyJar",matches=".+")
class StrictVoxyContractTest {
    @Test void uploadIngestAndBothDrawHooksMatchTheActualVoxyJar() throws Exception {
        try(ZipFile jar=new ZipFile(System.getProperty("vss.voxyJar"))) {
            ClassNode async=read(jar,"client/core/rendering/hierachical/AsyncNodeManager");
            assertEquals(2,calls(method(async,"tick"),"java/lang/invoke/VarHandle","compareAndSet",
                    "(Lme/cortex/voxy/client/core/rendering/hierachical/AsyncNodeManager;Ljava/lang/Void;Lme/cortex/voxy/client/core/rendering/hierachical/AsyncNodeManager$SyncResults;)Z"));
            assertEquals(1,calls(method(async,"run"),"java/util/concurrent/atomic/AtomicInteger","addAndGet","(I)I"));
            ClassNode draw=read(jar,"client/core/rendering/section/backend/mdic/MDICSectionRenderer");
            for(String name:new String[]{"renderTerrain","renderTranslucent"}) assertEquals(1,calls(method(draw,name),
                    "org/lwjgl/opengl/ARBIndirectParameters","glMultiDrawElementsIndirectCountARB","(IIJJII)V"));
            ClassNode ingest=read(jar,"common/world/service/VoxelIngestService");
            ClassNode mesh=read(jar,"client/core/rendering/building/RenderGenerationService");
            assertEquals(1,calls(method(mesh,"enqueueTask"),"java/util/concurrent/PriorityBlockingQueue","add","(Ljava/lang/Object;)Z"));
            assertEquals(1,calls(method(mesh,"processJob"),"java/util/concurrent/PriorityBlockingQueue","poll","()Ljava/lang/Object;"));
            assertEquals(2,calls(method(mesh,"processJob"),"java/util/function/Consumer","accept","(Ljava/lang/Object;)V"));
            assertEquals(4,calls(method(async,"run"),"java/util/concurrent/ConcurrentLinkedDeque","poll","()Ljava/lang/Object;"));
            assertEquals(1,calls(method(async,"run"),"java/lang/invoke/VarHandle","compareAndSet",
                    "(Lme/cortex/voxy/client/core/rendering/hierachical/AsyncNodeManager;Ljava/lang/Void;Lme/cortex/voxy/client/core/rendering/hierachical/AsyncNodeManager$SyncResults;)Z"));
            assertEquals(1,calls(method(ingest,"processJob"),"java/util/concurrent/ConcurrentLinkedDeque","pop","()Ljava/lang/Object;"));
            ClassNode result=read(jar,"client/core/rendering/hierachical/AsyncNodeManager$SyncResults");
            assertTrue(result.fields.stream().anyMatch(f->f.name.equals("scatterWriteLocationMap")));
            assertTrue(result.fields.stream().anyMatch(f->f.name.equals("scatterWriteBuffer")));
            ClassNode executor=read(jar,"common/thread/PerThreadContextExecutor");
            assertTrue(executor.fields.stream().anyMatch(f->f.name.equals("currentRunning")&&f.desc.equals("Ljava/util/concurrent/atomic/AtomicInteger;")));
        }
    }
    private static ClassNode read(ZipFile jar,String name) throws Exception {
        var entry=jar.getEntry("me/cortex/voxy/"+name+".class");assertNotNull(entry);
        ClassNode node=new ClassNode();new ClassReader(jar.getInputStream(entry).readAllBytes()).accept(node,0);return node;
    }
    private static MethodNode method(ClassNode node,String name) {return node.methods.stream().filter(m->m.name.equals(name)).findFirst().orElseThrow();}
    private static int calls(MethodNode method,String owner,String name,String descriptor) {
        int count=0;for(var instruction:method.instructions) if(instruction instanceof MethodInsnNode call
                &&call.owner.equals(owner)&&call.name.equals(name)&&call.desc.equals(descriptor)) count++;
        return count;
    }
}
