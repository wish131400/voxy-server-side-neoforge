package dev.xantha.vss.client.prediction;

import java.util.*;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.renderer.culling.Frustum;

/** Only world-space geometry is cached. Camera/frustum decisions stay frame-local. */
final class PredictionRenderGeometry {
    record Entry(PredictionTileManager.PredictionTile tile,AABB bounds,AABB culling,
                 double centerX,double centerZ,double margin) {
        Entry(PredictionTileManager.PredictionTile t) {
            this(t,new AABB(t.baseBlockX(),t.depthBound().minY(),t.baseBlockZ(),
                    t.baseBlockX()+t.spanBlocks(),t.depthBound().maxY(),t.baseBlockZ()+t.spanBlocks()));
        }
        private Entry(PredictionTileManager.PredictionTile t,AABB b) {
            this(t,b,b.inflate(2),b.minX+t.spanBlocks()*.5,b.minZ+t.spanBlocks()*.5,
                    Math.sqrt(2)*t.spanBlocks()*.5);
        }
        boolean visible(Vec3 camera,Frustum frustum,double horizon) {
            double dx=Math.max(Math.max(bounds.minX-camera.x,camera.x-bounds.maxX),0);
            double dz=Math.max(Math.max(bounds.minZ-camera.z,camera.z-bounds.maxZ),0);
            return dx*dx+dz*dz <= (horizon+margin)*(horizon+margin)
                    && (frustum==null || frustum.isVisible(culling));
        }
        double distance(Vec3 camera) {
            double dx=centerX-camera.x,dz=centerZ-camera.z;return dx*dx+dz*dz;
        }
    }
    static final class Visible {
        private final Entry geometry;
        private double distance;
        private int faces;
        Visible(Entry geometry){this.geometry=geometry;}
        Entry geometry(){return geometry;}
        double distance(){return distance;}
        int faces(){return faces;}
    }
    private Object previous;
    private List<Visible> entries=List.of();
    private Map<PredictionTileManager.PredictionTileKey,Visible> cached=Map.of();
    private final ArrayList<Visible> visible=new ArrayList<>();
    void update(PredictionTileManager.RenderSnapshot snapshot) {
        if(previous==snapshot) return;
        var next=new HashMap<PredictionTileManager.PredictionTileKey,Visible>();
        var ordered=new ArrayList<Visible>(snapshot.tiles().size());
        for(var tile:snapshot.tiles().values()) {
            var entry=cached.get(tile.key());
            if(entry==null || entry.geometry().tile()!=tile) entry=new Visible(new Entry(tile));
            next.put(tile.key(),entry);ordered.add(entry);
        }
        cached=next;entries=ordered;previous=snapshot;
    }
    List<Visible> visible(Vec3 camera,Frustum frustum,double horizon) {
        visible.clear();
        for(var item:entries) {
            var entry=item.geometry();
            if(!entry.visible(camera,frustum,horizon)) continue;
            item.distance=entry.distance(camera);item.faces=VssLodFaceGroup.visibleMask(entry.bounds(),camera);
            visible.add(item);
        }
        visible.sort(Comparator.comparingDouble(Visible::distance));
        return visible;
    }
    void clear(){previous=null;entries=List.of();cached=Map.of();visible.clear();}
}
