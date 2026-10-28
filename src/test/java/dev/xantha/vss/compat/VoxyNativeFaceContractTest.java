package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.mixin.voxy.VoxyPredictionPlugin;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;
import org.objectweb.asm.tree.*;

/** Execute the released backend's actual plane-reader bytecode against coordinate-marked sections. */
public class VoxyNativeFaceContractTest {
    public static final class Section {
        public static int RELEASE_HINT_POSSIBLE_REUSE=0;
        public int lvl,x,y,z;public long key;
        final long[] data=new long[32768];
        Section(int lvl,int x,int y,int z) {
            this.lvl=lvl;this.x=x;this.y=y;this.z=z;
            for(int i=0;i<data.length;i++)data[i]=marker(lvl,x,y,z,i);
        }
        public long[] _unsafeGetRawDataArray(){return data;}
        public long[] _rawOrNull(){return data;}
        public long getUniformValue(){return 0;}
        public boolean isUniform(){return false;}
        public long[] materialize(){return data;}
    }
    public static final class Engine {
        public Section acquire(int l,int x,int y,int z){return new Section(l,x,y,z);}
        public Section acquireIfExists(int l,int x,int y,int z){return acquire(l,x,y,z);}
    }
    public static int expand(int value,int mask) {
        int out=0,bit=1;
        for(int i=0;i<32;i++)if((mask&1<<i)!=0) {if((value&bit)!=0)out|=1<<i;bit<<=1;}
        return out;
    }
    private static long marker(int l,int x,int y,int z,int i) {
        return ((long)(l+31)<<56)^((long)(x+128)<<40)^((long)(y+128)<<32)^((long)(z+128)<<24)^i;
    }
    @Test void actualBackendPlaneReaderMatchesAllSixFacesAndMaskIsolation() throws Exception {
        var node=new ClassNode();
        try(var jar=new ZipFile(System.getProperty("vss.voxyJar"));var input=jar.getInputStream(jar.getEntry(
                "me/cortex/voxy/client/core/rendering/building/RenderDataFactory.class"))) {
            new ClassReader(input).accept(node,0);
        }
        String fingerprint=VoxyPredictionPlugin.faceLayoutFingerprint(node);
        System.out.println("NATIVE_FACE_READER "+fingerprint+" "+System.getProperty("vss.voxyJar"));
        var reader=node.methods.stream().filter(m->m.name.equals("acquireNeighborData")).findFirst().orElseThrow();
        if(!reader.desc.endsWith(")V")) {
            assertFalse(VoxyPredictionPlugin.supportsFaceLayout(node));
            System.out.println("NATIVE_FACE_FALLBACK availability-returning backend retains native missing-neighbor policy");
            return;
        }
        // Include the backend's own helpers, so uniform-storage variants execute their real loops too.
        var methods=new java.util.ArrayList<MethodNode>();methods.add(reader);
        for(int i=0;i<methods.size();i++)for(var insn:methods.get(i).instructions)
            if(insn instanceof MethodInsnNode call && call.owner.equals(node.name)) {
                var helper=node.methods.stream().filter(m->m.name.equals(call.name) && m.desc.equals(call.desc)).findFirst().orElseThrow();
                if(!methods.contains(helper))methods.add(helper);
            }
        // Release bookkeeping is unrelated to the native face layout; remove it without changing the loops.
        for(var member:methods)for(var insn:member.instructions.toArray()) {
            if(insn instanceof MethodInsnNode call && call.owner.endsWith("/WorldSection") && call.name.equals("release")) {
                var replacement=new InsnList();
                for(Type argument:Type.getArgumentTypes(call.desc))replacement.add(new InsnNode(argument.getSize()==2?Opcodes.POP2:Opcodes.POP));
                replacement.add(new InsnNode(Opcodes.POP));
                if(Type.getReturnType(call.desc).getSort()==Type.INT)replacement.add(new InsnNode(Opcodes.ICONST_0));
                member.instructions.insertBefore(call,replacement);member.instructions.remove(call);
            }
            if(insn instanceof MethodInsnNode call && call.name.equals("expand") && call.desc.equals("(II)I")
                    && (call.owner.equals("java/lang/Integer") || call.owner.endsWith("/ExpansionUtil") || call.owner.endsWith("/BitMath")))
                call.owner=Type.getInternalName(VoxyNativeFaceContractTest.class);
        }
        String target="VssExecutableNeighborPlanes",world="me/cortex/voxy/common/world/WorldEngine",section="me/cortex/voxy/common/world/WorldSection";
        var remap=new SimpleRemapper(java.util.Map.of(node.name,target,world,Type.getInternalName(Engine.class),section,Type.getInternalName(Section.class)));
        var writer=new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17,Opcodes.ACC_PUBLIC,target,null,"java/lang/Object",null);
        writer.visitField(Opcodes.ACC_PUBLIC,"world",Type.getDescriptor(Engine.class),null,null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC,"neighboringFaces","[J",null,null).visitEnd();
        var constructor=writer.visitMethod(Opcodes.ACC_PUBLIC,"<init>","()V",null,null);
        constructor.visitCode();constructor.visitVarInsn(Opcodes.ALOAD,0);constructor.visitMethodInsn(Opcodes.INVOKESPECIAL,"java/lang/Object","<init>","()V",false);
        constructor.visitInsn(Opcodes.RETURN);constructor.visitMaxs(1,1);constructor.visitEnd();
        for(var member:methods) {
            member.access=Opcodes.ACC_PUBLIC;
            var method=writer.visitMethod(member.access,member.name,remap.mapMethodDesc(member.desc),null,null);
            member.accept(new MethodRemapper(method,remap));
        }
        writer.visitEnd();
        try(var loader=new java.net.URLClassLoader(new java.net.URL[]{java.nio.file.Path.of(System.getProperty("vss.voxyJar")).toUri().toURL()},getClass().getClassLoader()) {
            Class<?> define(byte[] bytes){return defineClass(target,bytes,0,bytes.length);}
        }) {
        Class<?> contract=loader.define(writer.toByteArray());
        Object instance=contract.getConstructor().newInstance();contract.getField("world").set(instance,new Engine());
        var read=contract.getMethod("acquireNeighborData",Section.class,int.class);
        for(int mask:new int[]{1,2,4,8,16,32,63,21,0}) {
            long[] output=new long[6144];java.util.Arrays.fill(output,-1);contract.getField("neighboringFaces").set(instance,output);
            read.invoke(instance,new Section(3,-4,-2,7),mask);
            for(int direction=0;direction<6;direction++)for(int i=0;i<1024;i++) {
                int x=-4+(direction==0?-1:direction==1?1:0),y=-2+(direction==2?-1:direction==3?1:0),z=7+(direction==4?-1:direction==5?1:0);
                long expected=(mask&1<<direction)==0?-1:marker(3,x,y,z,VoxyFaceData.index(direction^1,i));
                assertEquals(expected,output[direction*1024+i],"direction="+direction+", i="+i);
            }
        }
        }
    }
}
