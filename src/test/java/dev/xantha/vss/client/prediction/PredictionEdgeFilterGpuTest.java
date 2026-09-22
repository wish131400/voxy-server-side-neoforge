package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

@EnabledIfSystemProperty(named="vss.gpuTests", matches="true")
class PredictionEdgeFilterGpuTest {
    @Test void filtersSubpixelSnowLinesPreservesZoomDepthStateAndResizes() {
        assertTrue(glfwInit());
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,3); glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,3);
        glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);
        long window=glfwCreateWindow(64,64,"VSS antialias regression",0,0);
        assertNotEquals(0,window);
        try {
            glfwMakeContextCurrent(window); GL.createCapabilities(); RenderSystem.initRenderThread();
            verifyAtlasSampler();
            try(var filter=new PredictionEdgeFilter()) {
                for(int size:new int[]{64,96,64}) {
                    var target=new TextureTarget(size,size,true,false);
                    try {
                        for(int mode=0;mode<4;mode++) {
                            var projection=new Matrix4f().perspective((float)Math.toRadians(mode==2?1:70),1,.05F,1000);
                            float distance=mode==0?1:80;
                            var clip=projection.transform(new org.joml.Vector4f(0,0,-distance,1));
                            float depth=mode==3?1:clip.z/clip.w*.5F+.5F;
                            byte[] source=pattern(size,size,0);
                            upload(target,source,depth);
                            float[] before=depths(size,size);
                            int oldDraw=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
                            int sampler=glGenSamplers(); glBindSampler(0,sampler);
                            glEnable(GL_SCISSOR_TEST); glScissor(0,0,1,1);
                            glColorMask(false,true,false,false); glDepthMask(true);
                            filter.render(target,projection);
                            assertTrue(glIsEnabled(GL_SCISSOR_TEST)); assertTrue(glGetBoolean(GL_DEPTH_WRITEMASK));
                            assertEquals(oldDraw,glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING));
                            assertEquals(sampler,glGetIntegeri(GL_SAMPLER_BINDING,0));
                            glDisable(GL_SCISSOR_TEST); glColorMask(true,true,true,true);
                            glBindSampler(0,0); glDeleteSamplers(sampler);
                            byte[] result=colors(target);
                            assertArrayEquals(before,depths(size,size),"antialiasing must never change handoff/occlusion depth");
                            for(int i=3;i<result.length;i+=4) assertEquals(source[i],result[i],"alpha is not edge-filtered");
                            if(mode==1) {
                                double input=contrast(source,size,size),output=contrast(result,size,size);
                                assertTrue(output<input*.8,"parallel snow ledges must filter even when the diagonal gradient is zero: "+output+"/"+input);
                                save(source,result,size,size);
                            } else assertArrayEquals(source,result,"near, spyglass and sky pixels stay exact; mode="+mode);
                            assertEquals(GL_NO_ERROR,glGetError());
                        }
                    } finally { target.destroyBuffers(); }
                }
                benchmark(filter,1920,1080);
                benchmark(filter,3840,2160);
            }
        } finally {glfwDestroyWindow(window);glfwTerminate();}
    }

    private static void benchmark(PredictionEdgeFilter filter,int width,int height) {
        var target=new TextureTarget(width,height,true,false);
        try {
            var projection=new Matrix4f().perspective((float)Math.toRadians(70),(float)width/height,.05F,10000);
            var clip=projection.transform(new org.joml.Vector4f(0,0,-4000,1));
            byte[] source=pattern(width,height,0);
            long[] times=new long[15]; int query=glGenQueries();
            try {
                for(int i=-3;i<times.length;i++) {
                    upload(target,source,clip.z/clip.w*.5F+.5F);
                    glBeginQuery(GL_TIME_ELAPSED,query);filter.render(target,projection);glEndQuery(GL_TIME_ELAPSED);
                    long ns=glGetQueryObjectui64(query,GL_QUERY_RESULT);
                    if(i>=0)times[i]=ns;
                }
                java.util.Arrays.sort(times);
                System.out.printf(java.util.Locale.ROOT,"ANTIALIAS_GPU %dx%d medianMs=%.3f p93Ms=%.3f colorMiB=%.2f gpu=%s%n",
                        width,height,times[7]/1e6,times[13]/1e6,width*(double)height*4/(1024*1024),glGetString(GL_RENDERER));
            } finally {glDeleteQueries(query);}
        } finally {target.destroyBuffers();}
    }

    private static void verifyAtlasSampler() {
        int[] textures={glGenTextures(),glGenTextures()};
        int foreign=glGenSamplers();
        try {
            for(int mode=0;mode<2;mode++) {
                glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,textures[mode]);
                glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,16,16,0,GL_RGBA,GL_UNSIGNED_BYTE,(ByteBuffer)null);
                glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);
                glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
                glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAX_LEVEL,mode==0?0:4);
                if(mode==1)glGenerateMipmap(GL_TEXTURE_2D);
                glBindSampler(0,foreign);
                try(var state=new PredictionIrisBridge.State(2)) {
                    PredictionAtlasSampler.bind(textures[mode]);
                    int sampler=glGetIntegeri(GL_SAMPLER_BINDING,0);
                    assertNotEquals(foreign,sampler);
                    assertEquals(mode==0?GL_LINEAR:GL_LINEAR_MIPMAP_LINEAR,glGetSamplerParameteri(sampler,GL_TEXTURE_MIN_FILTER));
                    assertEquals(GL_NEAREST,glGetTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER),"shared atlas settings must not change");
                    assertEquals(GL_NEAREST,glGetSamplerParameteri(sampler,GL_TEXTURE_MAG_FILTER),"near pixels retain nearest magnification");
                }
                assertEquals(foreign,glGetIntegeri(GL_SAMPLER_BINDING,0));
            }
        } finally {
            glBindSampler(0,0);glDeleteSamplers(foreign);for(int t:textures)glDeleteTextures(t);
        }
    }

    private static byte[] pattern(int width,int height,int phase) {
        byte[] data=new byte[width*height*4];
        for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
            int level=x<width/2?((y+phase)%2==0?210:30):90;
            int i=(y*width+x)*4;data[i]=data[i+1]=data[i+2]=(byte)level;data[i+3]=(byte)177;
        }
        return data;
    }
    private static void upload(TextureTarget target,byte[] data,float depth) {
        target.bindWrite(true);glDisable(GL_SCISSOR_TEST);glDepthMask(true);glClearDepth(depth);glClear(GL_DEPTH_BUFFER_BIT);
        glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,target.getColorTextureId());
        ByteBuffer bytes=BufferUtils.createByteBuffer(data.length);bytes.put(data).flip();
        glTexSubImage2D(GL_TEXTURE_2D,0,0,0,target.width,target.height,GL_RGBA,GL_UNSIGNED_BYTE,bytes);
    }
    private static byte[] colors(TextureTarget target) {
        target.bindWrite(true);glBindFramebuffer(GL_READ_FRAMEBUFFER,target.frameBufferId);
        ByteBuffer out=BufferUtils.createByteBuffer(target.width*target.height*4);
        glReadPixels(0,0,target.width,target.height,GL_RGBA,GL_UNSIGNED_BYTE,out);
        byte[] data=new byte[out.remaining()];out.get(data);return data;
    }
    private static float[] depths(int width,int height) {
        var out=BufferUtils.createFloatBuffer(width*height);glReadPixels(0,0,width,height,GL_DEPTH_COMPONENT,GL_FLOAT,out);
        float[] data=new float[out.remaining()];out.get(data);return data;
    }
    private static double contrast(byte[] pixels,int w,int h) {
        long total=0;
        for(int y=2;y<h-2;y++)for(int x=2;x<w/2-2;x++)total+=Math.abs((pixels[(y*w+x)*4]&255)-(pixels[((y+1)*w+x)*4]&255));
        return total;
    }
    private static void save(byte[] before,byte[] after,int w,int h) {
        try {
            var out=java.nio.file.Path.of("build/reports/antialias");java.nio.file.Files.createDirectories(out);
            var img=new java.awt.image.BufferedImage(w*2,h,java.awt.image.BufferedImage.TYPE_INT_RGB);
            for(int side=0;side<2;side++)for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
                var data=side==0?before:after;int i=(y*w+x)*4;
                img.setRGB(x+side*w,h-y-1,(data[i]&255)<<16|(data[i+1]&255)<<8|(data[i+2]&255));
            }
            javax.imageio.ImageIO.write(img,"png",out.resolve("snow-lines-before-after.png").toFile());
        } catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
    }
}
