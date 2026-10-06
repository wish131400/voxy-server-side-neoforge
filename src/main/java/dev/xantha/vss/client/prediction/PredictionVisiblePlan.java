package dev.xantha.vss.client.prediction;

import java.util.*;
import java.util.function.Function;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/** Retains draw objects and distance order. Rotation only changes frustum membership. */
final class PredictionVisiblePlan<K,T> {
    /**
     * Opaque terrain does not need a new exact distance order for every
     * fractional camera movement.  Keep that order for one coarse horizontal
     * camera cell and rebuild it when the camera crosses a cell boundary.
     *
     * The cell is deliberately much larger than the 8-block ownership cells:
     * membership and frustum changes are still evaluated every frame, while a
     * small move no longer removes and reinserts every resident draw.
     */
    private static final double STABLE_ORDER_BUCKET_BLOCKS = 128.0D;

    private static final class Entry<T> {
        final long id;
        final Object key;
        T value;
        double distance;
        boolean visible;
        Entry(long id,Object key,T value) { this.id=id;this.key=key;this.value=value; }
    }
    private final Map<K,Entry<T>> entries=new HashMap<>();
    private final Set<Entry<T>> dirty=new HashSet<>();
    private final ArrayList<Entry<T>> stableStale=new ArrayList<>();
    private final TreeSet<Entry<T>> visible=new TreeSet<>(Comparator
            .<Entry<T>>comparingDouble(entry->entry.distance).thenComparingLong(entry->entry.id));
    private final Function<T,AABB> bounds;
    private final Matrix4f modelView=new Matrix4f(),projection=new Matrix4f();
    private Vec3 camera;
    private long nextId, visited, orderEdits, publications;
    private boolean changed;
    private List<T> result=List.of();
    private long stableBucketX, stableBucketZ;
    private boolean stableInitialized;

    PredictionVisiblePlan(Function<T,AABB> bounds) { this.bounds=bounds; }

    void put(K key,T value) {
        var entry=entries.get(key);
        if(entry!=null && entry.value==value) return;
        if(entry==null) { entry=new Entry<>(nextId++,key,value);entries.put(key,entry); }
        else {
            if(entry.visible) { visible.remove(entry);changed=true;entry.visible=false; }
            entry.value=value;
        }
        dirty.add(entry);
    }

    void remove(K key) {
        var entry=entries.remove(key);
        if(entry==null) return;
        dirty.remove(entry);
        if(entry.visible) { visible.remove(entry);changed=true; }
    }

    List<T> select(Vec3 nextCamera,Matrix4f nextView,Matrix4f nextProjection,Frustum frustum) {
        return select(nextCamera,nextView,nextProjection,frustum,null);
    }

    List<T> select(Vec3 nextCamera,Matrix4f nextView,Matrix4f nextProjection,Frustum frustum,
                   Set<K> candidates) {
        // Vertical motion changes frustum membership but not horizontal order.
        boolean translated=camera == null || !nextCamera.equals(camera);
        boolean moved=camera == null || nextCamera.x != camera.x || nextCamera.z != camera.z;
        boolean turned=camera==null || !modelView.equals(nextView) || !projection.equals(nextProjection);
        if(translated || turned) {
            if (candidates == null) {
                for(var entry:entries.values())
                    update(entry,nextCamera,frustum,moved || dirty.contains(entry));
            } else {
                // A conservative broad phase is supplied by the spatial
                // index. Remove stale visible entries, then visit candidates
                // directly instead of scanning every resident tile on turns.
                if (!visible.isEmpty()) {
                    var stale = new java.util.ArrayList<Entry<T>>();
                    for (var entry : visible) if (!candidates.contains(entry.key)) stale.add(entry);
                    for (var entry : stale) {
                        visible.remove(entry); entry.visible=false; changed=true;
                    }
                }
                for (K key : candidates) {
                    var entry = entries.get(key);
                    if (entry != null) update(entry,nextCamera,frustum,moved || dirty.contains(entry));
                }
            }
        } else {
            if (candidates != null) for (var mapEntry : entries.entrySet()) {
                if (candidates.contains(mapEntry.getKey())) continue;
                var entry = mapEntry.getValue();
                if (entry.visible) { visible.remove(entry);entry.visible=false;changed=true; }
            }
            for(var entry:dirty) {
                if(candidates == null || candidates.contains(entry.key)) update(entry,nextCamera,frustum,true);
                else if(entry.visible) { visible.remove(entry);entry.visible=false;changed=true; }
            }
        }
        dirty.clear();camera=nextCamera;modelView.set(nextView);projection.set(nextProjection);
        if(changed) {
            var next=new ArrayList<T>(visible.size());for(var entry:visible) next.add(entry.value);
            result=List.copyOf(next);changed=false;publications++;
        }
        return result;
    }

    /**
     * Selects visible entries using a stable opaque ordering.
     *
     * Existing entries retain their previous distance order while the camera
     * remains in the same coarse horizontal bucket.  Frustum membership is
     * still updated on every translated/rotated frame and changed entries are
     * inserted or removed immediately.  Crossing a bucket boundary recomputes
     * all resident distances and rebuilds the exact order.  The regular
     * {@link #select(Vec3, Matrix4f, Matrix4f, Frustum)} methods intentionally
     * keep their exact per-frame ordering semantics for callers that need it.
     */
    List<T> selectStable(Vec3 nextCamera,Matrix4f nextView,Matrix4f nextProjection,
                         Frustum frustum) {
        return selectStable(nextCamera,nextView,nextProjection,frustum,null);
    }

    /** Stable-order variant with a conservative candidate set. */
    List<T> selectStable(Vec3 nextCamera,Matrix4f nextView,Matrix4f nextProjection,
                         Frustum frustum,Set<K> candidates) {
        boolean translated=camera == null || !nextCamera.equals(camera);
        boolean turned=camera==null || !modelView.equals(nextView) || !projection.equals(nextProjection);
        long nextBucketX=stableBucket(nextCamera.x), nextBucketZ=stableBucket(nextCamera.z);
        boolean reorder=!stableInitialized || camera==null
                || nextBucketX!=stableBucketX || nextBucketZ!=stableBucketZ;

        if(translated || turned || reorder) {
            if(candidates==null) {
                for(var entry:entries.values())
                    updateStable(entry,nextCamera,frustum,reorder || dirty.contains(entry));
            } else {
                // A broad phase may stop returning an entry after a move or a
                // turn. Remove those stale entries before visiting candidates.
                if(!visible.isEmpty()) {
                    stableStale.clear();
                    for(var entry:visible) if(!candidates.contains(entry.key)) stableStale.add(entry);
                    for(var entry:stableStale) {
                        visible.remove(entry);entry.visible=false;changed=true;
                    }
                    stableStale.clear();
                }
                for(K key:candidates) {
                    var entry=entries.get(key);
                    if(entry!=null) updateStable(entry,nextCamera,frustum,
                            reorder || dirty.contains(entry));
                }
            }
        } else {
            // A candidate set can narrow even when the camera and matrices did
            // not change. Keep the broad-phase contract of select(): entries
            // outside that set are no longer eligible for this frame.
            if(candidates!=null) {
                for(var mapEntry:entries.entrySet()) {
                    if(candidates.contains(mapEntry.getKey())) continue;
                    var entry=mapEntry.getValue();
                    if(entry.visible) {
                        visible.remove(entry);entry.visible=false;changed=true;
                    }
                }
                // Candidate sets may widen without a camera or matrix change
                // (for example after a spatial index update). Visit them so a
                // previously narrowed entry can re-enter immediately.
                for(K key:candidates) {
                    var entry=entries.get(key);
                    if(entry!=null) updateStable(entry,nextCamera,frustum,dirty.contains(entry));
                }
                for(var entry:dirty) if(!candidates.contains(entry.key) && entry.visible) {
                    visible.remove(entry);entry.visible=false;changed=true;
                }
            } else for(var entry:dirty) updateStable(entry,nextCamera,frustum,true);
        }

        // If the bucket changed, updateStable() has already removed and
        // reinserted visible entries with fresh distances. The assignment is
        // kept here so a following frame in the same bucket can take the
        // inexpensive membership-only path.
        stableBucketX=nextBucketX;stableBucketZ=nextBucketZ;stableInitialized=true;
        dirty.clear();camera=nextCamera;modelView.set(nextView);projection.set(nextProjection);
        if(changed) {
            var next=new ArrayList<T>(visible.size());
            for(var entry:visible) next.add(entry.value);
            result=List.copyOf(next);changed=false;publications++;
        }
        return result;
    }

    private static long stableBucket(double coordinate) {
        return (long)Math.floor(coordinate/STABLE_ORDER_BUCKET_BLOCKS);
    }

    private void updateStable(Entry<T> entry,Vec3 nextCamera,Frustum frustum,
                              boolean distanceChanged) {
        visited++;
        var box=bounds.apply(entry.value);
        boolean inView=frustum==null || frustum.isVisible(box);
        // A dirty or newly visible entry needs a current key even when the
        // camera stayed inside its bucket. Existing visible entries keep their
        // old key until the bucket is crossed.
        boolean updateDistance=distanceChanged || (!entry.visible && inView);
        if(entry.visible && (!inView || distanceChanged)) {
            visible.remove(entry);entry.visible=false;changed=true;
        }
        if(updateDistance) {
            double dx=(box.minX+box.maxX)*.5-nextCamera.x,
                    dz=(box.minZ+box.maxZ)*.5-nextCamera.z;
            entry.distance=dx*dx+dz*dz;
        }
        if(!inView) return;
        if(!entry.visible) {
            visible.add(entry);entry.visible=true;changed=true;orderEdits++;
        }
    }

    private void update(Entry<T> entry,Vec3 camera,Frustum frustum,boolean distanceChanged) {
        visited++;
        var box=bounds.apply(entry.value);
        boolean inView=frustum==null || frustum.isVisible(box);
        if(entry.visible && (!inView || distanceChanged)) { visible.remove(entry);entry.visible=false;changed=true; }
        if(distanceChanged) {
            double dx=(box.minX+box.maxX)*.5-camera.x,dz=(box.minZ+box.maxZ)*.5-camera.z;
            entry.distance=dx*dx+dz*dz;
        }
        if(!inView) return;
        if(!entry.visible) { visible.add(entry);entry.visible=true;changed=true;orderEdits++; }
    }

    long visited() { return visited; }
    long orderEdits() { return orderEdits; }
    long publications() { return publications; }
    void clear() {
        entries.clear();dirty.clear();visible.clear();stableStale.clear();result=List.of();camera=null;changed=false;
        stableInitialized=false;stableBucketX=stableBucketZ=0L;
    }
}
