package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.mixin.voxy.StrictVoxyIngestPlugin;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import org.junit.jupiter.api.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

class VoxyIngestRealWriteTest {
    private static final String FIXTURE="dev/xantha/vss/compat/VoxyIngestRealWriteFixture";
    private static final String NATIVE="me/cortex/voxy/common/";
    @BeforeEach void reset() { VoxyIngestTracker.resetWorld(); }

    @Test void transformedNativeLocalPublicationCertifiesOnlyTheActualSuccessfulWrites() throws Throwable {
        exercise(false);
    }
    @Test void delegatedNativeWriterDoesNotCertifyAnExceptionHandledByTheNativeWorker() throws Throwable {
        exercise(true);
    }
    private void exercise(boolean delegated) throws Throwable {
        Class<?> type=transformed(delegated);Object service=type.getConstructor().newInstance();
        Class<?> world=type.getClassLoader().loadClass(FIXTURE.replace('/','.')+"$WorldEngine");
        Object engine=world.getConstructor().newInstance();
        assertFalse((boolean)type.getMethod("enqueueIngest",world).invoke(service,engine));
        assertFalse(VoxyRealCoverage.of(engine).containsSection(0,0,0));
        type.getMethod("processJob").invoke(service);
        assertTrue(VoxyRealCoverage.of(engine).containsSection(0,0,0));
        assertFalse(VoxyRealCoverage.of(engine).containsSection(0,1,0));
        world.getField("fail").setBoolean(engine,true);
        if(delegated)type.getMethod("processJob").invoke(service);
        else {
            var failure=assertThrows(InvocationTargetException.class,()->type.getMethod("processJob").invoke(service));
            assertEquals("native insert failed",failure.getCause().getMessage());
        }
        assertFalse(VoxyRealCoverage.of(engine).containsSection(0,1,0));
        assertFalse(VoxyIngestTracker.workerScopeActive());
        assertEquals(1,world.getField("writes").getInt(engine));
    }

    private static Class<?> transformed(boolean delegated) throws Exception {
        Map<String,String> names=Map.of(FIXTURE+"$WorldEngine",NATIVE+"world/WorldEngine",
                FIXTURE+"$WorldUpdater",NATIVE+"world/WorldUpdater",
                FIXTURE+"$VoxelizedSection",NATIVE+"voxelization/VoxelizedSection");
        ClassNode node=new ClassNode();
        try(var input=VoxyIngestRealWriteTest.class.getClassLoader().getResourceAsStream(FIXTURE+".class")) {
            new ClassReader(Objects.requireNonNull(input)).accept(new ClassRemapper(node,new SimpleRemapper(names)),0);
        }
        if(delegated) {
            node.methods.stream().filter(m->m.name.equals("processJob")).findFirst().orElseThrow().name="unusedDirectWorker";
            node.methods.stream().filter(m->m.name.equals("processDelegatedJob")).findFirst().orElseThrow().name="processJob";
        }
        assertTrue(StrictVoxyIngestPlugin.instrument(node));
        Map<String,String> inverse=new HashMap<>();names.forEach((k,v)->inverse.put(v,k));
        ClassNode result=new ClassNode();node.accept(new ClassRemapper(result,new SimpleRemapper(inverse)));
        for(var method:result.methods)new Analyzer<>(new BasicVerifier()).analyze(result.name,method);
        ClassWriter writer=new ClassWriter(0);result.accept(writer);byte[] transformed=writer.toByteArray();
        return new ClassLoader(VoxyIngestRealWriteTest.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException {
                if(!name.startsWith(FIXTURE.replace('/','.')))return super.loadClass(name,resolve);
                synchronized(getClassLoadingLock(name)) {
                    Class<?> found=findLoadedClass(name);
                    if(found==null)try {
                        byte[] bytes=name.equals(FIXTURE.replace('/','.'))?transformed:
                                Objects.requireNonNull(getParent().getResourceAsStream(name.replace('.','/')+".class")).readAllBytes();
                        found=defineClass(name,bytes,0,bytes.length);
                    }catch(java.io.IOException failure){throw new ClassNotFoundException(name,failure);}
                    if(resolve)resolveClass(found);return found;
                }
            }
        }.loadClass(FIXTURE.replace('/','.'));
    }
}
