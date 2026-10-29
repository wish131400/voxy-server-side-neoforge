package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL46C.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL;

/** Executes the patched native queue and parent fallback on a real GPU under contention. */
class VoxyTraversalQueueGpuTest {
    private static final String PATH="voxy:lod/hierarchical/traversal_dev.comp";
    private static final int PARENTS=2048, CANARY=0x5a5a5a5a;

    @Test void realBackendCompilesAndQueuePressureRetainsWholeGroupsOrTheirParentMesh() throws Exception {
        assertTrue(glfwInit());glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,4);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,6);
        long window=glfwCreateWindow(16,16,"VSS traversal capacity verification",0,0);assertNotEquals(0,window);
        try(ZipFile jar=new ZipFile(System.getProperty("vss.voxyJar"))) {
            glfwMakeContextCurrent(window);GL.createCapabilities();
            System.out.println("Traversal GPU: "+glGetString(GL_RENDERER));
            String original=expand(jar,PATH,new HashSet<>());
            String patched=VoxyTraversalShader.patch(PATH,original);
            assertSame(patched,VoxyTraversalShader.patch(PATH,patched));
            assertEquals(VoxyPredictionBridge.patchSubdivisionRequests(PATH,patched),
                    VoxyTraversalShader.patch(PATH,VoxyPredictionBridge.patchSubdivisionRequests(PATH,original)));
            String defines="\n#define LOCAL_SIZE_BITS 5\n#define MAX_ITERATIONS 5\n#define MAX_REQUEST_QUEUE_SIZE 50\n#define HIZ_BINDING 0\n";
            int binding=0;
            for(String name:List.of("SCENE_UNIFORM_BINDING","REQUEST_QUEUE_BINDING","RENDER_QUEUE_BINDING",
                    "NODE_DATA_BINDING","NODE_QUEUE_INDEX_BINDING","NODE_QUEUE_META_BINDING",
                    "NODE_QUEUE_SOURCE_BINDING","NODE_QUEUE_SINK_BINDING","RENDER_TRACKER_BINDING","STATISTICS_BUFFER_BINDING"))
                defines+="#define "+name+" "+binding+++"\n";
            for(String mode:List.of("", "#define HAS_STATISTICS\n", "#define TAA\nvec2 getTAA(){return vec2(0);}\n")) {
                String full=patched.replaceFirst("\n",Matcher.quoteReplacement("\n"+defines+mode));
                int program=program(removePrintf(full));glDeleteProgram(program);
            }
            String queue=patched.substring(patched.indexOf("#define SENTINAL_OUT_OF_BOUNDS"),patched.indexOf("#define SIMPLE_QUEUE"));
            String structure=patched.substring(patched.indexOf("struct UnpackedNode"),patched.indexOf("#define NULL_NODE"));
            StringBuilder helpers=new StringBuilder(structure).append("\n#define NULL_MESH 16777215u\n#define EMPTY_MESH 16777214u\n#define EMPTY_QUEUE_ID 16777214u\n");
            for(String signature:List.of("bool hasMesh(","bool isEmptyMesh(","bool childListIsEmpty(","uint getMesh(",
                    "uint getId(","uint getChildCount(","uint getChildPtr(")) helpers.append(function(patched,signature));
            String enqueue=function(patched,"void enqueueChildren(in UnpackedNode node) {");
            String render=function(patched,"void enqueueSelfForRender(in UnpackedNode node) {");
            String probe="""
                    #version 460 core
                    #define LOCAL_SIZE_BITS 5
                    #define LOCAL_SIZE 32
                    #define MAX_ITERATIONS 5
                    #define NODE_QUEUE_INDEX_BINDING 0
                    #define NODE_QUEUE_META_BINDING 0
                    #define NODE_QUEUE_SOURCE_BINDING 1
                    #define NODE_QUEUE_SINK_BINDING 2
                    layout(local_size_x=32) in;
                    %s
                    %s
                    layout(std430,binding=3) buffer Render {uint renderQueueIndex;uint renderQueue[];};
                    layout(std430,binding=4) buffer Tracker {uint lastRenderFrame[];};
                    layout(std430,binding=5) buffer Sources {uint sources[];};
                    const uint frameId=17u;
                    const uint renderQueueMaxSize=2048u;
                    void enqueueSelfForRender(in UnpackedNode node);
                    %s
                    %s
                    void main(){
                        uint id=gl_GlobalInvocationID.x;
                        sources[id]=getCurrentNode();
                        UnpackedNode node;
                        node.nodeId=id;node.meshPtr=id+30000u;node.childPtr=id*8u;node.flags=7u<<2u;
                        enqueueChildren(node);
                    }
                    """.formatted(queue,helpers,enqueue,render);
            int program=program(removePrintf(probe));
            try {
                execute(program,PARENTS*8,0);
                for(int i=0;i<5;i++)execute(program,1013,0);
                execute(program,1013,4);
            } finally {glDeleteProgram(program);}
            verifyRealBufferReplacement();
            if(Boolean.getBoolean("vss.queueTiming"))compareQueueTime(original,patched);
            assertEquals(GL_NO_ERROR,glGetError());
        } finally {glfwMakeContextCurrent(0);glfwDestroyWindow(window);glfwTerminate();}
    }

    private static void compareQueueTime(String original,String patched) {
        String header="""
                #version 460 core
                #define LOCAL_SIZE_BITS 5
                #define LOCAL_SIZE 32
                #define MAX_ITERATIONS 5
                #define NODE_QUEUE_INDEX_BINDING 0
                #define NODE_QUEUE_META_BINDING 0
                #define NODE_QUEUE_SOURCE_BINDING 1
                #define NODE_QUEUE_SINK_BINDING 2
                layout(local_size_x=32) in;
                """;
        for(String source:List.of(original,patched)) {
            boolean bounded=source==patched;
            String queue=source.substring(source.indexOf("#define SENTINAL_OUT_OF_BOUNDS"),source.indexOf("#define SIMPLE_QUEUE"));
            int p=program(removePrintf(header+queue+"\nvoid main(){"+(bounded?"if(!vssReserveNodes(8u))return;":"pushNodesInit(8u);")
                    +"for(uint i=0u;i<8u;i++)pushNode(gl_GlobalInvocationID.x*8u+i);}"));
            int[] buffers={buffer(0,new int[20]),buffer(1,new int[16]),buffer(2,new int[32768*8])};
            long[] timings=new long[30];int query=glGenQueries();
            try {
                glUseProgram(p);glUniform1ui(0,0);
                for(int i=-5;i<timings.length;i++) {
                    glNamedBufferSubData(buffers[0],0,new int[20]);
                    glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
                    glBeginQuery(GL_TIME_ELAPSED,query);glDispatchCompute(1024,1,1);
                    glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);glEndQuery(GL_TIME_ELAPSED);
                    long nanos=glGetQueryObjectui64(query,GL_QUERY_RESULT);if(i>=0)timings[i]=nanos;
                }
                Arrays.sort(timings);System.out.println("Queue timing 32768 sibling groups, "+(bounded?"bounded":"native")+": median "+timings[15]/1e6+" ms");
            } finally {glUseProgram(0);glDeleteQueries(query);for(int b:buffers)glDeleteBuffers(b);glDeleteProgram(p);}
        }
    }

    private static void execute(int program,int capacity,int iteration) {
        int[] meta=new int[20];meta[iteration*4+3]=PARENTS;
        int[] source=new int[16];for(int i=0;i<16;i++)source[i]=i+123;
        int[] sink=new int[capacity+16];Arrays.fill(sink,CANARY);
        int[] buffers={buffer(0,meta),buffer(1,source),buffer(2,sink),buffer(3,new int[PARENTS+1]),
                buffer(4,new int[PARENTS]),buffer(5,new int[PARENTS])};
        try {
            glBindBufferRange(GL_SHADER_STORAGE_BUFFER,2,buffers[2],0,capacity*4L);
            glUseProgram(program);glUniform1ui(0,iteration);glDispatchCompute(PARENTS/32,1,1);
            glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT|GL_SHADER_STORAGE_BARRIER_BIT);
            glGetNamedBufferSubData(buffers[0],0,meta);glGetNamedBufferSubData(buffers[2],0,sink);
            int[] render=new int[PARENTS+1],tracker=new int[PARENTS],sources=new int[PARENTS];
            glGetNamedBufferSubData(buffers[3],0,render);glGetNamedBufferSubData(buffers[4],0,tracker);glGetNamedBufferSubData(buffers[5],0,sources);
            int accepted=iteration==4?0:capacity/8;
            if(iteration<4) {
                assertEquals(PARENTS*8,meta[(iteration+1)*4+3],"Reservation counter is never rolled back under contention");
                assertEquals(accepted,meta[(iteration+1)*4],"Only accepted sibling groups dispatch child work");
            }
            assertEquals(PARENTS-accepted,render[0],"Every rejected group retains its parent mesh");
            boolean[] seen=new boolean[PARENTS];
            for(int i=0;i<accepted*8;i+=8) {
                int parent=sink[i]/8;assertTrue(parent>=0&&parent<PARENTS);assertFalse(seen[parent]);seen[parent]=true;
                for(int j=0;j<8;j++)assertEquals(parent*8+j,sink[i+j]);
            }
            for(int i=0;i<render[0];i++) {int parent=render[i+1]-30000;assertFalse(seen[parent]);seen[parent]=true;assertEquals(17,tracker[parent]);}
            for(int i=0;i<PARENTS;i++) {assertTrue(seen[i]);assertEquals(i<16?i+123:-1,sources[i],"Source bound");}
            for(int i=accepted*8;i<sink.length;i++)assertEquals(iteration<4&&i<capacity?-1:CANARY,sink[i],
                    "Partial tail is empty; the trailing canary is untouched");
        } finally {glUseProgram(0);for(int b:buffers)glDeleteBuffers(b);}
    }

    public static class Buffer {
        public final int id;final long bytes;boolean freed;
        public Buffer(long bytes){this.bytes=bytes;id=glCreateBuffers();glNamedBufferStorage(id,bytes,GL_DYNAMIC_STORAGE_BIT);}
        public long size(){return bytes;}
        public void free(){assertFalse(freed);freed=true;glDeleteBuffers(id);}
    }
    public static class Tree {
        int[] idx2topNodeMapping=new int[200000];
        Buffer topNodeIds=new Buffer(800000),scratchQueueA=new Buffer(800000),scratchQueueB=new Buffer(800000);
        VoxyTraversalCapacityTest.Nodes nodeManager=new VoxyTraversalCapacityTest.Nodes();
    }
    private static void verifyRealBufferReplacement() {
        Tree tree=new Tree();var plan=VoxyCapacityPlan.create(16,0,8192,-64,2031);
        var manager=new VoxyTraversalCapacity(tree,plan);
        int[] ids=new int[128];for(int i=0;i<ids.length;i++){ids[i]=i*73;tree.idx2topNodeMapping[i]=ids[i];}
        glNamedBufferSubData(tree.topNodeIds.id,0,ids);manager.changed(ids.length,ids.length,true);
        Buffer old=tree.topNodeIds;
        manager.prepare(VoxyCapacityPlan.create(128,0,65536,-64,2031));
        int[] actual=new int[ids.length];glGetNamedBufferSubData(tree.topNodeIds.id,0,actual);
        assertArrayEquals(ids,actual);assertTrue(old.freed);assertTrue(tree.idx2topNodeMapping.length>=261285);
        for(int i=0;i<ids.length;i++)assertEquals(ids[i],tree.idx2topNodeMapping[i]);
        tree.topNodeIds.free();tree.scratchQueueA.free();tree.scratchQueueB.free();
    }
    private static int buffer(int binding,int[] values) {
        int b=glCreateBuffers();glNamedBufferStorage(b,values,GL_DYNAMIC_STORAGE_BIT);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,binding,b);return b;
    }
    private static String removePrintf(String s){return s.replaceAll("printf\\(\\\"[^\\\"]*\\\"[^;]*\\);", "");}
    private static String function(String source,String signature) {
        int start=source.indexOf(signature);assertTrue(start>=0,signature);
        int end=source.indexOf('{',start)+1,depth=1;
        while(depth!=0){char c=source.charAt(end++);if(c=='{')depth++;else if(c=='}')depth--;}
        return source.substring(start,end)+"\n";
    }
    private static String expand(ZipFile jar,String id,Set<String> included) throws Exception {
        if(!included.add(id))return "";
        String[] pair=id.split(":",2);String path="assets/"+pair[0]+"/shaders/"+pair[1];
        String source=new String(jar.getInputStream(jar.getEntry(path)).readAllBytes(),StandardCharsets.UTF_8).replace("\r\n","\n");
        Matcher m=Pattern.compile("#import <([^>]+)>").matcher(source);StringBuilder out=new StringBuilder();
        while(m.find())m.appendReplacement(out,Matcher.quoteReplacement(expand(jar,m.group(1),included)));
        m.appendTail(out);return out.toString();
    }
    private static int program(String source) {
        int shader=glCreateShader(GL_COMPUTE_SHADER);glShaderSource(shader,source);glCompileShader(shader);
        assertEquals(GL_TRUE,glGetShaderi(shader,GL_COMPILE_STATUS),glGetShaderInfoLog(shader));
        int p=glCreateProgram();glAttachShader(p,shader);glLinkProgram(p);glDeleteShader(shader);
        assertEquals(GL_TRUE,glGetProgrami(p,GL_LINK_STATUS),glGetProgramInfoLog(p));return p;
    }
}
