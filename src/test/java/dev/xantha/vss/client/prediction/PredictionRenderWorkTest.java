package dev.xantha.vss.client.prediction;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class PredictionRenderWorkTest {
    @org.junit.jupiter.api.BeforeAll
    static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    @Test void narrowViewSortKeepsAllSeamNeighborsAndMatchesFullSort() {
        var layout=VssLodLayout.of(8192,6,true,false);
        var tiles=new LinkedHashMap<PredictionTileManager.PredictionTileKey,PredictionTileManager.PredictionTile>();
        for(int x=-35;x<35;x++) for(int z=-35;z<35;z++) {
            var tile=PredictionCoverageWorkTest.tile(x,z,0,layout);tiles.put(tile.key(),tile);
        }
        var snapshot=new PredictionTileManager.RenderSnapshot(Level.OVERWORLD,layout,tiles,Map.of());
        var old=new PredictionRenderGeometry();old.update(snapshot);
        var current=new PredictionRenderGeometry();current.update(snapshot);
        long[][] times=new long[2][30];
        int submitted=0;
        for(int frame=-30;frame<30;frame++) {
            var camera=new Vec3(frame*.13,100,frame*.31);
            var frustum=new net.minecraft.client.renderer.culling.Frustum(new org.joml.Matrix4f(),
                    new org.joml.Matrix4f().perspective((float)Math.toRadians(7),1.7F,.1F,8192));
            frustum.prepare(camera.x,camera.y,camera.z);
            List<PredictionTileManager.PredictionTileKey> expected=null,actual=null;
            for(int mode:frame%2==0?new int[]{0,1}:new int[]{1,0}) {
                long start=System.nanoTime();
                var residents=mode==0?old.visible(camera,null,8192):current.resident(camera,8192);
                var visible=new ArrayList<PredictionRenderGeometry.Visible>();
                for(var item:residents) if(frustum.isVisible(item.geometry().culling())) visible.add(item);
                if(mode==1) visible.sort(Comparator.comparingDouble(PredictionRenderGeometry.Visible::distance));
                long elapsed=System.nanoTime()-start;
                assertEquals(4900,residents.size(),"offscreen surfaces must still be available for seams");
                var keys=visible.stream().map(item->item.geometry().tile().key()).toList();
                if(mode==0) expected=keys; else actual=keys;
                if(frame>=0) times[mode][frame]=elapsed;
            }
            assertEquals(expected,actual);
            submitted=actual.size();
            assertTrue(submitted>0 && submitted<4900/4);
        }
        for(var values:times) Arrays.sort(values);
        System.out.printf(Locale.ROOT,"NARROW_SORT resident=4900 submitted=%d previousMedianMs=%.3f currentMedianMs=%.3f (cull/sort only)%n",
                submitted,times[0][15]/1e6,times[1][15]/1e6);
    }
    @Test void allFaceMasksKeepTheExactOrderedIndexSequence() {
        var random=new Random(921);
        for(int sample=0;sample<100;sample++) {
            int[] first=new int[5],count=new int[5];int end=sample;
            for(int i=0;i<5;i++){first[i]=end;count[i]=random.nextInt(9);end+=count[i];}
            for(int mask=0;mask<32;mask++) {
                var expected=new ArrayList<Integer>();
                for(int i=0;i<5;i++) if((mask&(1<<i))!=0)
                    for(int q=0;q<count[i];q++) expected.add(first[i]+q);
                var plan=new PredictionDrawRanges(first,count,mask);
                var actual=new ArrayList<Integer>();
                for(int i=0;i<plan.first.length;i++) for(int q=0;q<plan.count[i];q++) actual.add(plan.first[i]+q);
                assertEquals(expected,actual);assertEquals(actual.size(),plan.quads);
            }
        }
    }
    @Test void geometryFollowsCurrentCameraAndSnapshotReplacement() {
        var layout=VssLodLayout.of(8192,6,true,false);
        var a=PredictionCoverageWorkTest.tile(-1,-1,0,layout);
        var b=PredictionCoverageWorkTest.tile(1,1,0,layout);
        var cache=new PredictionRenderGeometry();
        var snapshot=new PredictionTileManager.RenderSnapshot(Level.OVERWORLD,layout,Map.of(a.key(),a,b.key(),b),Map.of());
        cache.update(snapshot);
        var near=cache.visible(new Vec3(-30,120,-30),null,8192);
        assertSame(a,near.getFirst().geometry().tile());
        var moved=cache.visible(new Vec3(96,120,96),null,8192);
        assertSame(b,moved.getFirst().geometry().tile());
        assertTrue(cache.visible(new Vec3(100000,120,100000),null,64).isEmpty());
        var replacement=PredictionCoverageWorkTest.tile(-1,-1,0,layout);
        cache.update(new PredictionTileManager.RenderSnapshot(Level.OVERWORLD,layout,Map.of(a.key(),replacement),Map.of()));
        assertSame(replacement,cache.visible(Vec3.ZERO,null,8192).getFirst().geometry().tile());
        cache.clear();assertTrue(cache.visible(Vec3.ZERO,null,8192).isEmpty());
    }
    @Test void cachedBoundsMatchOriginalFrustumAndHorizonAtEveryCamera() {
        var layout=VssLodLayout.of(8192,6,true,false);
        var random=new Random(7201);
        for(int i=0;i<1000;i++) {
            var tile=PredictionCoverageWorkTest.tile(random.nextInt(40)-20,random.nextInt(40)-20,random.nextInt(4),layout);
            var camera=new Vec3(random.nextDouble()*4096-2048,random.nextDouble()*512,random.nextDouble()*4096-2048);
            var projection=new org.joml.Matrix4f().perspective((float)Math.toRadians(20+random.nextInt(90)),1.7f,.1f,20000);
            var frustum=new net.minecraft.client.renderer.culling.Frustum(new org.joml.Matrix4f().rotateX(random.nextFloat()),projection);
            frustum.prepare(camera.x,camera.y,camera.z);
            double minX=tile.baseBlockX(),minZ=tile.baseBlockZ(),maxX=minX+tile.spanBlocks(),maxZ=minZ+tile.spanBlocks();
            var bounds=new net.minecraft.world.phys.AABB(minX,tile.depthBound().minY(),minZ,maxX,tile.depthBound().maxY(),maxZ).inflate(2);
            double dx=camera.x<minX?minX-camera.x:camera.x>maxX?camera.x-maxX:0;
            double dz=camera.z<minZ?minZ-camera.z:camera.z>maxZ?camera.z-maxZ:0;
            double limit=2048+Math.sqrt(2)*tile.spanBlocks()*.5;
            assertEquals(frustum.isVisible(bounds)&&dx*dx+dz*dz<=limit*limit,
                    new PredictionRenderGeometry.Entry(tile).visible(camera,frustum,2048));
        }
    }
    @Test void compareRepeatedGeometrySortWithFrameLocalDistances() {
        var layout=VssLodLayout.of(8192,6,true,false);
        var tiles=new LinkedHashMap<PredictionTileManager.PredictionTileKey,PredictionTileManager.PredictionTile>();
        for(int x=-35;x<35;x++) for(int z=-35;z<35;z++) {
            var tile=PredictionCoverageWorkTest.tile(x,z,0,layout);tiles.put(tile.key(),tile);
        }
        var snapshot=new PredictionTileManager.RenderSnapshot(Level.OVERWORLD,layout,tiles,Map.of());
        var cache=new PredictionRenderGeometry();cache.update(snapshot);
        long[][] times=new long[2][20];
        var bean=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
        long[] allocation=new long[2];long thread=Thread.currentThread().threadId();
        for(int frame=-10;frame<20;frame++) for(int mode:frame%2==0?new int[]{0,1}:new int[]{1,0}) {
            var camera=new Vec3(frame*.13,100,frame*.31);
            long bytes=bean.getThreadAllocatedBytes(thread),start=System.nanoTime();
            List<PredictionTileManager.PredictionTileKey> keys;
            if(mode==0) {
                var sorted=new ArrayList<>(tiles.values());
                sorted.sort(Comparator.comparingDouble(t->{
                    double dx=t.baseBlockX()+t.spanBlocks()*.5-camera.x,dz=t.baseBlockZ()+t.spanBlocks()*.5-camera.z;
                    return dx*dx+dz*dz;
                }));
                keys=sorted.stream().map(t->t.key()).toList();
            } else keys=cache.visible(camera,null,8192).stream().map(v->v.geometry().tile().key()).toList();
            long elapsed=System.nanoTime()-start;
            long allocated=bean.getThreadAllocatedBytes(thread)-bytes;
            assertEquals(4900,keys.size());
            // Independent expected order also catches negative-coordinate and tie changes.
            if(mode==1) {
                var expected=new ArrayList<>(tiles.values());
                expected.sort(Comparator.comparingDouble(t->{double dx=t.baseBlockX()+t.spanBlocks()*.5-camera.x,
                    dz=t.baseBlockZ()+t.spanBlocks()*.5-camera.z;return dx*dx+dz*dz;}));
                assertEquals(expected.stream().map(t->t.key()).toList(),keys);
            }
            if(frame>=0){times[mode][frame]=elapsed;allocation[mode]+=allocated;}
        }
        for(var t:times)Arrays.sort(t);
        System.out.printf(Locale.ROOT,"RENDER_GEOMETRY tiles=4900 oldSortMs=%.3f newCullSortMs=%.3f oldKiB=%.1f newKiB=%.1f%n",
            times[0][10]/1e6,times[1][10]/1e6,allocation[0]/20./1024,allocation[1]/20./1024);
    }
}
