package dev.xantha.vss.client.prediction;

import java.nio.ByteBuffer;
import java.util.*;
import static org.lwjgl.opengl.GL43C.*;

/** Bounded shared geometry pages. Retired slices are reusable only after a zero-timeout fence succeeds. */
final class PredictionTerrainArena {
    // Larger pages keep opaque tiles together, reducing texture switches and
    // MDI submissions. This remains a lazy allocation, not a startup reserve.
    static final int PAGE_BYTES = configuredPageMiB() * 1024 * 1024;
    private static int configuredPageMiB() {
        int requested = Integer.getInteger("vss.indirectPageMiB", 64);
        return requested == 8 || requested == 16 || requested == 32 || requested == 64
                ? requested : 64;
    }
    /** Shared pages replace the same bytes that would otherwise live in one
     * texture buffer per tile. They are allocated lazily, so this is a ceiling
     * rather than an eager VRAM reservation. A JVM property is provided for
     * low-VRAM systems and deterministic GPU tests. */
    static final int MAX_PAGES = configuredMaxPages();
    /** Opaque and transparent geometry use separate page pools. Water is a
     * small bounded pool so transparent sorting never forces opaque pages to
     * fragment. Pages are still lazy and only allocated when water exists. */
    private static final int WATER_MAX_PAGES = Math.max(1,
            Math.min(MAX_PAGES, Integer.getInteger("vss.indirectWaterPages", 2)));
    static final PredictionTerrainArena SHARED = new PredictionTerrainArena(MAX_PAGES, "opaque");
    static final PredictionTerrainArena WATER = new PredictionTerrainArena(WATER_MAX_PAGES, "water");

    private static int configuredMaxPages() {
        int mib = Integer.getInteger("vss.indirectArenaMiB", 2048);
        mib = Math.max(128, Math.min(2048, mib));
        return Math.max(1, (int) ((long) mib * 1024L * 1024L / PAGE_BYTES));
    }
    private final List<Page> pages = new ArrayList<>();
    private final ArrayDeque<Slice> retired = new ArrayDeque<>();
    private final int maxPages;
    private final String name;
    private int alignment;

    private PredictionTerrainArena(int maxPages, String name) {
        this.maxPages = maxPages;
        this.name = name;
    }
    static boolean supported() {
        try { return !Boolean.getBoolean("vss.disableIndirect") && org.lwjgl.opengl.GL.getCapabilities().OpenGL46; }
        catch (IllegalStateException noContext) { return false; }
    }
    static final class Page {
        final int buffer, texture;
        final TreeMap<Integer,Integer> free = new TreeMap<>();
        int live;
        Page() {
            buffer=glGenBuffers();texture=glGenTextures();
            glBindBuffer(GL_TEXTURE_BUFFER,buffer);glBufferData(GL_TEXTURE_BUFFER,PAGE_BYTES,GL_DYNAMIC_DRAW);
            glBindTexture(GL_TEXTURE_BUFFER,texture);glTexBuffer(GL_TEXTURE_BUFFER,GL_RGBA32UI,buffer);
            free.put(0,PAGE_BYTES);
        }
        int allocate(int bytes) {
            for(var e:free.entrySet())if(e.getValue()>=bytes){
                int start=e.getKey(),length=e.getValue();free.remove(start);
                if(length>bytes)free.put(start+bytes,length-bytes);live++;return start;
            }
            return -1;
        }
        void release(int start,int length) {
            var before=free.lowerEntry(start);
            if(before!=null && before.getKey()+before.getValue()==start){start=before.getKey();length+=before.getValue();free.remove(before.getKey());}
            var after=free.ceilingEntry(start);
            if(after!=null && start+length==after.getKey()){length+=after.getValue();free.remove(after.getKey());}
            free.put(start,length);live--;
        }
        void close(){glDeleteTextures(texture);glDeleteBuffers(buffer);}
    }
    static final class Slice {
        final Page page;final int offset,length,texture,maskOffset;
        long fence;
        Slice(Page page,int offset,int length,int texture,int maskOffset){this.page=page;this.offset=offset;this.length=length;this.texture=texture;this.maskOffset=maskOffset;}
    }
    Slice upload(ByteBuffer data,int cells) {
        if(!supported())return null;
        reap();
        if(alignment==0) {
            if(glGetInteger(GL_MAX_TEXTURE_BUFFER_SIZE)<PAGE_BYTES/16)return null;
            alignment=Math.max(16,glGetInteger(GL_TEXTURE_BUFFER_OFFSET_ALIGNMENT));
        }
        int payload=(data.remaining()+15)/16*16;
        int length=(payload+cells*4+alignment-1)/alignment*alignment;
        if(length>PAGE_BYTES/2)return null;
        Page selected=null;int offset=-1;
        for(Page page:pages)if((offset=page.allocate(length))>=0){selected=page;break;}
        if(selected==null){if(pages.size()>=maxPages)return null;selected=new Page();pages.add(selected);offset=selected.allocate(length);}
        int texture=glGenTextures();
        try {
            glBindBuffer(GL_TEXTURE_BUFFER,selected.buffer);glBufferSubData(GL_TEXTURE_BUFFER,offset,data);
            glBindTexture(GL_TEXTURE_BUFFER,texture);glTexBufferRange(GL_TEXTURE_BUFFER,GL_RGBA32UI,selected.buffer,offset,payload);
            return new Slice(selected,offset,length,texture,offset+payload);
        }catch(RuntimeException|Error failure){glDeleteTextures(texture);selected.release(offset,length);throw failure;}
        finally{glBindBuffer(GL_TEXTURE_BUFFER,0);glBindTexture(GL_TEXTURE_BUFFER,0);}
    }
    void mask(Slice slice,int[] values){
        glBindBuffer(GL_TEXTURE_BUFFER,slice.page.buffer);
        try{glBufferSubData(GL_TEXTURE_BUFFER,slice.maskOffset,values);}finally{glBindBuffer(GL_TEXTURE_BUFFER,0);}
    }
    void retire(Slice slice) {
        if(slice==null)return;
        glDeleteTextures(slice.texture);
        slice.fence=glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE,0);retired.addLast(slice);
        reap();
    }
    private void reapLocal(){
        for(int count=0;count<16&&!retired.isEmpty();count++){
            Slice slice=retired.peekFirst();int result=glClientWaitSync(slice.fence,0,0);
            // Fences are in submission order. Later slices cannot become reusable first.
            if(result!=GL_ALREADY_SIGNALED && result!=GL_CONDITION_SATISFIED)break;
            glDeleteSync(slice.fence);slice.page.release(slice.offset,slice.length);retired.removeFirst();
        }
        for(var it=pages.iterator();it.hasNext();){Page page=it.next();if(page.live==0){page.close();it.remove();}}
    }
    void reap(){
        reapLocal();
        if (this == SHARED) WATER.reapLocal();
    }
    private void closeLocal(){
        for(Slice slice:retired)glDeleteSync(slice.fence);retired.clear();
        for(Page page:pages)page.close();pages.clear();alignment=0;
    }
    void close(){
        closeLocal();
        // Existing callers historically closed SHARED directly. Keep that
        // lifecycle contract while also disposing the dedicated water pool.
        if (this == SHARED) WATER.closeLocal();
    }
    private String diagnosticsLocal(){return "arena="+name+"{pages="+pages.size()+",bytes="+(long)pages.size()*PAGE_BYTES
            +",budgetBytes="+(long)maxPages*PAGE_BYTES+",retired="+retired.size()+"}";}
    String diagnostics(){return diagnosticsLocal()+","+WATER.diagnosticsLocal();}
    long allocatedBytes() { return (long) pages.size() * PAGE_BYTES; }
}
