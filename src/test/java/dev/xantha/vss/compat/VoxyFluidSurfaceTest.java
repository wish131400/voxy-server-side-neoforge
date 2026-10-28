package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;
import org.objectweb.asm.tree.*;

public class VoxyFluidSurfaceTest {
    private static final long WATER=1L<<52|1L<<49|1L<<53, MODEL=7L<<26;
    public static class NativeFactory {
        public static boolean VERIFY_MESHING=false;
        public int quadCount,minX=99,minY=99,minZ=99,maxX,maxY,maxZ;
        public long quadBufferPtr;
        public int[] quadCounters=new int[8];
    }
    public static final Map<Long,Long> WRITES=new HashMap<>();
    public static void memPutLong(long address,long value) {WRITES.put(address,value);}
    @Test void releasedNativeEmitterPreservesRemaindersAndAllFourTransitionFaceCoordinates() throws Exception {
        var node=new ClassNode();String owner="me/cortex/voxy/client/core/rendering/building/RenderDataFactory";
        try(var jar=new ZipFile(System.getProperty("vss.voxyJar"))) {
            new ClassReader(jar.getInputStream(jar.getEntry(owner+"$Mesher.class"))).accept(node,0);
        }
        var emit=node.methods.stream().filter(m->m.name.equals("emitQuad")).findFirst().orElseThrow();
        String target="VssReleasedLiquidEmitter";
        var remap=new SimpleRemapper(Map.of(node.name,target,owner,Type.getInternalName(NativeFactory.class),
                "org/lwjgl/system/MemoryUtil",Type.getInternalName(VoxyFluidSurfaceTest.class)));
        var writer=new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17,Opcodes.ACC_PUBLIC,target,null,"java/lang/Object",null);
        for(var field:node.fields)writer.visitField(Opcodes.ACC_PUBLIC,field.name,remap.mapDesc(field.desc),null,null).visitEnd();
        var ctor=writer.visitMethod(Opcodes.ACC_PUBLIC,"<init>","()V",null,null);ctor.visitCode();ctor.visitVarInsn(Opcodes.ALOAD,0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL,"java/lang/Object","<init>","()V",false);ctor.visitInsn(Opcodes.RETURN);ctor.visitMaxs(1,1);ctor.visitEnd();
        emit.accept(new MethodRemapper(writer.visitMethod(Opcodes.ACC_PUBLIC,emit.name,emit.desc,null,null),remap));writer.visitEnd();
        Class<?> type=new ClassLoader(getClass().getClassLoader()) {Class<?> make(byte[] b){return defineClass(target,b,0,b.length);}}.make(writer.toByteArray());
        Object emitter=type.getConstructor().newInstance();var factory=new NativeFactory();type.getField("this$0").set(emitter,factory);
        var method=type.getMethod("emitQuad",int.class,int.class,int.class,int.class,long.class);
        for(int face:new int[]{2,3,4,5})for(int drop:new int[]{0,1,15}) {
            int axis=face>>1;type.getField("axis").setInt(emitter,axis);
            type.getField("auxiliaryPosition").setInt(emitter,axis==1?19:7);
            type.getField("doAuxiliaryFaceOffset").setBoolean(emitter,false);
            WRITES.clear();method.invoke(emitter,axis==1?7:19,11,1,1,MODEL|(long)drop<<42|(face&1));
            assertEquals(1,WRITES.size());long quad=WRITES.values().iterator().next();
            assertEquals(face,quad&7);assertEquals(7,quad>>>21&31);assertEquals(11,quad>>>16&31);assertEquals(19,quad>>>11&31);
            assertEquals(drop,quad>>>42&15);assertEquals(7,quad>>>26&65535);
        }
    }
    @Test void nativePackerPreservesMaterialLightBiomeAndAddsOnlyPureLiquidRemainders() throws Exception {
        var node=new ClassNode();
        try(var jar=new ZipFile(System.getProperty("vss.voxyJar"))) {
            new ClassReader(jar.getInputStream(jar.getEntry("me/cortex/voxy/client/core/rendering/building/RenderDataFactory.class"))).accept(node,0);
        }
        var methods=new ArrayList<MethodNode>();
        methods.add(node.methods.stream().filter(m->m.name.equals("packPartialQuadData")&&m.desc.equals("(IJJ)J")).findFirst().orElseThrow());
        for(int i=0;i<methods.size();i++)for(var insn:methods.get(i).instructions)
            if(insn instanceof MethodInsnNode c && c.owner.equals(node.name)) {
                var helper=node.methods.stream().filter(m->m.name.equals(c.name)&&m.desc.equals(c.desc)).findFirst().orElseThrow();
                if(!methods.contains(helper))methods.add(helper);
            }
        String target="VssNativeLiquidPacker";
        var writer=new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17,Opcodes.ACC_PUBLIC,target,null,"java/lang/Object",null);
        String queries="me/cortex/voxy/client/core/model/ModelQueries",qt="VssNativeFluidQueries";
        var remap=new SimpleRemapper(Map.of(node.name,target,queries,qt));
        var queryNode=new ClassNode();
        try(var jar=new ZipFile(System.getProperty("vss.voxyJar"))) {
            new ClassReader(jar.getInputStream(jar.getEntry(queries+".class"))).accept(queryNode,0);
        }
        var qw=new ClassWriter(ClassWriter.COMPUTE_MAXS);qw.visit(Opcodes.V17,Opcodes.ACC_PUBLIC,qt,null,"java/lang/Object",null);
        for(var m:queryNode.methods)if((m.access&Opcodes.ACC_STATIC)!=0)m.accept(new MethodRemapper(
                qw.visitMethod(m.access,m.name,m.desc,null,null),remap));
        qw.visitEnd();
        for(var method:methods) {
            method.access=Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC;
            method.accept(new MethodRemapper(writer.visitMethod(method.access,method.name,method.desc,null,null),remap));
        }
        writer.visitEnd();
        try(var loader=new java.net.URLClassLoader(new java.net.URL[]{java.nio.file.Path.of(System.getProperty("vss.voxyJar")).toUri().toURL()},getClass().getClassLoader()) {
            Class<?> define(byte[] bytes){byte[] qb=qw.toByteArray();defineClass(qt,qb,0,qb.length);return defineClass(target,bytes,0,bytes.length);}
        }) {
            var pack=loader.define(writer.toByteArray()).getMethod("packPartialQuadData",int.class,long.class,long.class);
            for(long meta:new long[]{WATER,1L<<54,1L<<51,0})for(int drop=0;drop<16;drop++) {
                long raw=0xe7L<<56|123L<<47|54321L<<27;
                long before=(long)pack.invoke(null,65530,raw,meta);
                long nativeTagged=(long)pack.invoke(null,65530,raw|drop,meta);
                assertEquals(before,nativeTagged,"Native material/light/biome decoding ignores raw remainder bits");
                long after=VoxyFluidSurface.pack(nativeTagged,raw|drop,meta);
                assertEquals(before,after&~(15L<<42));
                assertEquals((meta&(1L<<52))!=0?drop:0,after>>>42&15);
            }
        }
    }
    @Test void unequalCapsProduceOneNativeWallAndEqualWaterProducesNone() {
        long[] data=new long[65536],raw=new long[32768],neighbors=new long[6144];
        int i=4<<10|8<<5|7;
        data[i*2]=MODEL|2|1L<<42;data[i*2+1]=WATER;
        data[(i+1)*2]=MODEL|2|5L<<42;data[(i+1)*2+1]=WATER;
        data[(i+32)*2]=MODEL|2|1L<<42;data[(i+32)*2+1]=WATER;
        var walls=new ArrayList<String>();
        VoxyFluidSurface.transitions(data,raw,neighbors,(f,x,y,z,q)->walls.add(f+":"+x+":"+y+":"+z));
        assertEquals(List.of("5:7:4:8"),walls,"Higher liquid gets one outward strip; solids/equal caps keep native culling");
        data[i*2]=MODEL|2|7L<<42;
        walls.clear();VoxyFluidSurface.transitions(data,raw,neighbors,(f,x,y,z,q)->walls.add(f+":"+x+":"+y+":"+z));
        assertEquals(Set.of("4:8:4:8","2:7:4:9"),new HashSet<>(walls));
    }
    @Test void opaqueShorelineFacesRemainOwnedByNativeOpaqueMeshing() {
        long[] data=new long[65536],raw=new long[32768],neighbors=new long[6144];
        int i=4<<10|8<<5|7;data[i*2]=MODEL|2|1L<<42;data[i*2+1]=WATER;
        data[(i+1)*2]=11L<<26|4;data[(i+1)*2+1]=1L<<54;
        var walls=new ArrayList<String>();
        VoxyFluidSurface.transitions(data,raw,neighbors,(f,x,y,z,q)->walls.add(f+":"+x+":"+(q>>>26&65535)+":"+(q>>>42&15)));
        assertTrue(walls.isEmpty(),"Native opaque culling already emits the full bank face; do not duplicate it");
        Arrays.fill(data,0);i=4<<10|8<<5;data[i*2]=11L<<26|4;data[i*2+1]=1L<<54;
        neighbors[4*32+8]=9L<<27|16|1;
        walls.clear();VoxyFluidSurface.transitions(data,raw,neighbors,(f,x,y,z,q)->walls.add(f+":"+x));
        assertTrue(walls.isEmpty(),"Native outer opaque faces also remain unchanged");
    }
    @Test void allHorizontalSectionBordersUseNativePlaneCoordinatesAndOneOwner() {
        for(int face:new int[]{0,1,4,5}) {
            int x=face<2?(face&1)*31:13,z=face>=4?(face&1)*31:19,y=8;
            int i=y<<10|z<<5|x,plane=face<2?y*32+z:y*32+x;
            long[] data=new long[65536],raw=new long[32768],neighbors=new long[6144];
            data[i*2]=MODEL|2|1L<<42;data[i*2+1]=WATER;raw[i]=9L<<27|1;
            neighbors[face*1024+plane]=9L<<27|7;
            var walls=new ArrayList<Integer>();
            VoxyFluidSurface.transitions(data,raw,neighbors,(f,xx,yy,zz,q)-> {assertEquals(x,xx);assertEquals(y,yy);assertEquals(z,zz);walls.add(f);});
            assertEquals(List.of(face<2?face+4:face-2),walls);
            neighbors[face*1024+plane]=9L<<27;
            walls.clear();VoxyFluidSurface.transitions(data,raw,neighbors,(f,xx,yy,zz,q)->walls.add(f));
            assertTrue(walls.isEmpty(),"Lower side never duplicates its neighbor's wall");
        }
    }
    public static class FixtureMesher {
        public int axis,auxiliaryPosition;public boolean doAuxiliaryFaceOffset;
        final List<String> walls=new ArrayList<>();
        protected void emitQuad(int x,int z,int length,int width,long data) {
            assertFalse(doAuxiliaryFaceOffset);assertEquals(1,length);assertEquals(1,width);
            walls.add(axis+":"+auxiliaryPosition+":"+x+":"+z+":"+(data&1));
        }
    }
    public static class FixtureFactory {
        private final long[] sectionData=new long[65536],neighboringFaces=new long[6144];
        private final FixtureMesher blockMesher=new FixtureMesher();
    }
    @Test void realLiquidSectionCanOwnTheHigherSideNextToPredictedWater() {
        var factory=new FixtureFactory();int i=4<<10|8<<5;
        factory.sectionData[i*2]=MODEL|2;factory.sectionData[i*2+1]=WATER;
        long[] raw=new long[32768];raw[i]=9L<<27;
        factory.neighboringFaces[4*32+8]=9L<<27|16|1;
        VoxyFluidSurface.meshTransitions(factory,raw,false);
        assertEquals(List.of("2:0:8:4:0"),factory.blockMesher.walls);
    }
    public static class NativeFluidFactory {private void applyBiomeBlend() {}}
    public static class LegacyFluidFactory {private void generateFluidFaces() {}}
    @Test void nativePaletteOwnerIsExcludedWhileLegacyFluidEntryPointsRemainSupported() {
        assertFalse(VoxyFluidSurface.legacyMesher(NativeFluidFactory.class));
        assertTrue(VoxyFluidSurface.legacyMesher(LegacyFluidFactory.class));
    }
    @Test void nativeEmitterReceivesCorrectAxesAndCoordinatesWithoutScanningBuiltGeometry() {
        var factory=new FixtureFactory();int i=4<<10|8<<5|7;
        factory.sectionData[i*2]=MODEL|2|1L<<42;factory.sectionData[i*2+1]=WATER;
        for(int d:new int[]{1,32}) {factory.sectionData[(i+d)*2]=MODEL|2|7L<<42;factory.sectionData[(i+d)*2+1]=WATER;}
        VoxyFluidSurface.meshTransitions(factory,new long[32768],true);
        assertEquals(Set.of("2:7:8:4:1","1:8:7:4:1"),new HashSet<>(factory.blockMesher.walls));
        assertTrue(factory.blockMesher.doAuxiliaryFaceOffset);
    }
}
