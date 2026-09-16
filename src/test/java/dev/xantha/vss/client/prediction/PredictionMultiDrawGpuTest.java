package dev.xantha.vss.client.prediction;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

@EnabledIfSystemProperty(named="vss.gpuTests",matches="true")
class PredictionMultiDrawGpuTest {
    @Test void orderedMultiDrawMatchesColorAndDepthOfSeparateRanges() {
        assertTrue(glfwInit());glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,3);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,3);
        glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);
        long window=glfwCreateWindow(32,32,"VSS multidraw regression",0,0);assertNotEquals(0,window);
        try {
            glfwMakeContextCurrent(window);GL.createCapabilities();
            com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
            int vao=glGenVertexArrays(),ebo=glGenBuffers();glBindVertexArray(vao);glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,ebo);
            var indices=BufferUtils.createIntBuffer(30);
            for(int q=0;q<5;q++)for(int i:new int[]{0,1,2,2,3,0})indices.put(q*4+i);
            indices.flip();glBufferData(GL_ELEMENT_ARRAY_BUFFER,indices,GL_STATIC_DRAW);
            try(var program=GlProgram.link("multidraw_test","""
                #version 330
                flat out int quad;
                void main(){
                    quad=gl_VertexID/4;int v=gl_VertexID%4;
                    vec2 p=vec2(v==1||v==2?0.8:-0.8,v>=2?0.8:-0.8);
                    gl_Position=vec4(p+vec2(float(quad)*0.02),float(quad)*0.1,1);
                }
                ""","""
                #version 330
                flat in int quad;out vec4 color;
                void main(){color=vec4(float(quad+1)/5.0,0.2,0.8,0.4);}
                """)) {
                program.use();glViewport(0,0,32,32);glDisable(GL_CULL_FACE);
                for(boolean water:new boolean[]{false,true})for(int mask=1;mask<32;mask++) {
                    byte[][] color=new byte[2][];float[][] depth=new float[2][];
                    for(int mode=0;mode<2;mode++) {
                        glDepthMask(true);glClearColor(0,0,0,0);glClearDepth(1);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
                        glEnable(GL_DEPTH_TEST);glDepthFunc(GL_LEQUAL);glDepthMask(!water);
                        if(water){glEnable(GL_BLEND);glBlendFunc(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA);}else glDisable(GL_BLEND);
                        if(mode==0) {for(int g=0;g<5;g++)if((mask&(1<<g))!=0)glDrawElements(GL_TRIANGLES,6,GL_UNSIGNED_INT,(long)g*24);}
                        else PredictionRenderer.submitRanges(new PredictionDrawRanges(new int[]{0,1,2,3,4},new int[]{1,1,1,1,1},mask));
                        var pixels=BufferUtils.createByteBuffer(32*32*4);glReadPixels(0,0,32,32,GL_RGBA,GL_UNSIGNED_BYTE,pixels);
                        color[mode]=new byte[pixels.remaining()];pixels.get(color[mode]);
                        var depths=BufferUtils.createFloatBuffer(32*32);glReadPixels(0,0,32,32,GL_DEPTH_COMPONENT,GL_FLOAT,depths);
                        depth[mode]=new float[depths.remaining()];depths.get(depth[mode]);
                        assertEquals(GL_NO_ERROR,glGetError());
                    }
                    assertArrayEquals(color[0],color[1],"color mask="+mask+" water="+water);
                    assertArrayEquals(depth[0],depth[1],"depth mask="+mask+" water="+water);
                }
                System.out.println("MULTIDRAW_GPU masks=31 passes=2 color+depth identical renderer="+glGetString(GL_RENDERER));
            } finally {glDeleteBuffers(ebo);glDeleteVertexArrays(vao);}
        } finally {GL.setCapabilities(null);glfwDestroyWindow(window);glfwTerminate();}
    }
}
