package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL43C.*;
import java.nio.*;
import java.util.*;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

@EnabledIfSystemProperty(named="vss.gpuTests",matches="true")
class PredictionProductionBatchGpuTest {
    @Test void productionArenaMaskBatchAndFallbackArePixelExact()throws Exception {
        assertTrue(glfwInit());glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,4);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,6);
        long window=glfwCreateWindow(128,128,"production batch",0,0);assertNotEquals(0,window);
        var gpuTiles=new ArrayList<PredictionGpuTile>();var textures=new ArrayList<Integer>();
        try {
            glfwMakeContextCurrent(window);GL.createCapabilities();RenderSystem.initRenderThread();
            PredictionMeshCodecTest.bootstrap();
            try(var program=PredictionTerrainProgram.createBatch();var legacy=PredictionTerrainProgram.create();var batch=new PredictionIndirectBatch()) {
                int vao=glGenVertexArrays();glBindVertexArray(vao);
                int ebo=glGenBuffers();glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,ebo);
                var entries=new ArrayList<PredictionRenderer.Draw>();int max=0;
                for(int t=0;t<128;t++) {
                    var mesh=PredictionMeshCodecTest.fixture();int n=(mesh.cellAxis()+1)*(mesh.cellAxis()+1);
                    var key=new PredictionTileManager.PredictionTileKey(net.minecraft.world.level.Level.OVERWORLD,t%4,t/4,0);
                    var tile=new PredictionTileManager.PredictionTile(key,new int[n],new int[n],new ClientColumnSample[n],mesh,
                            new PredictionDepthBound(60,80),0,t+1,mesh.cellAxis(),1);
                    var gpu=new PredictionGpuTile(key);gpuTiles.add(gpu);assertTrue(gpu.ensureMesh(tile));assertNotNull(gpu.arenaSlice());
                    boolean[] allowed=new boolean[mesh.cellCount()];Arrays.fill(allowed,true);gpu.updateCoverage(allowed);
                    byte[] mask=new byte[allowed.length];for(int i=0;i<mask.length;i++)mask[i]=(byte)((i+t)%7==0?0:128);
                    gpu.updateBoundaryCoverage(mask);
                    entries.add(new PredictionRenderer.Draw(tile,gpu,allowed,false,VssLodFaceGroup.ALL,0,null));max=Math.max(max,gpu.quadCount());
                }
                int[] indices=new int[max*6];int[] corners={0,1,2,0,2,3};
                for(int q=0;q<max;q++)for(int c=0;c<6;c++)indices[q*6+c]=q*4+corners[c];
                glBufferData(GL_ELEMENT_ARRAY_BUFFER,indices,GL_STATIC_DRAW);
                tex(textures,0,1,1,GL_RGBA8,GL_RGBA,new float[]{1,1,1,1});
                tex(textures,1,1,1,GL_RGBA8,GL_RGBA,new float[]{1,1,1,1});
                tex(textures,2,2,2,GL_RGBA32F,GL_RGBA,new float[16]);
                tex(textures,5,128,128,GL_R32F,GL_RED,new float[128*128]);
                float[] clear=new float[128*128];Arrays.fill(clear,1);int depth=tex(textures,7,128,128,GL_R32F,GL_RED,clear);
                var camera=new Vec3(32,145,16);
                for(var setup:List.of(legacy,program)) {
                setup.use();setup.setSamplers(0,1,2,3,4);
                setup.setFrame(new float[]{0,0,0,1},1e7f,2e7f,0,1e7f,false,1);
                var p=VssLodProjection.of(new Matrix4f().perspective((float)Math.toRadians(80),1,.05f,65536));
                setup.setCamera(new Matrix4f().lookAlong(0,-1,0,0,0,-1),p.matrix());setup.bindMainDepth(depth,p);
                int handle=glGetInteger(GL_CURRENT_PROGRAM);
                glUniform1i(glGetUniformLocation(handle,"ExactCoverage"),5);glUniform1i(glGetUniformLocation(handle,"VanillaMask"),6);
                glUniform3i(glGetUniformLocation(handle,"VanillaMaskSize"),1,1,1);glUniform3f(glGetUniformLocation(handle,"VanillaMaskOrigin"),1e6f,1e6f,1e6f);
                glUniform1fv(glGetUniformLocation(handle,"DirectionalTint[0]"),new float[]{1,1,1,1,1,1,1});
                }
                program.use();program.setSamplers(0,1,2,3,4);
                program.setFrame(new float[]{0,0,0,1},1e7f,2e7f,0,1e7f,false,1);
                var projection=VssLodProjection.of(new Matrix4f().perspective((float)Math.toRadians(80),1,.05f,65536));
                program.setCamera(new Matrix4f().lookAlong(0,-1,0,0,0,-1),projection.matrix());program.bindMainDepth(depth,projection);
                int id=glGetInteger(GL_CURRENT_PROGRAM);
                glUniform1i(glGetUniformLocation(id,"ExactCoverage"),5);glUniform1i(glGetUniformLocation(id,"VanillaMask"),6);
                glActiveTexture(GL_TEXTURE6);int volume=glGenTextures();textures.add(volume);glBindTexture(GL_TEXTURE_3D,volume);
                glTexParameteri(GL_TEXTURE_3D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_3D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
                glTexImage3D(GL_TEXTURE_3D,0,GL_R8,1,1,1,0,GL_RED,GL_FLOAT,new float[]{0});
                glUniform3i(glGetUniformLocation(id,"VanillaMaskSize"),1,1,1);glUniform3f(glGetUniformLocation(id,"VanillaMaskOrigin"),1e6f,1e6f,1e6f);
                glUniform1fv(glGetUniformLocation(id,"DirectionalTint[0]"),new float[]{1,1,1,1,1,1,1});
                for(boolean water:new boolean[]{false,true})for(boolean split:new boolean[]{false,true}) {
                    byte[][] pixels=new byte[2][];float[][] depths=new float[2][];
                    for(int mode=0;mode<2;mode++) {
                        var activeProgram=mode==0?legacy:program;activeProgram.use();
                        glViewport(0,0,128,128);glDisable(GL_CULL_FACE);glEnable(GL_DEPTH_TEST);glDepthFunc(GL_GREATER);glDepthMask(true);
                        if(water){glEnable(GL_BLEND);glBlendFunc(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA);}else glDisable(GL_BLEND);
                        glClearDepth(0);glClearColor(0,0,0,1);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
                        activeProgram.batch(false);activeProgram.setOpaqueAlpha(water?0:1);
                        int sentinel=glGenBuffers();glBindBuffer(GL_SHADER_STORAGE_BUFFER,sentinel);glBufferData(GL_SHADER_STORAGE_BUFFER,256,GL_STATIC_DRAW);
                        glBindBufferRange(GL_SHADER_STORAGE_BUFFER,7,sentinel,0,128);glBindBuffer(GL_DRAW_INDIRECT_BUFFER,sentinel);
                        if(mode==1)batch.begin(program);
                        for(int i=0;i<entries.size();i++) {
                            var draw=entries.get(water?entries.size()-1-i:i);var p=draw.gpu().packed();
                            var ranges=p.drawRanges(water,split?5:VssLodFaceGroup.ALL);if(ranges.quads==0)continue;
                            if(mode==1 && i%3!=1){assertTrue(batch.add(draw,ranges,camera,water,true,Long.MAX_VALUE));continue;}
                            if(mode==1)batch.flush();
                            draw.gpu().bindTerrain(activeProgram);draw.gpu().bindYield(3);
                            activeProgram.setTile((float)(draw.tile().baseBlockX()-camera.x),(float)-camera.y,(float)(draw.tile().baseBlockZ()-camera.z),1,p.cellAxis(),true);
                            activeProgram.setBoundaryReplacement(!water);PredictionRenderer.submitRanges(ranges);
                        }
                        if(mode==1){assertTrue(batch.end()>0);assertEquals(sentinel,glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING,7));assertEquals(128,glGetInteger64i(GL_SHADER_STORAGE_BUFFER_SIZE,7));assertEquals(sentinel,glGetInteger(GL_DRAW_INDIRECT_BUFFER_BINDING));}
                        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,7,0);glBindBuffer(GL_SHADER_STORAGE_BUFFER,0);glBindBuffer(GL_DRAW_INDIRECT_BUFFER,0);glDeleteBuffers(sentinel);
                        var bytes=BufferUtils.createByteBuffer(128*128*4);glReadPixels(0,0,128,128,GL_RGBA,GL_UNSIGNED_BYTE,bytes);
                        pixels[mode]=new byte[bytes.remaining()];bytes.get(pixels[mode]);
                        var values=BufferUtils.createFloatBuffer(128*128);glReadPixels(0,0,128,128,GL_DEPTH_COMPONENT,GL_FLOAT,values);
                        depths[mode]=new float[values.remaining()];values.get(depths[mode]);assertEquals(GL_NO_ERROR,glGetError());
                    }
                    assertArrayEquals(pixels[0],pixels[1],"colors water="+water+" split="+split);assertArrayEquals(depths[0],depths[1]);
                    if(!water&&!split){int visible=0;for(int i=0;i<pixels[0].length;i+=4)if(pixels[0][i]!=0||pixels[0][i+1]!=0||pixels[0][i+2]!=0)visible++;assertTrue(visible>100);}
                }
                benchmark(program,legacy,batch,entries,camera,VssLodFaceGroup.ALL);
                benchmark(program,legacy,batch,entries,camera,5);
                glDeleteBuffers(ebo);glDeleteVertexArrays(vao);
            }
        } finally {
            for(var gpu:gpuTiles)gpu.close();glFinish();PredictionTerrainArena.SHARED.reap();PredictionTerrainArena.SHARED.close();
            for(int texture:textures)glDeleteTextures(texture);glfwDestroyWindow(window);glfwTerminate();
        }
    }
    private static void benchmark(PredictionTerrainProgram program,PredictionTerrainProgram legacy,PredictionIndirectBatch batch,
                                  List<PredictionRenderer.Draw> entries,Vec3 camera,int faces) {
        int query=glGenQueries();long[][] cpu=new long[2][31],gpu=new long[2][31];
        try {
            glEnable(GL_RASTERIZER_DISCARD);program.setOpaqueAlpha(1);
            for(int round=-30;round<31;round++)for(int order=0;order<2;order++) {
                int mode=Math.floorMod(round+order,2);glFinish();glBeginQuery(GL_TIME_ELAPSED,query);
                long start=System.nanoTime();
                var active=mode==1?program:legacy;active.use();active.setOpaqueAlpha(1);
                if(mode==1)batch.begin(program);else legacy.batch(false);
                for(var draw:entries) {
                    var packed=draw.gpu().packed();var ranges=packed.drawRanges(false,faces);
                    if(mode==1){batch.add(draw,ranges,camera,false,true,Long.MAX_VALUE);continue;}
                    draw.gpu().bindTerrain(legacy);draw.gpu().bindYield(3);
                    legacy.setTile((float)(draw.tile().baseBlockX()-camera.x),(float)-camera.y,
                            (float)(draw.tile().baseBlockZ()-camera.z),1,packed.cellAxis(),true);
                    legacy.setBoundaryReplacement(true);PredictionRenderer.submitRanges(ranges);
                }
                if(mode==1)batch.end();long elapsed=System.nanoTime()-start;glEndQuery(GL_TIME_ELAPSED);
                long device=glGetQueryObjectui64(query,GL_QUERY_RESULT);
                if(round>=0){cpu[mode][round]=elapsed;gpu[mode][round]=device;}
            }
            for(var values:cpu)Arrays.sort(values);for(var values:gpu)Arrays.sort(values);
            System.out.printf(Locale.ROOT,"PRODUCTION_BATCH faces=%d tiles=%d quads=%d cpuMs=%.6f->%.6f gpuMs=%.6f->%.6f%n",
                    faces,entries.size(),entries.stream().mapToInt(d->d.gpu().quadCount()).sum(),cpu[0][15]/1e6,cpu[1][15]/1e6,gpu[0][15]/1e6,gpu[1][15]/1e6);
        }finally{glDisable(GL_RASTERIZER_DISCARD);glDeleteQueries(query);}
    }
    private static int tex(List<Integer> textures,int unit,int w,int h,int format,int channels,float[] data){
        glActiveTexture(GL_TEXTURE0+unit);int texture=glGenTextures();textures.add(texture);glBindTexture(GL_TEXTURE_2D,texture);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
        glTexImage2D(GL_TEXTURE_2D,0,format,w,h,0,channels,GL_FLOAT,data);return texture;
    }
}
