package dev.xantha.vss.client.prediction;

import org.joml.Matrix4f;
import static org.lwjgl.opengl.GL45C.*;

/** Current-frame, conservative reversed-depth HiZ. Never waits for GPU visibility on the CPU. */
final class PredictionOcclusionCuller implements AutoCloseable {
    private static final String REDUCE = """
            #version 430
            layout(local_size_x=8, local_size_y=8) in;
            layout(binding=8) uniform sampler2D Source;
            layout(r32f, binding=0) writeonly uniform image2D Destination;
            uniform int SourceLevel;
            uniform int Scale;
            void main() {
                ivec2 p=ivec2(gl_GlobalInvocationID.xy);
                if(any(greaterThanEqual(p,imageSize(Destination)))) return;
                ivec2 size=textureSize(Source,SourceLevel);
                float d=1.0;
                for(int y=0;y<Scale;y++) for(int x=0;x<Scale;x++) {
                    ivec2 s=p*Scale+ivec2(x,y);
                    if(Scale==2) s=min(s,size-1); // A mip axis may already have reached one texel.
                    float v=any(greaterThanEqual(s,size)) ? 0.0 : texelFetch(Source,s,SourceLevel).r;
                    // Unknown, clear and padded pixels must never establish an occluder.
                    d=min(d, isnan(v)||isinf(v) ? 0.0 : v);
                }
                imageStore(Destination,p,vec4(d));
            }
            """;
    private static final String FILTER = """
            #version 430
            layout(local_size_x=64) in;
            struct Box { vec4 lo; vec4 hi; };
            layout(std430,binding=0) readonly buffer Bounds { Box boxes[]; };
            layout(std430,binding=1) readonly buffer InputCommands { uint source[]; };
            layout(std430,binding=2) writeonly buffer OutputCommands { uint result[]; };
            layout(std430,binding=3) buffer Statistics { uint candidates; uint hidden; uint quads; uint hiddenQuads; };
            layout(std430,binding=4) readonly buffer Parents { Box parents[]; };
            layout(binding=8) uniform sampler2D Pyramid;
            uniform mat4 Transform;
            uniform vec3 CameraOffset;
            uniform ivec2 ViewSize;
            uniform int Levels;
            uniform uint Count;
            uniform bool Sample;
            uniform bool Hierarchical;
            shared bool parentHidden;
            shared uint testedCount, hiddenCount, testedQuads, rejectedQuads;
            bool occluded(Box box) {
                if(any(isnan(box.lo))||any(isnan(box.hi))||any(isinf(box.lo))||any(isinf(box.hi))) return false;
                vec3 boxLo = box.lo.xyz + CameraOffset;
                vec3 boxHi = box.hi.xyz + CameraOffset;
                if(all(lessThanEqual(boxLo,vec3(0)))&&all(greaterThanEqual(boxHi,vec3(0)))) return false;
                vec2 lo=vec2(1e30),hi=vec2(-1e30);
                float nearest=0.0;
                for(int i=0;i<8;i++) {
                    vec3 p=vec3((i&1)==0?box.lo.x:box.hi.x,(i&2)==0?box.lo.y:box.hi.y,(i&4)==0?box.lo.z:box.hi.z) + CameraOffset;
                    vec4 clip=Transform*vec4(p,1);
                    // Keep near-plane intersections; perspective bounds are unsafe across W=0.
                    if(any(isnan(clip))||any(isinf(clip))||clip.w<=0.0||clip.z>=clip.w||clip.z<=-clip.w) return false;
                    vec3 ndc=clip.xyz/clip.w;
                    lo=min(lo,ndc.xy); hi=max(hi,ndc.xy);
                    nearest=max(nearest,ndc.z*0.5+0.5);
                }
                vec2 a=(lo*0.5+0.5)*vec2(ViewSize)-vec2(1);
                vec2 b=(hi*0.5+0.5)*vec2(ViewSize)+vec2(1);
                // The parent tile may cross the frustum while this entire segment is outside it.
                if(any(lessThan(b,vec2(0)))||any(greaterThanEqual(a,vec2(ViewSize)))) return true;
                a=clamp(a,vec2(0),vec2(ViewSize-1)); b=clamp(b,vec2(0),vec2(ViewSize-1));
                float span=max(b.x-a.x,b.y-a.y);
                // Up to 8x8 cells reduces unrelated sky contamination at coarse silhouettes.
                int level=clamp(int(ceil(log2(max(span/16.0,1.0)))),0,Levels-1);
                float cell=4.0*exp2(float(level));
                ivec2 first=ivec2(floor(a/cell)),last=ivec2(floor(b/cell));
                float farthest=1.0;
                // Read every covered texel, including silhouette/sky holes, not just corners.
                for(int y=first.y;y<=last.y;y++) for(int x=first.x;x<=last.x;x++)
                    farthest=min(farthest,texelFetch(Pyramid,ivec2(x,y),level).r);
                return nearest+max(2e-7,nearest*2e-4)<farthest;
            }
            void main() {
                uint lane=gl_LocalInvocationID.x;
                if(lane==0u) {
                    parentHidden=Hierarchical && occluded(parents[gl_WorkGroupID.x]);
                    testedCount=0u; hiddenCount=0u; testedQuads=0u; rejectedQuads=0u;
                }
                barrier();
                uint id=gl_GlobalInvocationID.x;
                if(id<Count) {
                    uint start=id*5u;
                    bool hide=parentHidden || occluded(boxes[id]);
                    for(uint i=0u;i<5u;i++) result[start+i]=i==0u&&hide?0u:source[start+i];
                    if(Sample) {
                        atomicAdd(testedCount,1u); atomicAdd(testedQuads,source[start]/6u);
                        if(hide) { atomicAdd(hiddenCount,1u); atomicAdd(rejectedQuads,source[start]/6u); }
                    }
                }
                barrier();
                if(Sample && lane==0u) {
                    atomicAdd(candidates,testedCount); atomicAdd(quads,testedQuads);
                    atomicAdd(hidden,hiddenCount); atomicAdd(hiddenQuads,rejectedQuads);
                }
            }
            """;
    private int reduce, filter, pyramid, width, height, levels;
    private int sourceLevelUniform, scaleUniform, transformUniform, cameraOffsetUniform,
            viewUniform, sampleUniform, countUniform, levelsUniform, hierarchicalUniform;
    private int depth, viewWidth, viewHeight, rebuilds, lastRebuilds;
    private long frame, sampleFrame;
    private final int[] lastStats = new int[4];
    private final Sample[] samples = {new Sample(), new Sample(), new Sample()};
    private Sample activeSample;
    private State state;
    private boolean failed;
    private int filteringProgram, filteringQuery = -1;
    private long filteringStart;
    private boolean filtering;

    boolean begin(int depth, int width, int height, Matrix4f transform) {
        lastRebuilds=0;
        if (failed || Boolean.getBoolean("vss.disablePredictionOcclusion") || depth <= 0 || width <= 0 || height <= 0
                || !org.lwjgl.opengl.GL.getCapabilities().OpenGL45) return false;
        if(glGetInteger(GL_CLIP_DEPTH_MODE)!=GL_NEGATIVE_ONE_TO_ONE || glGetInteger(GL_CLIP_ORIGIN)!=GL_LOWER_LEFT) return false;
        state = new State();
        try {
            if (reduce == 0) {
                reduce = compile(REDUCE);
                sourceLevelUniform=glGetUniformLocation(reduce,"SourceLevel"); scaleUniform=glGetUniformLocation(reduce,"Scale");
            }
            if (filter == 0) {
                filter = compile(FILTER);
                transformUniform=glGetUniformLocation(filter,"Transform"); viewUniform=glGetUniformLocation(filter,"ViewSize");
                cameraOffsetUniform=glGetUniformLocation(filter,"CameraOffset");
                sampleUniform=glGetUniformLocation(filter,"Sample"); countUniform=glGetUniformLocation(filter,"Count");
                levelsUniform=glGetUniformLocation(filter,"Levels");
                hierarchicalUniform=glGetUniformLocation(filter,"Hierarchical");
            }
            this.depth=depth; viewWidth=width; viewHeight=height; rebuilds=0; frame++;
            poll();
            activeSample=null;
            if (frame % 30 == 1) for (Sample sample : samples) if (sample.fence == 0) {
                if (sample.buffer == 0) {
                    sample.buffer=glCreateBuffers(); glNamedBufferData(sample.buffer,16,GL_STREAM_READ);
                }
                glClearNamedBufferData(sample.buffer,GL_R32UI,GL_RED_INTEGER,GL_UNSIGNED_INT,new int[]{0});
                activeSample=sample; sample.frame=frame; break;
            }
            glUseProgram(filter);
            glUniformMatrix4fv(transformUniform,false,transform.get(new float[16]));
            glUniform2i(viewUniform,width,height);
            glUniform1i(sampleUniform,activeSample!=null?1:0);
            if (activeSample!=null) glBindBufferBase(GL_SHADER_STORAGE_BUFFER,3,activeSample.buffer);
            glBindSampler(8,0);
            return true;
        } catch (RuntimeException failure) {
            failed=true;
            dev.xantha.vss.common.VSSLogger.error("Prediction occlusion unavailable; using ordinary terrain submission", failure);
            end();
            return false;
        } finally { if (state!=null) { glUseProgram(state.program); state.restoreTexture(); } }
    }

    void rebuild() {
        if (state==null) return;
        long timingStart = PredictionRenderTimings.start();
        int timingQuery = PredictionRenderTimings.gpuStart(PredictionRenderTimings.Stage.HZB_REDUCE);
        try {
        int w=powerOfTwo((viewWidth+3)/4),h=powerOfTwo((viewHeight+3)/4);
        if (pyramid==0 || width!=w || height!=h) {
            if(pyramid!=0) glDeleteTextures(pyramid);
            width=w; height=h; levels=32-Integer.numberOfLeadingZeros(Math.max(w,h));
            pyramid=glCreateTextures(GL_TEXTURE_2D);
            glTextureStorage2D(pyramid,levels,GL_R32F,w,h);
            glTextureParameteri(pyramid,GL_TEXTURE_MIN_FILTER,GL_NEAREST_MIPMAP_NEAREST);
            glTextureParameteri(pyramid,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
        }
        int previous=glGetInteger(GL_CURRENT_PROGRAM);
        glBindSampler(8,0);
        glUseProgram(reduce);
        for(int level=0;level<levels;level++) {
            glBindTextureUnit(8,level==0?depth:pyramid);
            glUniform1i(sourceLevelUniform,Math.max(0,level-1));
            glUniform1i(scaleUniform,level==0?4:2);
            glBindImageTexture(0,pyramid,level,false,0,GL_WRITE_ONLY,GL_R32F);
            glDispatchCompute((Math.max(1,w>>level)+7)/8,(Math.max(1,h>>level)+7)/8,1);
            glMemoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT | GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
        }
        rebuilds++;
        glUseProgram(previous);
        state.restoreTexture();
        } finally {
            PredictionRenderTimings.gpuEnd(timingQuery);
            PredictionRenderTimings.end(PredictionRenderTimings.Stage.HZB_REDUCE, timingStart);
        }
    }

    int filter(int bounds, int input, int output, int commands) {
        if(state==null || rebuilds==0) return input;
        beginFiltering();
        try {
            return dispatch(bounds, input, output, commands, 0);
        } finally { endFiltering(); }
    }

    boolean beginFiltering() {
        if(state==null || rebuilds==0) return false;
        filteringStart = PredictionRenderTimings.start();
        filteringQuery = PredictionRenderTimings.gpuStart(PredictionRenderTimings.Stage.GPU_FILTER);
        filteringProgram=glGetInteger(GL_CURRENT_PROGRAM);
        filtering=true;
        glUseProgram(filter);
        glBindSampler(8,0);
        glBindTextureUnit(8,pyramid);
        glUniform1i(levelsUniform,levels);
        return true;
    }

    int dispatch(int bounds, int input, int output, int commands, int parents) {
        if (!filtering) return input;
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,0,bounds);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,1,input);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,2,output);
        if (parents != 0) glBindBufferBase(GL_SHADER_STORAGE_BUFFER,4,parents);
        glUniform1i(hierarchicalUniform,parents != 0 ? 1 : 0);
        glUniform1ui(countUniform,commands);
        glDispatchCompute((commands+63)/64,1,1);
        return output;
    }

    void endFiltering() {
        if (!filtering) return;
        glMemoryBarrier(GL_COMMAND_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT);
        glUseProgram(filteringProgram);
        state.restoreTexture();
        filtering=false;
        PredictionRenderTimings.gpuEnd(filteringQuery);
        PredictionRenderTimings.end(PredictionRenderTimings.Stage.GPU_FILTER, filteringStart);
    }

    void end() {
        if(state==null) return;
        endFiltering();
        if(activeSample!=null) {
            glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
            activeSample.fence=glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE,0);
            activeSample=null;
        }
        lastRebuilds=rebuilds;
        state.restore(); state=null;
    }

    private void poll() {
        for(Sample s:samples) if(s.fence!=0) {
            int status=glClientWaitSync(s.fence,0,0);
            if(status==GL_ALREADY_SIGNALED || status==GL_CONDITION_SATISFIED) {
                if(s.frame>sampleFrame) { glGetNamedBufferSubData(s.buffer,0,lastStats); sampleFrame=s.frame; }
                glDeleteSync(s.fence); s.fence=0;
            }
        }
    }

    void setCameraOffset(float x, float y, float z) {
        if (state != null && cameraOffsetUniform >= 0) {
            glUseProgram(filter);
            glUniform3f(cameraOffsetUniform, x, y, z);
            glUseProgram(state.program);
        }
    }
    void skippedFrame() { lastRebuilds=0; frame++; poll(); }
    String diagnostics() {
        return ",occlusion="+(failed?"fallback":lastRebuilds>0?"current-frame-hiz":"off")
                +",hizBuilds="+lastRebuilds+",sampleAge="+(sampleFrame==0?-1:frame-sampleFrame)
                +",testedCommands="+Integer.toUnsignedLong(lastStats[0])+",hiddenCommands="+Integer.toUnsignedLong(lastStats[1])
                +",testedQuads="+Integer.toUnsignedLong(lastStats[2])+",hiddenQuads="+Integer.toUnsignedLong(lastStats[3]);
    }
    private static int powerOfTwo(int n) { return n<=1?1:Integer.highestOneBit(n-1)<<1; }
    private static int compile(String source) {
        int shader=glCreateShader(GL_COMPUTE_SHADER),program=0;
        try {
            glShaderSource(shader,source); glCompileShader(shader);
            if(glGetShaderi(shader,GL_COMPILE_STATUS)==GL_FALSE) throw new IllegalStateException(glGetShaderInfoLog(shader));
            program=glCreateProgram(); glAttachShader(program,shader); glLinkProgram(program);
            if(glGetProgrami(program,GL_LINK_STATUS)==GL_FALSE) throw new IllegalStateException(glGetProgramInfoLog(program));
            return program;
        } catch(RuntimeException failure) { if(program!=0) glDeleteProgram(program); throw failure; }
        finally { glDeleteShader(shader); }
    }
    @Override public void close() {
        end();
        if(reduce!=0)glDeleteProgram(reduce); if(filter!=0)glDeleteProgram(filter); if(pyramid!=0)glDeleteTextures(pyramid);
        for(Sample s:samples) { if(s.fence!=0)glDeleteSync(s.fence); if(s.buffer!=0)glDeleteBuffers(s.buffer); }
    }
    private static final class Sample { int buffer; long fence,frame; }

    /** Save once per opaque pass. Restore the ownership sampler before every terrain draw. */
    private static final class State {
        final int program=glGetInteger(GL_CURRENT_PROGRAM),storage=glGetInteger(GL_SHADER_STORAGE_BUFFER_BINDING);
        final int texture=glGetIntegeri(GL_TEXTURE_BINDING_2D,8),sampler=glGetIntegeri(GL_SAMPLER_BINDING,8);
        final int image=glGetIntegeri(GL_IMAGE_BINDING_NAME,0),level=glGetIntegeri(GL_IMAGE_BINDING_LEVEL,0),
                layered=glGetIntegeri(GL_IMAGE_BINDING_LAYERED,0),layer=glGetIntegeri(GL_IMAGE_BINDING_LAYER,0),
                access=glGetIntegeri(GL_IMAGE_BINDING_ACCESS,0),format=glGetIntegeri(GL_IMAGE_BINDING_FORMAT,0);
        final int[] buffers=new int[5]; final long[] starts=new long[5],sizes=new long[5];
        State() { for(int i=0;i<5;i++) { buffers[i]=glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING,i);
            starts[i]=glGetInteger64i(GL_SHADER_STORAGE_BUFFER_START,i); sizes[i]=glGetInteger64i(GL_SHADER_STORAGE_BUFFER_SIZE,i); } }
        void restoreTexture() {
            // Binding zero with glBindTextureUnit would also erase unrelated cube/array bindings.
            int active=glGetInteger(GL_ACTIVE_TEXTURE);
            glActiveTexture(GL_TEXTURE8); glBindTexture(GL_TEXTURE_2D,texture); glActiveTexture(active);
            glBindSampler(8,sampler);
        }
        void restore() {
            glUseProgram(program); restoreTexture();
            glBindImageTexture(0,image,level,layered!=0,layer,access,format);
            for(int i=0;i<5;i++) if(buffers[i]!=0&&sizes[i]>0) glBindBufferRange(GL_SHADER_STORAGE_BUFFER,i,buffers[i],starts[i],sizes[i]);
                else glBindBufferBase(GL_SHADER_STORAGE_BUFFER,i,buffers[i]);
            glBindBuffer(GL_SHADER_STORAGE_BUFFER,storage);
        }
    }
}
