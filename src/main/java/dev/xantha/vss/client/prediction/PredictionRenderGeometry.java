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
    private Vec3 residentCamera;
    private double residentHorizon;
    private boolean residentSorted;
    void update(PredictionTileManager.RenderSnapshot snapshot) {
        if(previous==snapshot) return;
        residentCamera = null;
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
        return visible(camera, frustum, horizon, true);
    }
    List<Visible> resident(Vec3 camera, double horizon) { return visible(camera, null, horizon, false); }
    private List<Visible> visible(Vec3 camera,Frustum frustum,double horizon,boolean sorted) {
        if (frustum == null && camera.equals(residentCamera) && horizon == residentHorizon && sorted == residentSorted) return visible;
        residentCamera = frustum == null ? camera : null;
        residentHorizon = horizon;
        residentSorted = sorted;
        visible.clear();
        for(var item:entries) {
            var entry=item.geometry();
            if(!entry.visible(camera,frustum,horizon)) continue;
            item.distance=entry.distance(camera);item.faces=VssLodFaceGroup.visibleMask(entry.bounds(),camera);
            var payload=entry.tile().mesh().gpuPayload();
            if(payload!=null && payload.downFaces()) item.faces |= 1 << VssLodFaceGroup.HORIZONTAL;
            visible.add(item);
        }
        if (sorted) visible.sort(Comparator.comparingDouble(Visible::distance));
        return visible;
    }
    boolean inFrustum(PredictionTileManager.PredictionTileKey key, Frustum frustum) {
        var entry = cached.get(key);
        return entry != null && (frustum == null || frustum.isVisible(entry.geometry().culling()));
    }
    void clear(){previous=null;residentCamera=null;entries=List.of();cached=Map.of();visible.clear();}
}
