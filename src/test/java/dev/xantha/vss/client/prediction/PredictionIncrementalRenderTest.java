package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.renderer.culling.Frustum;
import org.joml.Matrix4f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import dev.xantha.vss.client.prediction.PredictionTileManager.*;

class PredictionIncrementalRenderTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void spatialJournalMatchesIndependentRectanglesAcrossReplacementAndRemoval() {
        var index=new PredictionSpatialIndex<PredictionTileKey>();
        var keys=new HashSet<PredictionTileKey>();var random=new Random(921);
        for(int round=0;round<40;round++) {
            for(int i=0;i<40;i++) {
                var key=new PredictionTileKey(Level.OVERWORLD,random.nextInt(64)-32,random.nextInt(64)-32,random.nextInt(9));
                keys.add(key);index.put(key,key);index.put(key,key);
            }
            var remove=new ArrayList<PredictionTileKey>();for(var key:keys) if(random.nextInt(10)==0) remove.add(key);
            for(var key:remove) { index.remove(key);index.remove(key);keys.remove(key); }
            for(int q=0;q<10;q++) {
                long x=random.nextInt(65536)-32768,z=random.nextInt(65536)-32768;
                long maxX=x+random.nextInt(16384)+1,maxZ=z+random.nextInt(16384)+1;
                var expected=new HashSet<PredictionTileKey>();
                for(var key:keys) {
                    long span=64L<<key.lod(),bx=key.tileX()*span,bz=key.tileZ()*span;
                    if(bx<maxX && bz<maxZ && bx+span>x && bz+span>z) expected.add(key);
                }
                var actual=new HashSet<PredictionTileKey>();index.intersect(x,z,maxX,maxZ,actual::add);
                assertEquals(expected,actual);
                assertEquals(!expected.isEmpty(),index.any(x,z,maxX,maxZ));
            }
        }
        index.clear();var result=new ArrayList<PredictionTileKey>();index.intersect(-100,-100,100,100,result::add);assertTrue(result.isEmpty());
    }

    @Test void oneTileChangeVisitsOnlyItsNeighborhoodAndMatchesAFullSeamRebuild() {
        var surfaces=new LinkedHashMap<PredictionTileKey,PredictionLodSeams.Surface>();
        // Share immutable tile payloads across coordinates, as the test changes only locality.
        var templates=new PredictionTile[]{PredictionLodSeamsTest.tile(0,0,1,64),PredictionLodSeamsTest.tile(0,0,1,67)};
        for(int z=-16;z<16;z++) for(int x=-16;x<16;x++) {
            var source=templates[(x+z)&1];var key=new PredictionTileKey(Level.OVERWORLD,x,z,0);
            var tile=new PredictionTile(key,source.heights(),source.groundHeights(),source.samples(),source.mesh(),source.depthBound(),0,1,64,1);
            surfaces.put(key,PredictionLodSeamsTest.surface(tile));
        }
        var seams=new PredictionLodSeams();
        var current=new HashMap<>(seams.apply(surfaces.values(),Set.of()).patches());
        var key=new PredictionTileKey(Level.OVERWORLD,0,0,0);
        long before=seams.updatedSurfaces();
        var replacement=PredictionLodSeamsTest.surface(PredictionLodSeamsTest.tile(0,0,1,70));surfaces.put(key,replacement);
        var delta=seams.apply(List.of(replacement),Set.of());
        assertTrue(seams.updatedSurfaces()-before<=9,"a local update must not traverse/rebuild all 1024 seam owners");
        delta.removed().forEach(current::remove);current.putAll(delta.patches());
        var expected=new PredictionLodSeams().update(List.copyOf(surfaces.values()));
        assertEquals(expected.size(),current.size());
        for(var patch:expected) assertArrayEquals(patch.mesh().quads(),current.get(patch.surface().tile().key()).mesh().quads());
        System.out.printf("SEAM_LOCALITY resident=1024 updated=%d%n",seams.updatedSurfaces()-before);
    }

    @Test void residencyJournalReportsOnlyOverlappingOwnersAndRemovesOldDimensions() {
        var layout=VssLodLayout.of(8192,6,true,true);
        var tiles=new HashMap<PredictionTileKey,PredictionTile>();
        for(int z=-16;z<16;z++) for(int x=-16;x<16;x++) {
            var tile=PredictionCoverageWorkTest.tile(x,z,0,layout);tiles.put(tile.key(),tile);
        }
        var source=new RenderSnapshot(Level.OVERWORLD,layout,Map.copyOf(tiles),Map.of());
        var residency=new PredictionRenderResidency();residency.retain(source);
        for(var tile:tiles.values()) residency.uploaded(tile);
        assertEquals(1024,residency.drainChanges().size());assertTrue(residency.drainChanges().isEmpty());
        var replacement=PredictionCoverageWorkTest.tile(0,0,0,layout);residency.uploaded(replacement);
        assertEquals(Set.of(replacement.key()),residency.drainChanges());
        var parent=PredictionCoverageWorkTest.tile(0,0,1,layout);residency.uploaded(parent);
        var expected=new HashSet<PredictionTileKey>();expected.add(parent.key());
        for(int z=0;z<2;z++) for(int x=0;x<2;x++) expected.add(new PredictionTileKey(Level.OVERWORLD,x,z,0));
        assertEquals(expected,residency.drainChanges());
        residency.retain(new RenderSnapshot(Level.NETHER,layout,Map.of(),Map.of()));
        assertEquals(1025,residency.drainChanges().size(),"dimension reset must retire every old GPU owner");
        assertTrue(residency.snapshot(new RenderSnapshot(Level.NETHER,layout,Map.of(),Map.of())).tiles().isEmpty());
    }

    @Test void immutableSnapshotPagesMatchFullMapsAndKeepPriorFramesAlive() {
        var random=new Random(922);var expected=new HashMap<PredictionTileKey,Integer>();
        var table=new PredictionTileTable<Integer>(Level.OVERWORLD);
        for(int round=0;round<80;round++) {
            var before=table;var beforeMap=Map.copyOf(expected);
            var changes=new HashMap<PredictionTileKey,Integer>();var removed=new HashSet<PredictionTileKey>();
            for(int i=0;i<30;i++) {
                var key=new PredictionTileKey(Level.OVERWORLD,random.nextInt(100)-50,random.nextInt(100)-50,random.nextInt(8));
                changes.put(key,round+i);expected.put(key,round+i);
            }
            for(var key:List.copyOf(expected.keySet())) if(random.nextInt(15)==0) { removed.add(key);expected.remove(key);changes.remove(key); }
            table=table.changed(changes,removed);
            assertEquals(expected,table);assertEquals(expected.entrySet(),table.entrySet());
            assertEquals(beforeMap,before,"new publications must not mutate an opaque/water frame still using old pages");
            for(var entry:expected.entrySet()) assertEquals(entry.getValue(),table.at(entry.getKey().tileX(),entry.getKey().tileZ(),entry.getKey().lod()));
            if(!table.isEmpty()) {
                var entry=table.entrySet().iterator().next();
                assertThrows(UnsupportedOperationException.class,()->entry.setValue(123));
            }
        }
    }

    @Test void pagedGpuSnapshotsPreserveScopedFamiliesAndCoverageAfterUpgrades() {
        var layout=VssLodLayout.of(8192,6,true,true);var tiles=new HashMap<PredictionTileKey,PredictionTile>();
        for(int lod=0;lod<3;lod++) for(int z=-4;z<4;z++) for(int x=-4;x<4;x++) {
            var tile=PredictionCoverageWorkTest.tile(x,z,lod,layout);tiles.put(tile.key(),tile);
        }
        var state=new PredictionRenderResidency();var random=new Random(923);var keys=List.copyOf(tiles.keySet());
        var initial=new RenderSnapshot(Level.OVERWORLD,layout,Map.copyOf(tiles),Map.of());state.retain(initial);
        for(var tile:tiles.values()) state.uploaded(tile);
        var retained=state.snapshot(initial);var retainedMap=Map.copyOf(retained.tiles());
        for(int round=0;round<40;round++) {
            var key=keys.get(random.nextInt(keys.size()));var old=tiles.get(key);
            var next=new PredictionTile(key,old.heights(),old.groundHeights(),old.samples(),old.mesh(),old.depthBound(),0,round+2,
                    old.cellAxis(),old.spacingBlocks(),!old.scopeOnly());
            tiles.put(key,next);var source=new RenderSnapshot(Level.OVERWORLD,layout,Map.copyOf(tiles),Map.of());
            state.retain(source);state.uploaded(next);var snapshot=state.snapshot(source);
            assertEquals(source.scopedFamilies(),snapshot.scopedFamilies());
            for(int x=-32;x<32;x+=3) for(int z=-32;z<32;z+=3) for(int lod=0;lod<4;lod++)
                assertSame(source.coveringTileAtDetail(x,z,lod),snapshot.coveringTileAtDetail(x,z,lod));
            assertEquals(retainedMap,retained.tiles());state.drainChanges();
        }
    }

    @Test void compareSnapshotPublicationAfterOneLocalUpload() {
        var layout=VssLodLayout.of(8192,6,true,true);var tiles=new HashMap<PredictionTileKey,PredictionTile>();
        for(int lod=0;lod<layout.levelCount();lod++) for(int z=-16;z<16;z++) for(int x=-16;x<16;x++) {
            var tile=PredictionCoverageWorkTest.tile(x,z,lod,layout);tiles.put(tile.key(),tile);
        }
        var source=new RenderSnapshot(Level.OVERWORLD,layout,Map.copyOf(tiles),Map.of());
        var state=new PredictionRenderResidency();state.retain(source);for(var tile:tiles.values()) state.uploaded(tile);
        state.snapshot(source);state.drainChanges();
        long[][] nanos=new long[2][30];long[] allocated=new long[2];
        var bean=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
        long thread=Thread.currentThread().getId();
        for(int iteration=-20;iteration<30;iteration++) {
            var tile=PredictionCoverageWorkTest.tile(0,0,0,layout);tiles.put(tile.key(),tile);state.uploaded(tile);
            RenderSnapshot previous=null,current=null;
            for(int mode:iteration%2==0?new int[]{0,1}:new int[]{1,0}) {
                long before=bean.getThreadAllocatedBytes(thread),start=System.nanoTime();
                var result=mode==0?new RenderSnapshot(Level.OVERWORLD,layout,Map.copyOf(tiles),Map.of()):state.snapshot(source);
                long elapsed=System.nanoTime()-start,bytes=bean.getThreadAllocatedBytes(thread)-before;
                if(mode==0) previous=result;else current=result;
                if(iteration>=0) { nanos[mode][iteration]=elapsed;allocated[mode]+=bytes; }
            }
            assertEquals(previous.tiles(),current.tiles());state.drainChanges();
        }
        for(var values:nanos) Arrays.sort(values);
        System.out.printf(Locale.ROOT,"SNAPSHOT_LOCAL tiles=%d oldMedianMs=%.3f pagedMedianMs=%.3f oldKiB=%.1f pagedKiB=%.1f (publication only; not FPS)%n",
                tiles.size(),nanos[0][15]/1e6,nanos[1][15]/1e6,allocated[0]/30D/1024,allocated[1]/30D/1024);
    }

    @Test void coarseFineSeamDeltasMatchRebuildAcrossCoverageChangesAndRemovals() {
        var current=new LinkedHashMap<PredictionTileKey,PredictionLodSeams.Surface>();
        var seams=new PredictionLodSeams();var patches=new HashMap<PredictionTileKey,PredictionLodSeams.Patch>();
        for(int round=0;round<20;round++) {
            var replacement=new ArrayList<PredictionLodSeams.Surface>();var removed=new HashSet<PredictionTileKey>();
            var parent=PredictionLodSeamsTest.surface(PredictionLodSeamsTest.tile(-1,-1,4,64+round%3));
            if(round%2==0) for(int z=32;z<64;z++) for(int x=32;x<64;x++) parent.allowed()[z*64+x]=false;
            replacement.add(parent);
            var child=PredictionLodSeamsTest.surface(PredictionLodSeamsTest.tile(-1,-1,2,72-round%5));
            if(round%3!=0) replacement.add(child);else removed.add(child.tile().key());
            removed.forEach(current::remove);for(var surface:replacement) current.put(surface.tile().key(),surface);
            var delta=seams.apply(replacement,removed);delta.removed().forEach(patches::remove);patches.putAll(delta.patches());
            var full=new PredictionLodSeams();var expected=full.update(List.copyOf(current.values()));
            assertEquals(expected.size(),patches.size());
            for(var patch:expected) assertArrayEquals(patch.mesh().quads(),patches.get(patch.surface().tile().key()).mesh().quads());
            for(var key:current.keySet()) assertArrayEquals(full.boundaryMask(key),seams.boundaryMask(key));
        }
    }

    @Test void realEdgesIgnoreRemoteTerrainAndReassignAfterLocalRemoval() {
        var near=PredictionLodSeamsTest.surface(PredictionLodSeamsTest.tile(0,0,1,64));
        var far=PredictionLodSeamsTest.surface(PredictionLodSeamsTest.tile(100,100,1,64));
        var ground=PredictionLodSeamsTest.tile(0,0,1,67).samples()[0];
        var edges=List.of(new PredictionRealBoundarySeams.Edge(-1,10,1,0,ground));
        var index=new PredictionLodSeams.Index(List.of(near,far));var real=new PredictionRealBoundarySeams();
        var initial=real.apply(index,Set.of(near.tile().key(),far.tile().key()),edges);
        assertEquals(1,initial.patches().size());long before=real.examinedEdges();
        real.apply(index,Set.of(far.tile().key()),edges);assertEquals(before,real.examinedEdges());
        index.remove(near.tile().key());var gone=real.apply(index,Set.of(near.tile().key()),edges);
        assertEquals(Set.of(near.tile().key()),gone.removed());
        index.put(near);assertEquals(1,real.apply(index,Set.of(near.tile().key()),edges).patches().size());
    }

    @Test void visiblePlanReusesStationaryFramesAndKeepsCorrectOrderDuringTurnsAndEdits() {
        var plan=new PredictionVisiblePlan<Integer,AABB>(box->box);
        var boxes=new LinkedHashMap<Integer,AABB>();
        for(int z=-35;z<35;z++) for(int x=-35;x<35;x++) {
            int id=boxes.size();var box=new AABB(x*64,60,z*64,x*64+64,65,z*64+64);
            boxes.put(id,box);plan.put(id,box);
        }
        var camera=new Vec3(.123,100,.456);var view=new Matrix4f();
        var projection=new Matrix4f().perspective((float)Math.toRadians(7),1.7F,.1F,8192);
        var frustum=new Frustum(view,projection);frustum.prepare(camera.x,camera.y,camera.z);
        var first=plan.select(camera,view,projection,frustum);long visits=plan.visited(),orders=plan.orderEdits();
        for(int frame=0;frame<120;frame++) assertSame(first,plan.select(camera,view,projection,frustum));
        assertEquals(visits,plan.visited());assertEquals(orders,plan.orderEdits());
        for(int frame=0;frame<16;frame++) {
            if(frame==4) { boxes.remove(100);plan.remove(100); }
            if(frame==8) { var box=new AABB(-10,60,-100,10,65,-80);boxes.put(111,box);plan.put(111,box); }
            if(frame==12) camera=new Vec3(92.321,90,51.789);
            view=new Matrix4f().rotateY(frame*.4F);frustum=new Frustum(view,projection);frustum.prepare(camera.x,camera.y,camera.z);
            var actual=plan.select(camera,view,projection,frustum);
            var expected=new HashSet<AABB>();for(var box:boxes.values()) if(frustum.isVisible(box)) expected.add(box);
            assertEquals(expected,new HashSet<>(actual));
            double previous=-1;
            for(var box:actual) { double dx=(box.minX+box.maxX)*.5-camera.x,dz=(box.minZ+box.maxZ)*.5-camera.z;
                double distance=dx*dx+dz*dz;assertTrue(distance>=previous);previous=distance; }
        }
        plan.clear();assertTrue(plan.select(camera,view,projection,frustum).isEmpty());
        System.out.println("STATIC_PLAN resident=4900 frames=120 additionalVisibilityVisits=0 additionalOrderEdits=0");
    }
}
