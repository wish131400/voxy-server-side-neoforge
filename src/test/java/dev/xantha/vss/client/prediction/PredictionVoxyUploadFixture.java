package dev.xantha.vss.client.prediction;

import dev.xantha.vss.compat.StrictLodVisibility;
import dev.xantha.vss.compat.StrictVoxyNodeIndex;
import dev.xantha.vss.compat.StrictVoxyPipeline;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTile;
import net.minecraft.world.level.Level;

/** Real native-node and pending-work observations, visible to the asynchronous loading worker. */
final class PredictionVoxyUploadFixture implements AutoCloseable {
    final StrictVoxyPipeline pipeline=new StrictVoxyPipeline();
    final StrictVoxyNodeIndex nodes;
    private final boolean previousHook;
    private int nextId;
    PredictionVoxyUploadFixture() throws Exception {
        previousHook=(boolean)get("renderHookSeen");
        StrictLodVisibility.reset();
        set("renderHookSeen",true);set("dimension",Level.OVERWORLD);set("uploadedNodes",true);
        set("meshPipeline",pipeline);nodes=(StrictVoxyNodeIndex)get("NODES");
    }
    void upload(PredictionTile tile) {
        var source=tile.data().voxyTile;
        synchronized(StrictLodVisibility.class) {
            for(int z=Math.floorDiv(source.baseZ(),32);z<=Math.floorDiv(source.baseZ()+source.span()-1,32);z++)
                for(int x=Math.floorDiv(source.baseX(),32);x<=Math.floorDiv(source.baseX()+source.span()-1,32);x++)
                    for(int y=Math.floorDiv(tile.depthBound().minY(),32);y<=Math.floorDiv(tile.depthBound().maxY(),32);y++)
                        nodes.update(++nextId,StrictVoxyNodeIndex.key(0,x,y,z),123);
        }
    }
    private static Object get(String name) throws Exception {var field=StrictLodVisibility.class.getDeclaredField(name);field.setAccessible(true);return field.get(null);}
    private static void set(String name,Object value) throws Exception {var field=StrictLodVisibility.class.getDeclaredField(name);field.setAccessible(true);field.set(null,value);}
    public void close() throws Exception {StrictLodVisibility.reset();set("renderHookSeen",previousHook);}
}
