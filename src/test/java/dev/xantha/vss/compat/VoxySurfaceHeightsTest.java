package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;

class VoxySurfaceHeightsTest {
    @Test void nativeMeshHasNoPostCullHeightDeformation() throws Exception {
        var bridge=new org.objectweb.asm.tree.ClassNode();
        new org.objectweb.asm.ClassReader(VoxyPredictionBridge.class.getName()).accept(bridge,0);
        var mesh=bridge.methods.stream().filter(m->m.name.equals("generateMesh")).findFirst().orElseThrow();
        for(var instruction:mesh.instructions) if(instruction instanceof org.objectweb.asm.tree.MethodInsnNode call) {
            assertFalse(call.owner.endsWith("/VoxySurfaceHeights"),"Already culled native geometry must remain unchanged");
            assertNotEquals("surfaceHeights",call.name,"Do not rebuild a second snapshot just to move mesh vertices");
        }
    }
    @Test void nativeInsetAndFluidDatumBranchesArePreserved() {
        String base="void setupQuad() { quad.basePoint = (quadStart*lodScale)+vec3(baseSection<<5); quad.uvCorner = faceSize.xz; }";
        for(String nativeHelper:new String[]{"resolveFluidTopIndentation(","extractFaceIndentation(faceData) / lodScale"}) {
            String nativeShader=nativeHelper+base;
            String patched=VoxySurfaceHeights.patchShader("voxy:lod/quad_util.glsl",nativeShader);
            assertTrue(patched.contains(nativeHelper));
            assertEquals(!nativeHelper.startsWith("resolveFluid"),patched.contains("// VSS liquid surface remainder"));
            assertFalse(patched.contains("// VSS world-space model inset"));
        }
    }
    @Test void actualBackendShaderCompilesAndExecutesBothQuadRepresentations() throws Exception {
        String jar=System.getProperty("vss.voxyJar");assertNotNull(jar);
        String source,format;
        try(var z=new ZipFile(jar)) {
            source=new String(z.getInputStream(z.getEntry("assets/voxy/shaders/lod/quad_util.glsl")).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            format=new String(z.getInputStream(z.getEntry("assets/voxy/shaders/lod/quad_format.glsl")).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            var geometry=new org.objectweb.asm.tree.ClassNode();
            new org.objectweb.asm.ClassReader(z.getInputStream(z.getEntry("me/cortex/voxy/client/core/rendering/building/BuiltSection.class"))).accept(geometry,0);
            assertTrue(geometry.fields.stream().anyMatch(f->f.name.equals("geometryBuffer") && f.desc.endsWith("/MemoryBuffer;")));
        }
        String patched=VoxySurfaceHeights.patchShader("voxy:lod/gl46/quads3.vert",format+"\n"+source);
        assertTrue(patched.contains("// VSS world-space model inset") || source.contains("resolveFluidTopIndentation(")
                || source.matches("(?s).*extractFaceIndentation\\(faceData\\)\\s*/\\s*lodScale.*"));
        assertSame(patched,VoxySurfaceHeights.patchShader("voxy:lod/quad_util.glsl",patched));
        if(source.contains("resolveFluidTopIndentation("))assertEquals(format+"\n"+source,patched,"Native fluid shapes and blend payloads remain intact");
        assertFalse(patched.contains("vssSurfaceDrop"));
        String setup=patched.substring(patched.indexOf("void setupQuad("));
        // Keep the production setup function through its closing brace, excluding downstream projection helpers.
        int open=setup.indexOf('{'),balance=1,end=open+1;
        while(balance!=0) {char c=setup.charAt(end++);if(c=='{')balance++;if(c=='}')balance--;}
        setup=setup.substring(0,end);
        assertTrue(GLFW.glfwInit(),"A real GL context is required for this regression");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,3);
        long window=GLFW.glfwCreateWindow(32,32,"VSS height verification",0,0);assertNotEquals(0,window);
        try {
            GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();
            for(boolean wide:new boolean[]{false,true})for(boolean single:new boolean[]{false,true}) {
                if(single && !source.contains("#ifdef USE_SINGLE_TRI"))continue;
                for(boolean solid:new boolean[]{false,true}) execute(setup,patched,format,wide,single,solid);
            }
            try(var z=new ZipFile(jar)) {
                String full=expand(z,"assets/voxy/shaders/lod/gl46/quads3.vert",new HashSet<>());
                String defines="\n#define Z_AXIS_FACE_TINT 0.8\n#define X_AXIS_FACE_TINT 0.6\n#define UP_FACE_TINT 1.0\n#define DOWN_FACE_TINT 0.5\n#define NO_SHADE_FACE_TINT 1.0\n";
                int versionEnd=full.indexOf('\n');full=full.substring(0,versionEnd+1)+defines+full.substring(versionEnd+1);
                full=VoxySurfaceHeights.patchShader("voxy:lod/gl46/quads3.vert",full);
                int vertex=GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
                GL20.glShaderSource(vertex,full);GL20.glCompileShader(vertex);
                assertEquals(GL11.GL_TRUE,GL20.glGetShaderi(vertex,GL20.GL_COMPILE_STATUS),GL20.glGetShaderInfoLog(vertex));
                GL20.glDeleteShader(vertex);
            }
        } finally {GLFW.glfwMakeContextCurrent(0);GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }
    private static String expand(ZipFile jar,String path,Set<String> included) throws Exception {
        if(!included.add(path))return "";
        String source=new String(jar.getInputStream(jar.getEntry(path)).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        var imports=java.util.regex.Pattern.compile("(?m)^\\s*#import\\s+<voxy:([^>]+)>.*$").matcher(source);
        StringBuilder result=new StringBuilder();
        while(imports.find())imports.appendReplacement(result,java.util.regex.Matcher.quoteReplacement(
                expand(jar,"assets/voxy/shaders/"+imports.group(1),included)));
        imports.appendTail(result);return result.toString();
    }
    private static void execute(String setup,String patched,String format,boolean wide,boolean single,boolean solid) {
        // Older forks select the native representation from the GPU's predefined macro.
        // Replace only this switch in the fixture to exercise the fallback on this capable GPU.
        format=format.replace("#ifdef GL_ARB_gpu_shader_int64","#ifdef QUAD_DATA_USE_64_BIT");
        setup=setup.replace("#ifdef GL_ARB_gpu_shader_int64","#ifdef QUAD_DATA_USE_64_BIT");
        String representations=wide ? "#extension GL_ARB_gpu_shader_int64 : require\n#define QUAD_DATA_USE_64_BIT\n" : "";
        // Use a flat water model with inset side edges, rather than full-cube sides.
        // Fork tint helpers use stubs; fluid datum follows the native helper's arithmetic.
        String shader="""
            #version 430 core
            %s
            layout(local_size_x=1) in;
            layout(std430,binding=0) buffer Result { float result[]; };
            %s
            struct BlockModel {uint faceData[6];};
            BlockModel modelData[1];
            struct QuadData {uvec4 attributeData;float lodScale;uint axis;vec3 basePoint;vec2 quadSizeAddin;vec2 uvCorner;};
            uint testLod;int testSectionY;ivec3 baseSectionPos=ivec3(0);float fluidDatumY;float testInset;
            uint getLoDLevel(uvec2 p){return testLod;}
            ivec3 getLoDPosition(uvec2 p){return ivec3(0,testSectionY,0);}
            uint extractDetail(uvec2 p){return testLod;}
            ivec3 extractLoDPosition(uvec2 p){return ivec3(0,testSectionY,0);}
            uint makeQuadFlags(uint a,uint b,ivec2 c,BlockModel d,uint e){return 0u;}
            uvec3 makeRemainingAttributes(BlockModel a,Quad b,uint c,uint d){return uvec3(0);}
            bool modelUsesBalancedLeafCutout(BlockModel m){return false;}
            uint makeBalancedLeafSeed(Quad q,ivec3 p,uint l,uint f){return 0u;}
            float resolveFluidTopIndentation(BlockModel m,uint f,float b,float y,float s,ivec3 p,uint l){
                if(TEST_SOLID!=0 || l==0u || f!=1u)return b;
                float bottom=y*s+float((p.y<<l)<<5),datum=(fluidDatumY-bottom)/s;
                return datum<=0.0 || datum>1.0?b:clamp(1.0-datum,0.0,62.0/64.0);
            }
            vec4 getFaceSize(uint f){return f==2u || f==3u?vec4(0,1,0,1.0-testInset):
                    f==4u || f==5u?vec4(0,1.0-testInset,0,1):vec4(0,1,0,1);}
            float extractFaceIndentation(uint f){return f==1u?testInset:0.0;}
            vec3 swizzelDataAxis(uint a,vec3 d){return a==0u?d.xzy:(a==1u?d:d.zxy);}
            %s
            void main(){
                testInset=TEST_SOLID!=0 ? 0.0 : 7.0/64.0;
                for(uint f=0u;f<6u;f++)modelData[0].faceData[f]=f;
                const int caps[13]=int[13](48,49,55,63,64,65,79,80,-64,-63,-1,0,1);
                for(int c=0;c<13;c++) {
                int cap=caps[c];fluidDatumY=float(cap)-testInset;
                for(uint i=0u;i<5u;i++){
                    testLod=i;int step=1<<i;testSectionY=(cap-1)>>(5u+i);
                    uint y=uint((cap-1)>>i)&31u;
                    for(uint f=1u;f<=5u;f++){
                    #ifdef QUAD_DATA_USE_64_BIT
                    Quad q=uint64_t(f|(y<<16)) | (uint64_t((TEST_SOLID!=0 || TEST_NATIVE!=0)?0:((-cap)&(step-1)))<<42);
                    #else
                    Quad q=ivec2(f|(y<<16),((TEST_SOLID!=0 || TEST_NATIVE!=0)?0:((-cap)&(step-1)))<<10);
                    #endif
                    QuadData d;setupQuad(d,q,uvec2(0),true);
                    float height=d.basePoint.y;
                    if(f>=2u)height+=(f<4u?d.quadSizeAddin.y:d.quadSizeAddin.x)*d.lodScale
                    #ifdef USE_SINGLE_TRI
                        *0.5
                    #endif
                        ;
                    uint at=c*25u+i*5u+f-1u;
                    result[at]=height;
                    result[325u+at]=d.basePoint.y;
                    }
                }
                }
            }
            """.formatted(representations
                    +(single?"#define USE_SINGLE_TRI\n":"")+"#define TEST_SOLID "+(solid?1:0)+"\n#define TEST_NATIVE "+(setup.contains("resolveFluidTopIndentation(")?1:0)+"\n",format,setup);
        int sh=GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);GL20.glShaderSource(sh,shader);GL20.glCompileShader(sh);
        assertEquals(GL11.GL_TRUE,GL20.glGetShaderi(sh,GL20.GL_COMPILE_STATUS),GL20.glGetShaderInfoLog(sh));
        int program=GL20.glCreateProgram();GL20.glAttachShader(program,sh);GL20.glLinkProgram(program);
        assertEquals(GL11.GL_TRUE,GL20.glGetProgrami(program,GL20.GL_LINK_STATUS),GL20.glGetProgramInfoLog(program));
        int buffer=GL15.glGenBuffers();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,buffer);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,2*13*25*4,GL15.GL_DYNAMIC_READ);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,buffer);
        GL20.glUseProgram(program);GL43.glDispatchCompute(1,1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
        float[] actual=new float[2*13*25];GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,actual);
        int[] caps={48,49,55,63,64,65,79,80,-64,-63,-1,0,1};
        for(int c=0;c<caps.length;c++)for(int lod=0;lod<=4;lod++)for(int face=1;face<=5;face++) {
            int rounded=-Math.floorDiv(-caps[c],1<<lod)*(1<<lod);
            // Full cubes must retain Voxy's integer occupancy boundary after face culling.
            // Existing fork fluid datums remain native; older Voxy only normalizes model indentation.
            boolean nativeFluid=setup.contains("resolveFluidTopIndentation(");
            boolean patchedInset=setup.contains("// VSS world-space model inset");
            double expected=solid ? rounded : !nativeFluid && ((-caps[c])&((1<<lod)-1))!=0 ? caps[c]-7.0/64 : face==1 ? (nativeFluid ? caps[c] : rounded)-7.0/64
                    : rounded-7.0/64*(patchedInset ? 1 : 1<<lod);
            assertEquals(expected,actual[c*25+lod*5+face-1],1e-5,
                    "GPU cap="+caps[c]+" LOD="+lod+" face="+face+" wide="+wide+" single="+single+" solid="+solid);
        }
        if(solid) {
            int lod=3;
            assertEquals(actual[3*25+lod*5],actual[4*25+lod*5],
                    "Adjacent sampled heights 63/64 share the native 64 plateau; no culled side is needed");
            assertEquals(actual[4*25+lod*5],actual[325+5*25+lod*5+1],
                    "The higher 65 column's existing side starts at the lower column's native 64 top");
        }
        GL20.glUseProgram(0);GL15.glDeleteBuffers(buffer);GL20.glDeleteProgram(program);GL20.glDeleteShader(sh);
    }
}
