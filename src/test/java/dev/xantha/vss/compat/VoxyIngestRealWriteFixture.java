package dev.xantha.vss.compat;

import java.util.concurrent.ConcurrentLinkedDeque;

public class VoxyIngestRealWriteFixture {
    public final ConcurrentLinkedDeque<IngestSection> ingestQueue=new ConcurrentLinkedDeque<>();
    public static class WorldEngine { public boolean fail; public int writes; }
    public static class VoxelizedSection {
        public int x,y,z;
        VoxelizedSection(int x,int y,int z) { this.x=x;this.y=y;this.z=z; }
    }
    public static class WorldUpdater {
        public static void insertUpdate(WorldEngine engine,VoxelizedSection section) {
            if(engine.fail)throw new IllegalStateException("native insert failed");
            engine.writes++;
        }
    }
    public record IngestSection(WorldEngine world,Object section,int x,int y,int z) { }
    public boolean rawIngest0(IngestSection task) { return ingestQueue.add(task); }
    public boolean enqueueIngest(WorldEngine engine) {
        ingestQueue.add(new IngestSection(engine,new Object(),0,0,0));
        ingestQueue.add(new IngestSection(engine,new Object(),0,1,0));
        return false; // Some native all-air loops publish work without returning true.
    }
    public void processJob() {
        IngestSection task=ingestQueue.poll();if(task==null)return;
        WorldUpdater.insertUpdate(task.world,new VoxelizedSection(task.x,task.y,task.z));
    }
    public void processDelegatedJob() {
        IngestSection task=ingestQueue.poll();if(task==null)return;
        try { processTask(task); } catch(Throwable nativeFailure) { /* Native Jiang worker handles the failure. */ }
    }
    private void processTask(IngestSection task) {
        WorldUpdater.insertUpdate(task.world,new VoxelizedSection(task.x,task.y,task.z));
    }
}
