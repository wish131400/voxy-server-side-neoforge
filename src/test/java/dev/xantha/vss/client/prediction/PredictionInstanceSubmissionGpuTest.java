package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;
import java.util.*;
import java.nio.*;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Lossless quad-instance dispatch experiment using the existing batch fixture.
 * Does not replace the production terrain/Iris/material/morph shader. */
@EnabledIfSystemProperty(named="vss.gpuTests",matches="true")
class PredictionInstanceSubmissionGpuTest {
    private static final int COUNT=512,WIDTH=128,HEIGHT=64;
    private static final String VERTEX="""
            #version 150
            uniform usamplerBuffer Quads;
            #ifdef BATCH
            uniform usamplerBuffer Owners;
            uniform isamplerBuffer Tiles;
            #else
            uniform ivec4 Tile;
            #endif
            flat out int Layer;
            out vec3 Color;
            void main() {
                #ifdef INSTANCED
                int q=gl_InstanceID,c=gl_VertexID%4;
                #else
                int q=gl_VertexID/4,c=gl_VertexID%4;
                #endif
                uvec4 data=texelFetch(Quads,q*3);
                #ifdef BATCH
                ivec4 tile=texelFetch(Tiles,int(texelFetch(Owners,q).r));
                #else
                ivec4 tile=Tile;
                #endif
                vec2 corner=vec2(c==1 || c==2 ? 1 : 0,c>=2 ? 1 : 0)*vec2(data.xy)/1024.0;
                gl_Position=vec4((vec2(tile.xy)+corner)/vec2(32,16)*2.0-1.0,0,1);
                Layer=tile.w;
                Color=vec3(float((tile.z*31)%255),float((tile.z*47)%255),float((tile.z*71)%255))/255.0;
            }
            """;
    private static final String FRAGMENT="""
            #version 150
            #ifdef BATCH
            uniform sampler2DArray Masks;
            #else
            uniform sampler2D Masks;
            #endif
            flat in int Layer;
            in vec3 Color;
            out vec4 fragColor;
            void main() {
                #ifdef BATCH
                float allowed=texelFetch(Masks,ivec3(0,0,Layer),0).r;
                #else
                float allowed=texelFetch(Masks,ivec2(0),0).r;
                #endif
                if(allowed<0.5) discard;
                fragColor=vec4(Color,1);
            }
            """;

    @Test void exactQuadInstancesMatchBatchedPayloadAndMeasureSubmissionCost() {
        assertTrue(glfwInit());glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,3);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,2);
        glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);
        long window=glfwCreateWindow(WIDTH,HEIGHT,"VSS batch submission prototype",0,0);assertNotEquals(0,window);
        var buffers=new ArrayList<Integer>();var textures=new ArrayList<Integer>();
        int vao=0,indices=0,query=0;
        try {
            glfwMakeContextCurrent(window);GL.createCapabilities();RenderSystem.initRenderThread();
            assertTrue(glGetInteger(GL_MAX_ARRAY_TEXTURE_LAYERS)>=COUNT);
            System.out.println("BATCH_GPU "+glGetString(GL_RENDERER)+" OpenGL="+glGetString(GL_VERSION));
            vao=glGenVertexArrays();glBindVertexArray(vao);indices=glGenBuffers();glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,indices);
            int[] elements=new int[COUNT*6];
            for(int i=0;i<COUNT;i++) { int[] quad={0,1,2,0,2,3};for(int j=0;j<6;j++) elements[i*6+j]=i*4+quad[j]; }
            glBufferData(GL_ELEMENT_ARRAY_BUFFER,elements,GL_STATIC_DRAW);
            int[] data=new int[COUNT*12],owners=new int[COUNT],metadata=new int[COUNT*4];
            byte[] masks=new byte[COUNT];int[] tilePayloads=new int[COUNT],tileMasks=new int[COUNT];
            for(int i=0;i<COUNT;i++) {
                data[i*12]=1024;data[i*12+1]=1024;owners[i]=i;
                metadata[i*4]=i%32;metadata[i*4+1]=i/32;metadata[i*4+2]=i;metadata[i*4+3]=i;
                masks[i]=(byte)(i%7==0?0:255);
                tilePayloads[i]=table(Arrays.copyOfRange(data,i*12,i*12+12),GL_RGBA32UI,buffers,textures);
                tileMasks[i]=glGenTextures();textures.add(tileMasks[i]);glBindTexture(GL_TEXTURE_2D,tileMasks[i]);
                glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
                var pixel=BufferUtils.createByteBuffer(1).put(masks[i]);pixel.flip();
                glTexImage2D(GL_TEXTURE_2D,0,GL_R8,1,1,0,GL_RED,GL_UNSIGNED_BYTE,pixel);
            }
            int payload=table(data,GL_RGBA32UI,buffers,textures),payloadBuffer=buffers.get(buffers.size()-1);
            int owner=table(owners,GL_R32UI,buffers,textures);
            int tiles=table(metadata,GL_RGBA32I,buffers,textures),tileBuffer=buffers.get(buffers.size()-1);
            int mask=glGenTextures();textures.add(mask);glBindTexture(GL_TEXTURE_2D_ARRAY,mask);
            glTexParameteri(GL_TEXTURE_2D_ARRAY,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D_ARRAY,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
            var maskBytes=BufferUtils.createByteBuffer(COUNT).put(masks);maskBytes.flip();glPixelStorei(GL_UNPACK_ALIGNMENT,1);
            glTexImage3D(GL_TEXTURE_2D_ARRAY,0,GL_R8,1,1,COUNT,0,GL_RED,GL_UNSIGNED_BYTE,maskBytes);
            var counts=BufferUtils.createIntBuffer(COUNT);var offsets=BufferUtils.createPointerBuffer(COUNT);
            for(int i=0;i<COUNT;i++) { counts.put(6);offsets.put(i*24L); }counts.flip();offsets.flip();
            glViewport(0,0,WIDTH,HEIGHT);glDisable(GL_DEPTH_TEST);glDisable(GL_BLEND);glDisable(GL_CULL_FACE);
            query=glGenQueries();
            try(var old=GlProgram.link("batch_reference",VERTEX,FRAGMENT);
                var batch=GlProgram.link("batch_prototype",VERTEX.replace("#version 150","#version 150\n#define BATCH"),FRAGMENT.replace("#version 150","#version 150\n#define BATCH"));
                var instances=GlProgram.link("instance_prototype",VERTEX.replace("#version 150","#version 150\n#define BATCH\n#define INSTANCED"),FRAGMENT.replace("#version 150","#version 150\n#define BATCH"))) {
                old.use();glUniform1i(old.uniform("Quads"),0);glUniform1i(old.uniform("Masks"),1);
                batch.use();glUniform1i(batch.uniform("Quads"),0);glUniform1i(batch.uniform("Masks"),1);
                glUniform1i(batch.uniform("Owners"),2);glUniform1i(batch.uniform("Tiles"),3);
                instances.use();glUniform1i(instances.uniform("Quads"),0);glUniform1i(instances.uniform("Masks"),1);
                glUniform1i(instances.uniform("Owners"),2);glUniform1i(instances.uniform("Tiles"),3);
                long[][] cpu=new long[3][60],gpu=new long[3][60];byte[][] pixels=new byte[3][];
                for(int frame=-30;frame<60;frame++) for(int order=0;order<3;order++) {
                    int mode=Math.floorMod(frame+order,3);
                    glClearColor(0,0,0,0);glClear(GL_COLOR_BUFFER_BIT);glFinish();
                    glBeginQuery(GL_TIME_ELAPSED,query);long start=System.nanoTime();
                    if(mode==0) {
                        old.use();int tile=old.uniform("Tile");
                        for(int i=0;i<COUNT;i++) {
                            bind(0,GL_TEXTURE_BUFFER,tilePayloads[i]);bind(1,GL_TEXTURE_2D,tileMasks[i]);
                            glUniform4i(tile,metadata[i*4],metadata[i*4+1],metadata[i*4+2],i);
                            glDrawElements(GL_TRIANGLES,6,GL_UNSIGNED_INT,0L);
                        }
                    } else {
                        (mode==1?batch:instances).use();bind(0,GL_TEXTURE_BUFFER,payload);bind(1,GL_TEXTURE_2D_ARRAY,mask);
                        bind(2,GL_TEXTURE_BUFFER,owner);bind(3,GL_TEXTURE_BUFFER,tiles);
                        if(mode==1) glMultiDrawElements(GL_TRIANGLES,counts,GL_UNSIGNED_INT,offsets);
                        else glDrawElementsInstanced(GL_TRIANGLES,6,GL_UNSIGNED_INT,0L,COUNT);
                    }
                    long elapsed=System.nanoTime()-start;glEndQuery(GL_TIME_ELAPSED);glFinish();
                    if(frame>=0) { cpu[mode][frame]=elapsed;gpu[mode][frame]=glGetQueryObjectui64(query,GL_QUERY_RESULT); }
                    if(frame==0 || frame==30) {
                        var image=BufferUtils.createByteBuffer(WIDTH*HEIGHT*4);glReadPixels(0,0,WIDTH,HEIGHT,GL_RGBA,GL_UNSIGNED_BYTE,image);
                        pixels[mode]=new byte[image.remaining()];image.get(pixels[mode]);
                        if(order==2) {
                            assertArrayEquals(pixels[0],pixels[1],"batch must preserve pixels");
                            assertArrayEquals(pixels[1],pixels[2],"instances must preserve pixels");
                        }
                    }
                    if(frame==20 && order==2) {
                        // A local upgrade updates one allocation and one mask layer, without rebuilding the page.
                        data[7*12]=512;metadata[7*4+2]=191;
                        glBindBuffer(GL_TEXTURE_BUFFER,buffers.get(7));glBufferSubData(GL_TEXTURE_BUFFER,0L,Arrays.copyOfRange(data,7*12,8*12));
                        glBindBuffer(GL_TEXTURE_BUFFER,payloadBuffer);glBufferSubData(GL_TEXTURE_BUFFER,7L*48,Arrays.copyOfRange(data,7*12,8*12));
                        glBindBuffer(GL_TEXTURE_BUFFER,tileBuffer);glBufferSubData(GL_TEXTURE_BUFFER,7L*16,Arrays.copyOfRange(metadata,7*4,8*4));
                        var pixel=BufferUtils.createByteBuffer(1).put((byte)255);pixel.flip();
                        bind(1,GL_TEXTURE_2D,tileMasks[7]);glTexSubImage2D(GL_TEXTURE_2D,0,0,0,1,1,GL_RED,GL_UNSIGNED_BYTE,pixel);
                        bind(1,GL_TEXTURE_2D_ARRAY,mask);glTexSubImage3D(GL_TEXTURE_2D_ARRAY,0,0,0,7,1,1,1,GL_RED,GL_UNSIGNED_BYTE,pixel);
                    }
                }
                for(var times:cpu) Arrays.sort(times);for(var times:gpu) Arrays.sort(times);
                System.out.printf(Locale.ROOT,"INSTANCE_PROTOTYPE quads=512 CPU_shared_ms=%.3f CPU_instanced_ms=%.3f GPU_shared_ms=%.3f GPU_instanced_ms=%.3f bytesAndPixels=equal (same packed quad payload; not full vegetation instancing or production FPS)%n",
                        cpu[1][30]/1e6,cpu[2][30]/1e6,gpu[1][30]/1e6,gpu[2][30]/1e6);
                assertEquals(GL_NO_ERROR,glGetError());
            }
        } finally {
            if(query!=0) glDeleteQueries(query);if(indices!=0) glDeleteBuffers(indices);if(vao!=0) glDeleteVertexArrays(vao);
            for(int texture:textures) glDeleteTextures(texture);for(int buffer:buffers) glDeleteBuffers(buffer);
            glfwDestroyWindow(window);glfwTerminate();
        }
    }
    private static int table(int[] data,int format,List<Integer> buffers,List<Integer> textures) {
        int buffer=glGenBuffers();buffers.add(buffer);glBindBuffer(GL_TEXTURE_BUFFER,buffer);glBufferData(GL_TEXTURE_BUFFER,data,GL_STATIC_DRAW);
        int texture=glGenTextures();textures.add(texture);glBindTexture(GL_TEXTURE_BUFFER,texture);glTexBuffer(GL_TEXTURE_BUFFER,format,buffer);return texture;
    }
    private static void bind(int unit,int target,int texture) { glActiveTexture(GL_TEXTURE0+unit);glBindTexture(target,texture); }
}
