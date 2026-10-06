package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL45C.*;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;
import java.util.Arrays;
import java.util.Locale;

/** Tests the production compute shaders on real GL, including actual indirect geometry execution. */
@EnabledIfSystemProperty(named="vss.gpuTests",matches="true")
class PredictionOcclusionGpuTest {
    @Test void currentFrameVisibilityCommandsStateAndCost() {
        assertTrue(glfwInit()); glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,4); glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,6);
        glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);
        long window=glfwCreateWindow(64,64,"Prediction conservative HiZ",0,0); assertNotEquals(0,window);
        try {
            glfwMakeContextCurrent(window); GL.createCapabilities();
            com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
            System.out.println("OCCLUSION_GPU "+glGetString(GL_RENDERER)+" "+glGetString(GL_VERSION));
            int input=buffer(new int[]{6,1,0,0,0}), output=buffer(new int[5]), boxes=glCreateBuffers();
            glNamedBufferData(boxes,32,GL_STREAM_DRAW);
            int sentinel=buffer(new int[256]), texture=glCreateTextures(GL_TEXTURE_2D), sampler=glGenSamplers();
            glTextureStorage2D(texture,1,GL_R32F,4,4);
            glBindTextureUnit(8,texture);glBindSampler(8,sampler);
            glBindImageTexture(0,texture,0,false,0,GL_READ_ONLY,GL_R32F);
            int alignment=glGetInteger(GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT);
            for(int i=0;i<4;i++) glBindBufferRange(GL_SHADER_STORAGE_BUFFER,i,sentinel,alignment,128);
            glBindBuffer(GL_SHADER_STORAGE_BUFFER,sentinel);
            glActiveTexture(GL_TEXTURE5);
            try(var culler=new PredictionOcclusionCuller()) {
                for(int[] size:new int[][]{{64,64},{259,131},{17,513},{64,64}}) {
                    int w=size[0],h=size[1],depth=glCreateTextures(GL_TEXTURE_2D);
                    glTextureStorage2D(depth,1,GL_DEPTH_COMPONENT32F,w,h);
                    glTextureParameteri(depth,GL_TEXTURE_MIN_FILTER,GL_NEAREST);
                    glTextureParameteri(depth,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
                    Matrix4f transform=VssLodProjection.of(new Matrix4f().perspective((float)Math.toRadians(70),(float)w/h,.05f,4096)).matrix();
                    String oldDisable=System.getProperty("vss.disablePredictionOcclusion");
                    try {
                        System.setProperty("vss.disablePredictionOcclusion","true");
                        assertFalse(culler.begin(depth,w,h,transform));
                        assertEquals(input,culler.filter(boxes,input,output,1));
                    } finally {
                        if(oldDisable==null)System.clearProperty("vss.disablePredictionOcclusion");
                        else System.setProperty("vss.disablePredictionOcclusion",oldDisable);
                    }
                    glClipControl(GL_LOWER_LEFT,GL_ZERO_TO_ONE);
                    assertFalse(culler.begin(depth,w,h,transform));
                    glClipControl(GL_UPPER_LEFT,GL_NEGATIVE_ONE_TO_ONE);
                    assertFalse(culler.begin(depth,w,h,transform));
                    glClipControl(GL_LOWER_LEFT,GL_NEGATIVE_ONE_TO_ONE);
                    assertState(sentinel,texture,sampler,alignment);
                    setBox(boxes,-1,-1,-21,1,1,-20);
                    fill(depth,.1f);
                    assertTrue(culler.begin(depth,w,h,transform)); culler.rebuild();
                    assertCommand(culler,boxes,input,output,w==17?6:0,"foreground; extremely thin viewport conservatively touches padding");
                    if(w==17) {
                        setBox(boxes,-.002f,-.1f,-21,.002f,.1f,-20);
                        assertCommand(culler,boxes,input,output,0,"thin viewport interior box");
                        setBox(boxes,-1,-1,-21,1,1,-20);
                    }
                    // Same immutable command must recover immediately when the current depth changes.
                    fill(depth,0);culler.rebuild();
                    assertCommand(culler,boxes,input,output,6,"clear sky restores visibility");
                    setBox(boxes,-10000,-1,-21,-9999,1,-20);
                    assertCommand(culler,boxes,input,output,0,"offscreen segment is hidden even if its parent tile is visible");
                    setBox(boxes,-1,-1,-21,1,1,-20);
                    fill(depth,.1f);
                    glTextureSubImage2D(depth,0,w/2,h/2,1,1,GL_DEPTH_COMPONENT,GL_FLOAT,new float[]{0});
                    culler.rebuild(); assertCommand(culler,boxes,input,output,6,"one-pixel hole keeps silhouette");
                    fill(depth,1f/20); culler.rebuild();
                    assertCommand(culler,boxes,input,output,6,"coplanar depth cannot hide");
                    fill(depth,.1f); culler.rebuild();
                    setBox(boxes,-1,-1,-21,1,1,-.5f);
                    assertCommand(culler,boxes,input,output,6,"near-plane crossing");
                    setBox(boxes,-1,-1,-1,1,1,1);
                    assertCommand(culler,boxes,input,output,6,"camera inside");
                    setBox(boxes,-1,-1,-21,1,1,-20);
                    culler.end();
                    assertState(sentinel,texture,sampler,alignment);
                    assertTrue(culler.begin(depth,w,h,new Matrix4f(transform).rotateY((float)Math.PI)));
                    culler.rebuild(); assertCommand(culler,boxes,input,output,6,"camera turn does not reuse old visibility");
                    culler.end();
                    int[] original=new int[5];glGetNamedBufferSubData(input,0,original);
                    assertArrayEquals(new int[]{6,1,0,0,0},original,"cached commands are immutable");
                    glDeleteTextures(depth);
                }
                benchmark(culler,boxes,input,output);
                assertEquals(GL_NO_ERROR,glGetError());
            }
            glDeleteBuffers(input);glDeleteBuffers(output);glDeleteBuffers(boxes);glDeleteBuffers(sentinel);
            glDeleteTextures(texture);glDeleteSamplers(sampler);
        } finally {glfwDestroyWindow(window);glfwTerminate();}
    }
    private static void benchmark(PredictionOcclusionCuller culler,int boxes,int input,int output) {
        int size=256,quads=262144;
        int depth=glCreateTextures(GL_TEXTURE_2D),color=glCreateTextures(GL_TEXTURE_2D),fbo=glCreateFramebuffers();
        glTextureStorage2D(depth,1,GL_DEPTH_COMPONENT32F,size,size);
        glTextureParameteri(depth,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTextureParameteri(depth,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
        glTextureStorage2D(color,1,GL_RGBA8,size,size);
        glNamedFramebufferTexture(fbo,GL_DEPTH_ATTACHMENT,depth,0);glNamedFramebufferTexture(fbo,GL_COLOR_ATTACHMENT0,color,0);
        assertEquals(GL_FRAMEBUFFER_COMPLETE,glCheckNamedFramebufferStatus(fbo,GL_FRAMEBUFFER));
        int vao=glCreateVertexArrays(),indices=glCreateBuffers(),timer=glGenQueries(),primitives=glGenQueries();
        int[] elements=new int[quads*6];int[] quad={0,1,2,0,2,3};
        for(int i=0;i<quads;i++)for(int j=0;j<6;j++)elements[i*6+j]=i*4+quad[j];
        glNamedBufferData(indices,elements,GL_STATIC_DRAW);glVertexArrayElementBuffer(vao,indices);
        glNamedBufferSubData(input,0,new int[]{quads*6,1,0,0,0});
        Matrix4f projection=VssLodProjection.of(new Matrix4f().perspective((float)Math.toRadians(70),1,.05f,4096)).matrix();
        try(var shader=GlProgram.link("occlusion_execution", """
                #version 430
                uniform mat4 Transform;
                void main() {
                    int corner=gl_VertexID%4;
                    int q=gl_VertexID/4;
                    vec2 p=(vec2(q%512,q/512)+vec2(corner==1||corner==2?1:0,corner>=2?1:0))/512.0*2.0-1.0;
                    gl_Position=Transform*vec4(p,-20,1);
                }
                """, """
                #version 430
                out vec4 Color;
                void main() { Color=vec4(0,1,0,1); }
                """)) {
            shader.use();glUniformMatrix4fv(shader.uniform("Transform"),false,projection.get(new float[16]));
            glBindFramebuffer(GL_FRAMEBUFFER,fbo);glBindVertexArray(vao);glViewport(0,0,size,size);
            glEnable(GL_DEPTH_TEST);glDepthFunc(GL_GEQUAL);glDepthMask(true);glDisable(GL_CULL_FACE);glDisable(GL_BLEND);
            setBox(boxes,-1,-1,-21,1,1,-20);
            for(boolean hidden:new boolean[]{true,false}) {
                long[][] times=new long[2][31];byte[][] pixels=new byte[2][];
                long[] generated=new long[2];
                for(int frame=-8;frame<31;frame++)for(int mode:frame%2==0?new int[]{0,1}:new int[]{1,0}) {
                    glClearColor(0,0,0,1);glClearDepth(hidden?.1:0);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
                    glFinish();glBeginQuery(GL_TIME_ELAPSED,timer);
                    int commands=input;
                    if(mode==1) {
                        assertTrue(culler.begin(depth,size,size,projection));culler.rebuild();
                        commands=culler.filter(boxes,input,output,1);
                    }
                    shader.use();glBindBuffer(GL_DRAW_INDIRECT_BUFFER,commands);
                    glBeginQuery(GL_PRIMITIVES_GENERATED,primitives);
                    glMultiDrawElementsIndirect(GL_TRIANGLES,GL_UNSIGNED_INT,0L,1,20);
                    glEndQuery(GL_PRIMITIVES_GENERATED);
                    if(mode==1)culler.end();
                    glEndQuery(GL_TIME_ELAPSED);
                    long time=glGetQueryObjectui64(timer,GL_QUERY_RESULT);
                    generated[mode]=glGetQueryObjectui64(primitives,GL_QUERY_RESULT);
                    if(frame>=0)times[mode][frame]=time;
                    if(frame==0) {
                        var bytes=org.lwjgl.BufferUtils.createByteBuffer(size*size*4);
                        glReadPixels(0,0,size,size,GL_RGBA,GL_UNSIGNED_BYTE,bytes);
                        pixels[mode]=new byte[bytes.remaining()];bytes.get(pixels[mode]);
                    }
                }
                assertArrayEquals(pixels[0],pixels[1],"pixel equality, hidden="+hidden);
                assertEquals(quads*2L,generated[0]);assertEquals(hidden?0:quads*2L,generated[1]);
                for(long[] values:times)Arrays.sort(values);
                System.out.printf(Locale.ROOT,"OCCLUSION_BENCH hidden=%s quads=%d primitives=%d->%d GPU_ms=%.4f->%.4f (synthetic, includes HiZ; not world FPS)%n",
                        hidden,quads,generated[0],generated[1],times[0][15]/1e6,times[1][15]/1e6);
            }
        } finally {
            glBindFramebuffer(GL_FRAMEBUFFER,0);glBindVertexArray(0);glUseProgram(0);
            glDeleteTextures(depth);glDeleteTextures(color);glDeleteFramebuffers(fbo);glDeleteVertexArrays(vao);
            glDeleteBuffers(indices);glDeleteQueries(timer);glDeleteQueries(primitives);
        }
    }
    private static void assertState(int sentinel,int texture,int sampler,int alignment) {
        assertEquals(GL_TEXTURE5,glGetInteger(GL_ACTIVE_TEXTURE));
        assertEquals(texture,glGetIntegeri(GL_TEXTURE_BINDING_2D,8));assertEquals(sampler,glGetIntegeri(GL_SAMPLER_BINDING,8));
        assertEquals(texture,glGetIntegeri(GL_IMAGE_BINDING_NAME,0));assertEquals(GL_READ_ONLY,glGetIntegeri(GL_IMAGE_BINDING_ACCESS,0));
        assertEquals(sentinel,glGetInteger(GL_SHADER_STORAGE_BUFFER_BINDING));
        for(int i=0;i<4;i++) {
            assertEquals(sentinel,glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING,i));
            assertEquals(alignment,glGetInteger64i(GL_SHADER_STORAGE_BUFFER_START,i));
            assertEquals(128,glGetInteger64i(GL_SHADER_STORAGE_BUFFER_SIZE,i));
        }
        assertEquals(GL_NO_ERROR,glGetError(),"all compute bindings restored");
    }
    private static int buffer(int[] data) { int id=glCreateBuffers();glNamedBufferData(id,data,GL_STREAM_DRAW);return id; }
    private static void fill(int depth,float value) {glClearTexImage(depth,0,GL_DEPTH_COMPONENT,GL_FLOAT,new float[]{value});}
    private static void setBox(int buffer,float x,float y,float z,float xx,float yy,float zz) {
        glNamedBufferSubData(buffer,0,new float[]{x,y,z,0,xx,yy,zz,0});
    }
    private static void assertCommand(PredictionOcclusionCuller culler,int boxes,int input,int output,int count,String message) {
        assertEquals(output,culler.filter(boxes,input,output,1));
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
        int[] actual=new int[5];glGetNamedBufferSubData(output,0,actual);
        assertArrayEquals(new int[]{count,1,0,0,0},actual,message);
    }
}
