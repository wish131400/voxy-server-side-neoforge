package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.mixin.voxy.StrictVoxyIngestPlugin;
import java.util.*;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

class VoxyIngestWriteContractTest {
    @Test void maintainedNativeWritersCertifyEverySuccessfulInsertAndKeepNativeCallsInOrder() throws Exception {
        String paths=System.getProperty("vss.coverageVoxyJars");
        if(paths==null)paths=Objects.requireNonNull(System.getProperty("vss.voxyJar"));
        for(String path:paths.split("[|]"))try(ZipFile zip=new ZipFile(path)) {
            ClassNode node=new ClassNode();
            try(var in=zip.getInputStream(zip.getEntry("me/cortex/voxy/common/world/service/VoxelIngestService.class"))) {
                new ClassReader(in).accept(node,0);
            }
            MethodNode worker=node.methods.stream().filter(m->m.name.equals("processJob")).findFirst().orElseThrow();
            var before=nativeCalls(worker);
            var writers=new LinkedHashMap<String,List<String>>();
            for(var method:node.methods)if(nativeCalls(method).stream().anyMatch(s->s.contains("WorldUpdater.insertUpdate")))
                writers.put(method.name,nativeCalls(method));
            long writes=writers.values().stream().flatMap(List::stream).filter(s->s.contains("WorldUpdater.insertUpdate")).count();
            assertTrue(writes>0,path);
            assertTrue(StrictVoxyIngestPlugin.instrument(node),path);
            MethodNode body=node.methods.stream().filter(m->m.name.equals("vss$processTrackedIngestBody")).findFirst().orElseThrow();
            assertEquals(before,nativeCalls(body),path);
            int certificates=0;
            for(var entry:writers.entrySet()) {
                MethodNode writer=entry.getKey().equals("processJob")?body:node.methods.stream()
                        .filter(m->m.name.equals(entry.getKey())).findFirst().orElseThrow();
                assertEquals(entry.getValue(),nativeCalls(writer),path);
                for(var instruction:writer.instructions)if(instruction instanceof MethodInsnNode call
                    &&call.owner.equals("dev/xantha/vss/compat/VoxyIngestTracker")&&call.name.equals("committed")) {
                AbstractInsnNode previous=instruction.getPrevious();
                while(previous!=null&&previous.getOpcode()<0)previous=previous.getPrevious();
                assertInstanceOf(MethodInsnNode.class,previous,path);
                assertEquals("insertUpdate",((MethodInsnNode)previous).name,path);certificates++;
                }
            }
            assertEquals(writes,certificates,path);
            for(var method:node.methods)new Analyzer<>(new BasicVerifier()).analyze(node.name,method);
        }
    }
    private static List<String> nativeCalls(MethodNode method) {
        var result=new ArrayList<String>();
        for(var instruction:method.instructions)if(instruction instanceof MethodInsnNode call
                &&!call.owner.equals("dev/xantha/vss/compat/VoxyIngestTracker"))result.add(call.owner+"."+call.name+call.desc);
        return result;
    }
}
