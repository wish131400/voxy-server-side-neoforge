package dev.xantha.vss.client.prediction;

import java.nio.ByteBuffer;
import java.util.*;
import static org.lwjgl.opengl.GL43C.*;

/** Bounded shared geometry pages. Retired slices are reusable only after a zero-timeout fence succeeds. */
final class PredictionTerrainArena {
    static final PredictionTerrainArena SHARED = new PredictionTerrainArena();
    static final int PAGE_BYTES = 8 * 1024 * 1024, MAX_PAGES = 16;
    private final List<Page> pages = new ArrayList<>();
    private final ArrayDeque<Slice> retired = new ArrayDeque<>();
    private int alignment;
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
        if(selected==null){if(pages.size()>=MAX_PAGES)return null;selected=new Page();pages.add(selected);offset=selected.allocate(length);}
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
    void reap(){
        for(int count=0;count<16&&!retired.isEmpty();count++){
            Slice slice=retired.peekFirst();int result=glClientWaitSync(slice.fence,0,0);
            // Fences are in submission order. Later slices cannot become reusable first.
            if(result!=GL_ALREADY_SIGNALED && result!=GL_CONDITION_SATISFIED)break;
            glDeleteSync(slice.fence);slice.page.release(slice.offset,slice.length);retired.removeFirst();
        }
        for(var it=pages.iterator();it.hasNext();){Page page=it.next();if(page.live==0){page.close();it.remove();}}
    }
    void close(){
        for(Slice slice:retired)glDeleteSync(slice.fence);retired.clear();
        for(Page page:pages)page.close();pages.clear();alignment=0;
    }
    String diagnostics(){return "arena={pages="+pages.size()+",bytes="+(long)pages.size()*PAGE_BYTES+",retired="+retired.size()+"}";}
}
