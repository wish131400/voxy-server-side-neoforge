package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL43C.*;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

/** Experimental shared arena/MDI using complete production terrain shaders.
 * The only shader changes are per-tile parameter/payload addressing and mask layers.
 * Not installed in the renderer. No per-quad instancing or geometry approximation. */
@EnabledIfSystemProperty(named="vss.gpuTests",matches="true")
class PredictionIndirectSubmissionGpuTest {
    private static final int TILES=128,SIZE=256;
    private static int oldMorph,oldBounds;
    private static boolean blending;
    private final List<Integer> buffers=new ArrayList<>(),textures=new ArrayList<>();
    private static final String RECORDS="""
            struct BatchRecord { vec4 offsetSpacing; ivec4 data; vec4 morph; ivec4 flags; };
            layout(std430,binding=7) readonly buffer BatchRecords { BatchRecord records[]; };
            """;
    private static final String IRIS_EMIT="""
            layout(location=0) out vec4 color;
            void voxy_emitFragment(VoxyFragmentParameters p) { color=p.sampledColour*p.tinting; }
            """;

    @Test void compareFullShaderMultiTileSubmission()throws Exception {
        assertTrue(glfwInit());glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,4);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,6);
        glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);
        long window=glfwCreateWindow(SIZE,SIZE,"VSS indirect experiment",0,0);assertNotEquals(0,window);
        int vao=0,query=0;
        Path output=Path.of(System.getenv().getOrDefault("VSS_INDIRECT_BENCH","build/meridian-followup-indirect"));Files.createDirectories(output);
        var report=new StringBuilder("iris,quadsPerTile,ranges,raster,update,oldCpuMs,mdiCpuMs,oldGpuMs,mdiGpuMs,oldCompletedMs,mdiCompletedMs\n");
        try {
            glfwMakeContextCurrent(window);GL.createCapabilities();RenderSystem.initRenderThread();
            assertTrue(GL.getCapabilities().GL_ARB_shader_draw_parameters);
            System.out.println("INDIRECT_GPU "+glGetString(GL_RENDERER)+" "+glGetString(GL_VERSION));
            vao=glGenVertexArrays();glBindVertexArray(vao);query=glGenQueries();
            tex2(0,GL_RGBA8,2,2,GL_RGBA,new float[]{.8f,.2f,.1f,1,.2f,.7f,.1f,.7f,.4f,.2f,.8f,1,.8f,.8f,.2f,1});
            tex2(1,GL_RGBA8,1,1,GL_RGBA,new float[]{1,1,1,1});
            tex2(2,GL_RGBA32F,2,2,GL_RGBA,new float[]{0,0,0,0,0,0,1,1,0,0,0,0,.6f,.3f,.2f,1});
            tex2(5,GL_R32F,1,1,GL_RED,new float[]{0});
            glActiveTexture(GL_TEXTURE6);int vanilla=texture();glBindTexture(GL_TEXTURE_3D,vanilla);
            glTexParameteri(GL_TEXTURE_3D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_3D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
            glTexImage3D(GL_TEXTURE_3D,0,GL_R8,2,2,2,0,GL_RED,GL_FLOAT,new float[8]);
            float[] clearDepth=new float[SIZE*SIZE];Arrays.fill(clearDepth,1);
            int mainDepth=tex2(7,GL_R32F,SIZE,SIZE,GL_RED,clearDepth);
            int[] masks=new int[TILES];float[] allMasks=new float[TILES*16];
            for(int t=0;t<TILES;t++) {
                float[] values=new float[16];for(int i=0;i<16;i++)values[i]=(t+i)%7==0?0:128f/255f;
                System.arraycopy(values,0,allMasks,t*16,16);masks[t]=tex2(3,GL_R8,4,4,GL_RED,values);
            }
            glActiveTexture(GL_TEXTURE3);int layered=texture();glBindTexture(GL_TEXTURE_2D_ARRAY,layered);
            glTexParameteri(GL_TEXTURE_2D_ARRAY,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D_ARRAY,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
            glTexImage3D(GL_TEXTURE_2D_ARRAY,0,GL_R8,4,4,TILES,0,GL_RED,GL_FLOAT,allMasks);
            int ebo=buffer(),metadata=buffer(),indirect=buffer();
            glBindBuffer(GL_SHADER_STORAGE_BUFFER,metadata);glBufferData(GL_SHADER_STORAGE_BUFFER,TILES*64L,GL_DYNAMIC_DRAW);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER,7,metadata);
            for(boolean iris:new boolean[]{false,true}) {
                String vertex=iris?PredictionTerrainProgram.irisVertex("vec2 vssTaaShift(){return vec2(0.0);}"):source("TERRAIN_VERTEX");
                String fragment=iris?PredictionTerrainProgram.irisFragment()+IRIS_EMIT:source("TERRAIN_FRAGMENT");
                try(var old=compile(vertex,fragment);var batch=compile(batchVertex(vertex),batchFragment(fragment))) {
                    for(var program:List.of(old,batch))setup(program,iris,mainDepth);
                    old.use();int oldId=glGetInteger(GL_CURRENT_PROGRAM);
                    oldMorph=glGetUniformLocation(oldId,"TerrainMorph");oldBounds=glGetUniformLocation(oldId,"MorphBounds");
                    for(int quads:new int[]{32,1024,4096}) {
                        int wordsPerTile=quads*12+28;
                        int[] shared=new int[wordsPerTile*TILES],individual=new int[TILES],individualBuffers=new int[TILES];
                        for(int t=0;t<TILES;t++) {
                            int[] words=geometry(t,quads);
                            System.arraycopy(words,0,shared,t*wordsPerTile,words.length);
                            individual[t]=table(words);individualBuffers[t]=buffers.get(buffers.size()-1);
                        }
                        int arena=table(shared),arenaBuffer=buffers.get(buffers.size()-1);
                        int[][][] updateWords=new int[2][TILES/16][];
                        for(int parity=0;parity<2;parity++)for(int t=0;t<TILES;t+=16)
                            updateWords[parity][t/16]=geometry(t+parity,quads);
                        int[] elements=new int[quads*6];for(int q=0;q<quads;q++)for(int c=0;c<6;c++)elements[q*6+c]=q*4+new int[]{0,1,2,0,2,3}[c];
                        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,ebo);glBufferData(GL_ELEMENT_ARRAY_BUFFER,elements,GL_STATIC_DRAW);
                        ByteBuffer records=records(quads,wordsPerTile);glBindBuffer(GL_SHADER_STORAGE_BUFFER,metadata);glBufferSubData(GL_SHADER_STORAGE_BUFFER,0,records);
                        for(boolean split:new boolean[]{false,true}) {
                            var ranges=split?new PredictionDrawRanges(new int[]{0,quads/4,quads/2,quads*3/4,quads},new int[]{quads/4,quads/4,quads/4,quads-quads*3/4,0},5)
                                    :new PredictionDrawRanges(new int[]{0},new int[]{quads},1);
                            int[] commands=new int[TILES*ranges.first.length*5];int cursor=0;
                            // Preserve per-tile and per-range ordering, including the blended pass.
                            for(int t=0;t<TILES;t++)for(int i=0;i<ranges.first.length;i++) {
                                commands[cursor++]=ranges.count[i]*6;commands[cursor++]=1;commands[cursor++]=ranges.first[i]*6;
                                commands[cursor++]=0;commands[cursor++]=t;
                            }
                            glBindBuffer(GL_DRAW_INDIRECT_BUFFER,indirect);glBufferData(GL_DRAW_INDIRECT_BUFFER,commands,GL_DYNAMIC_DRAW);
                            for(boolean blend:new boolean[]{false,true}) {
                                byte[][] colors=new byte[2][];float[][] depths=new float[2][];
                                for(int mode=0;mode<2;mode++) {
                                    frame(blend);draw(mode,old,batch,individual,masks,arena,layered,ranges,commands.length/5,quads);
                                    colors[mode]=pixels();depths[mode]=depths();assertEquals(GL_NO_ERROR,glGetError());
                                }
                                assertArrayEquals(colors[0],colors[1],"full shader colors iris="+iris+" split="+split+" blend="+blend);
                                assertArrayEquals(depths[0],depths[1],"full shader depth");
                                assertTrue(nonBlack(colors[0])>1000,"must actually render independent tiles");
                                // A visible tile changes while both buffer layouts remain resident.
                                int changedTile=72;
                                for(int mode=0;mode<2;mode++) {
                                    glBindBuffer(GL_TEXTURE_BUFFER,mode==0?individualBuffers[changedTile]:arenaBuffer);
                                    glBufferSubData(GL_TEXTURE_BUFFER,mode==0?0L:(long)changedTile*wordsPerTile*4,geometry(changedTile+9,quads));
                                }
                                byte[][] updated=new byte[2][];float[][] updatedDepth=new float[2][];
                                for(int mode=0;mode<2;mode++) {
                                    frame(blend);draw(mode,old,batch,individual,masks,arena,layered,ranges,commands.length/5,quads);
                                    updated[mode]=pixels();updatedDepth[mode]=depths();
                                }
                                assertFalse(Arrays.equals(colors[0],updated[0]),"local update must visibly change the fixture");
                                assertArrayEquals(updated[0],updated[1],"updated full shader colors");
                                assertArrayEquals(updatedDepth[0],updatedDepth[1],"updated full shader depth");
                                for(int mode=0;mode<2;mode++) {
                                    glBindBuffer(GL_TEXTURE_BUFFER,mode==0?individualBuffers[changedTile]:arenaBuffer);
                                    glBufferSubData(GL_TEXTURE_BUFFER,mode==0?0L:(long)changedTile*wordsPerTile*4,geometry(changedTile,quads));
                                }
                            }
                            for(boolean raster:new boolean[]{false,true})for(boolean update:new boolean[]{false,true}) {
                                if(raster && quads>32)continue; // Larger cases isolate vertex/driver cost rather than artificial overdraw.
                                long[][] cpu=new long[2][31],gpu=new long[2][31],completed=new long[2][31];
                                for(int round=-30;round<31;round++)for(int order=0;order<2;order++) {
                                    int mode=Math.floorMod(round+order,2);frame(false);
                                    if(!raster)glEnable(GL_RASTERIZER_DISCARD);else glDisable(GL_RASTERIZER_DISCARD);
                                    glFinish();glBeginQuery(GL_TIME_ELAPSED,query);long start=System.nanoTime();
                                    if(update) {
                                        // 1/16 of resident tiles change each frame: same upload bytes on both paths.
                                        for(int t=0;t<TILES;t+=16) {
                                            int[] changed=updateWords[round&1][t/16];
                                            glBindBuffer(GL_TEXTURE_BUFFER,mode==0?individualBuffers[t]:arenaBuffer);
                                            glBufferSubData(GL_TEXTURE_BUFFER,mode==0?0L:(long)t*wordsPerTile*4,changed);
                                        }
                                        if(mode==1) {glBindBuffer(GL_SHADER_STORAGE_BUFFER,metadata);glBufferSubData(GL_SHADER_STORAGE_BUFFER,0,records);}
                                    }
                                    draw(mode,old,batch,individual,masks,arena,layered,ranges,commands.length/5,quads);
                                    long submit=System.nanoTime()-start;glEndQuery(GL_TIME_ELAPSED);glFinish();long total=System.nanoTime()-start;
                                    long elapsed=glGetQueryObjectui64(query,GL_QUERY_RESULT);
                                    if(round>=0){cpu[mode][round]=submit;gpu[mode][round]=elapsed;completed[mode][round]=total;}
                                }
                                for(var a:cpu)Arrays.sort(a);for(var a:gpu)Arrays.sort(a);for(var a:completed)Arrays.sort(a);
                                String line=String.format(Locale.ROOT,"%s,%d,%d,%s,%s,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f%n",iris,quads,ranges.first.length,raster,update,
                                        cpu[0][15]/1e6,cpu[1][15]/1e6,gpu[0][15]/1e6,gpu[1][15]/1e6,completed[0][15]/1e6,completed[1][15]/1e6);
                                report.append(line);System.out.print("INDIRECT "+line);
                                glDisable(GL_RASTERIZER_DISCARD);
                            }
                        }
                        // Release each test size rather than retaining every arena until the context closes.
                        for(int t=0;t<TILES;t++){glDeleteTextures(individual[t]);textures.remove((Integer)individual[t]);glDeleteBuffers(individualBuffers[t]);buffers.remove((Integer)individualBuffers[t]);}
                        glDeleteTextures(arena);textures.remove((Integer)arena);glDeleteBuffers(arenaBuffer);buffers.remove((Integer)arenaBuffer);
                    }
                }
            }
            Files.writeString(output.resolve("benchmark.csv"),report);
            assertEquals(GL_NO_ERROR,glGetError());
        } finally {
            if(query!=0)glDeleteQueries(query);if(vao!=0)glDeleteVertexArrays(vao);
            for(int t:textures)glDeleteTextures(t);for(int b:buffers)glDeleteBuffers(b);
            glfwDestroyWindow(window);glfwTerminate();
        }
    }
    private static String source(String name)throws Exception {var f=PredictionTerrainProgram.class.getDeclaredField(name);f.setAccessible(true);return (String)f.get(null);}
    private static PredictionTerrainProgram compile(String v,String f)throws Exception {
        var c=PredictionTerrainProgram.class.getDeclaredConstructor(String.class,String.class);c.setAccessible(true);return c.newInstance(v,f);
    }
    private static String batchVertex(String s) {
        s=s.replace("#version 150","#version 460 core").replace("uniform usamplerBuffer QuadPayload;",RECORDS+"\nflat out int BatchSlot;\nuniform usamplerBuffer QuadPayload;");
        s=s.replace("uniform vec3 TileOffset;","#define TileOffset records[gl_BaseInstance].offsetSpacing.xyz")
                .replace("uniform float Spacing;","#define Spacing records[gl_BaseInstance].offsetSpacing.w")
                .replace("uniform int CellAxis;","#define CellAxis records[gl_BaseInstance].data.x")
                .replace("uniform int UseAverage;","#define UseAverage records[gl_BaseInstance].data.y")
                .replace("uniform vec2 TerrainMorph;","#define TerrainMorph records[gl_BaseInstance].morph.xy")
                .replace("uniform vec2 MorphBounds;","#define MorphBounds records[gl_BaseInstance].morph.zw")
                .replace("texelFetch(QuadPayload, ","texelFetch(QuadPayload, records[gl_BaseInstance].data.z + ")
                .replace("int quad = gl_VertexID >> 2;","BatchSlot=gl_BaseInstance;\nint quad = gl_VertexID >> 2;");
        return s;
    }
    private static String batchFragment(String s) {
        return s.replace("#version 150","#version 460 core").replace("uniform sampler2D Yield;",RECORDS+"\nflat in int BatchSlot;\nuniform sampler2DArray Yield;")
                .replace("uniform float Spacing;","#define Spacing records[BatchSlot].offsetSpacing.w")
                .replace("uniform int CellAxis;","#define CellAxis records[BatchSlot].data.x")
                .replace("uniform int UseAverage;","#define UseAverage records[BatchSlot].data.y")
                .replace("uniform bool ReplaceBoundaryWalls;","#define ReplaceBoundaryWalls (records[BatchSlot].flags.x!=0)")
                .replace("texelFetch(Yield, cell, 0)","texelFetch(Yield, ivec3(cell,records[BatchSlot].data.w), 0)");
    }
    private static void setup(PredictionTerrainProgram p,boolean iris,int depth) {
        p.use();p.setSamplers(0,1,2,3,4);p.setFrame(new float[]{0,0,0,1},1e7f,2e7f,0,1e7f,false,1);p.setOpaqueAlpha(1);
        var projection=VssLodProjection.of(new Matrix4f().perspective((float)Math.toRadians(100),1,.05f,65536));
        p.setCamera(new Matrix4f().lookAlong(0,-1,0,0,0,-1),projection.matrix());
        if(iris)p.setIrisFrame(new Matrix4f(projection.matrix()).invert(),SIZE,SIZE,false,new int[256],1);
        p.bindMainDepth(depth,projection);int program=glGetInteger(GL_CURRENT_PROGRAM);
        glUniform1i(glGetUniformLocation(program,"VanillaMask"),6);glUniform1i(glGetUniformLocation(program,"ExactCoverage"),5);
        glUniform3i(glGetUniformLocation(program,"VanillaMaskSize"),2,2,2);glUniform3f(glGetUniformLocation(program,"VanillaMaskOrigin"),1e6f,1e6f,1e6f);
        glUniform1fv(glGetUniformLocation(program,"DirectionalTint[0]"),new float[]{1,1,1,1,1,1,1});
    }
    private static void frame(boolean blend) {
        blending=blend;
        glViewport(0,0,SIZE,SIZE);glDisable(GL_CULL_FACE);glEnable(GL_DEPTH_TEST);glDepthFunc(GL_GREATER);glDepthMask(true);
        if(blend){glEnable(GL_BLEND);glBlendFunc(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA);}else glDisable(GL_BLEND);
        glClearColor(0,0,0,1);glClearDepth(0);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
    }
    private static void draw(int mode,PredictionTerrainProgram old,PredictionTerrainProgram batch,int[] individual,int[] masks,int arena,int layered,PredictionDrawRanges ranges,int commands,int quads) {
        if(mode==0) {
            old.use();old.setOpaqueAlpha(blending?0:1);for(int t=0;t<TILES;t++) {
                glActiveTexture(GL_TEXTURE4);glBindTexture(GL_TEXTURE_BUFFER,individual[t]);glActiveTexture(GL_TEXTURE3);glBindTexture(GL_TEXTURE_2D,masks[t]);
                old.setTile(t%16*32-256,-360,t/16*32-128,8,4,t%3==0);
                glUniform2f(oldMorph,quads*3,(t%3)*.25f);
                glUniform2f(oldBounds,80,112);old.setBoundaryReplacement(t%2==0);
                PredictionRenderer.submitRanges(ranges);
            }
        }else {
            batch.use();batch.setOpaqueAlpha(blending?0:1);glActiveTexture(GL_TEXTURE4);glBindTexture(GL_TEXTURE_BUFFER,arena);glActiveTexture(GL_TEXTURE3);glBindTexture(GL_TEXTURE_2D_ARRAY,layered);
            glMultiDrawElementsIndirect(GL_TRIANGLES,GL_UNSIGNED_INT,0L,commands,20);
        }
    }
    private static ByteBuffer records(int quads,int words) {
        var b=BufferUtils.createByteBuffer(TILES*64);
        for(int t=0;t<TILES;t++) {
            b.putFloat(t%16*32-256).putFloat(-360).putFloat(t/16*32-128).putFloat(8);
            b.putInt(4).putInt(t%3==0?1:0).putInt(t*words/4).putInt(t);
            b.putFloat(quads*3).putFloat((t%3)*.25f).putFloat(80).putFloat(112);
            b.putInt(t%2==0?1:0).putInt(0).putInt(0).putInt(0);
        }
        return b.flip();
    }
    private static int[] geometry(int t,int quads) {
        int[] data=new int[quads*12+28];int y=32768+(96+t%5)*4;
        for(int q=0;q<quads;q++) {
            int x=q%4*8,z=q/4%4*8;int rgb=((t*31+q*7)&255)<<16|((t*17+q*11)&255)<<8|((t*13+q*19)&255);
            int flags=1|(q%11==0?1<<22:0)|(q%13==0?1<<26:0);
            int[] quad={x|((x+8)<<16),(x+8)|(x<<16),z|(z<<16),(z+8)|((z+8)<<16),y|(y<<16),y|(y<<16),flags,rgb,q%16,rgb|0x1000000,rgb,rgb};
            System.arraycopy(quad,0,data,q*12,12);
        }
        for(int i=0;i<25;i++)data[quads*12+i]=(i%5-2)*128;
        return data;
    }
    private int buffer(){int b=glGenBuffers();buffers.add(b);return b;}
    private int texture(){int t=glGenTextures();textures.add(t);return t;}
    private int table(int[] data){int b=buffer();glBindBuffer(GL_TEXTURE_BUFFER,b);glBufferData(GL_TEXTURE_BUFFER,data,GL_DYNAMIC_DRAW);int t=texture();glBindTexture(GL_TEXTURE_BUFFER,t);glTexBuffer(GL_TEXTURE_BUFFER,GL_RGBA32UI,b);return t;}
    private int tex2(int unit,int format,int w,int h,int channels,float[] values){glActiveTexture(GL_TEXTURE0+unit);int t=texture();glBindTexture(GL_TEXTURE_2D,t);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);glTexImage2D(GL_TEXTURE_2D,0,format,w,h,0,channels,GL_FLOAT,values);return t;}
    private static byte[] pixels(){var b=BufferUtils.createByteBuffer(SIZE*SIZE*4);glReadPixels(0,0,SIZE,SIZE,GL_RGBA,GL_UNSIGNED_BYTE,b);byte[] r=new byte[b.remaining()];b.get(r);return r;}
    private static float[] depths(){var b=BufferUtils.createFloatBuffer(SIZE*SIZE);glReadPixels(0,0,SIZE,SIZE,GL_DEPTH_COMPONENT,GL_FLOAT,b);float[] r=new float[b.remaining()];b.get(r);return r;}
    private static int nonBlack(byte[] data){int n=0;for(int i=0;i<data.length;i+=4)if(data[i]!=0||data[i+1]!=0||data[i+2]!=0)n++;return n;}
}
