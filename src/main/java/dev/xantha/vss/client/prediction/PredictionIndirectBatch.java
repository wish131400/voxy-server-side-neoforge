package dev.xantha.vss.client.prediction;

import java.nio.*;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.opengl.GL43C.*;

/** Ordered page batches: never regroup transparent draws across other pages or fallback tiles. */
final class PredictionIndirectBatch implements AutoCloseable {
    private static final int LIMIT=512, COMMAND_LIMIT=LIMIT*5;
    private int metadata,indirect,tiles,commands;
    private final ByteBuffer records=MemoryUtil.memAlloc(LIMIT*64);
    private final ByteBuffer draws=MemoryUtil.memAlloc(COMMAND_LIMIT*20);
    private PredictionTerrainArena.Page page;
    private PredictionTerrainProgram program;
    private int oldStorage,oldIndirect,oldIndexed;
    private long oldStart,oldSize;
    private long calls;

    void begin(PredictionTerrainProgram program) {
        this.program=program;calls=0;tiles=commands=0;page=null;records.clear();draws.clear();
        oldStorage=glGetInteger(GL_SHADER_STORAGE_BUFFER_BINDING);oldIndirect=glGetInteger(GL_DRAW_INDIRECT_BUFFER_BINDING);
        oldIndexed=glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING,7);
        oldStart=glGetInteger64i(GL_SHADER_STORAGE_BUFFER_START,7);oldSize=glGetInteger64i(GL_SHADER_STORAGE_BUFFER_SIZE,7);
        if(metadata==0){metadata=glGenBuffers();indirect=glGenBuffers();}
        program.batch(false);
    }
    boolean add(PredictionRenderer.Draw draw,PredictionDrawRanges ranges,net.minecraft.world.phys.Vec3 camera,
                boolean water,boolean average,long now) {
        var slice=draw.gpu().arenaSlice();
        if(slice==null){flush();return false;}
        if(page!=slice.page || tiles==LIMIT || commands+ranges.first.length>COMMAND_LIMIT)flush();
        page=slice.page;
        var packed=draw.gpu().packed();
        records.putFloat((float)(draw.tile().baseBlockX()-camera.x)).putFloat((float)-camera.y)
                .putFloat((float)(draw.tile().baseBlockZ()-camera.z)).putFloat(draw.tile().spacingBlocks());
        records.putInt(packed.cellAxis()).putInt(average?1:0).putInt(slice.offset/16).putInt(slice.maskOffset/4);
        float morph=!water&&!draw.seam()?draw.morph()*draw.gpu().morphAmount(now):0;
        records.putFloat(packed.morphBaseTexel()).putFloat(morph).putFloat(packed.morphMinY()).putFloat(packed.morphMaxY());
        records.putInt(!water&&!draw.seam()?1:0).putInt(draw.gpu().paletteBaseTexel()).putInt(0).putInt(0);
        for(int i=0;i<ranges.first.length;i++) {
            draws.putInt(ranges.count[i]*6).putInt(1).putInt(ranges.first[i]*6).putInt(0).putInt(tiles);commands++;
        }
        tiles++;return true;
    }
    void flush() {
        if(commands==0)return;
        records.flip();draws.flip();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER,metadata);glBufferData(GL_SHADER_STORAGE_BUFFER,records,GL_STREAM_DRAW);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,7,metadata);
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER,indirect);glBufferData(GL_DRAW_INDIRECT_BUFFER,draws,GL_STREAM_DRAW);
        PredictionGlState.activeTexture(GL_TEXTURE4);glBindTexture(GL_TEXTURE_BUFFER,page.texture);
        program.batch(true);glMultiDrawElementsIndirect(GL_TRIANGLES,GL_UNSIGNED_INT,0L,commands,20);program.batch(false);
        calls++;records.clear();draws.clear();tiles=commands=0;page=null;
    }
    long end() {
        try {flush();return calls;}
        finally {
            program.batch(false);
            if(oldIndexed!=0 && oldSize>0)glBindBufferRange(GL_SHADER_STORAGE_BUFFER,7,oldIndexed,oldStart,oldSize);
            else glBindBufferBase(GL_SHADER_STORAGE_BUFFER,7,oldIndexed);
            glBindBuffer(GL_SHADER_STORAGE_BUFFER,oldStorage);glBindBuffer(GL_DRAW_INDIRECT_BUFFER,oldIndirect);
        }
    }
    @Override public void close(){if(metadata!=0)glDeleteBuffers(metadata);if(indirect!=0)glDeleteBuffers(indirect);MemoryUtil.memFree(records);MemoryUtil.memFree(draws);}
}
