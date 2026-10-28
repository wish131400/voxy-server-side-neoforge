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

    @Test void spatialFrustumBroadPhaseNeverDropsAnExactlyVisibleTile() {
        var index = new PredictionSpatialIndex<PredictionTileKey>();
        var keys = new ArrayList<PredictionTileKey>();
        var random = new Random(0x51A7);
        for (int i = 0; i < 180; i++) {
            int lod = random.nextInt(6);
            int x = random.nextInt(96) - 48, z = random.nextInt(96) - 48;
            var key = new PredictionTileKey(Level.OVERWORLD, x, z, lod);
            int minY = random.nextInt(160) - 32, maxY = minY + 1 + random.nextInt(220);
            index.put(key, key, minY, maxY);
            keys.add(key);
        }
        var camera = new Vec3(0.5, 96.0, 0.5);
        var view = new Matrix4f().lookAt((float) camera.x, (float) camera.y, (float) camera.z,
                900.0F, 96.0F, 700.0F, 0.0F, 1.0F, 0.0F);
        var projection = new Matrix4f().perspective((float) Math.toRadians(75), 1.6F, .1F, 20000.0F);
        var frustum = new Frustum(view, projection); frustum.prepare(camera.x, camera.y, camera.z);
        double horizon = 4096.0;
        var broad = new HashSet<PredictionTileKey>();
        index.intersectFrustum(frustum, camera, horizon, -1000, 1000, broad::add);
        for (var key : keys) {
            long span = 64L << key.lod();
            long bx = key.tileX() * span, bz = key.tileZ() * span;
            int minY = -1000, maxY = 1000;
            var box = new AABB(bx, minY, bz, bx + span, maxY, bz + span).inflate(2.0D);
            double dx = camera.x < bx ? bx - camera.x : camera.x > bx + span ? camera.x - (bx + span) : 0.0D;
            double dz = camera.z < bz ? bz - camera.z : camera.z > bz + span ? camera.z - (bz + span) : 0.0D;
            double margin = Math.sqrt(2.0D) * span * .5D;
            boolean exactlyVisible = dx * dx + dz * dz <= (horizon + margin) * (horizon + margin)
                    && frustum.isVisible(box);
            if (exactlyVisible) assertTrue(broad.contains(key), "broad phase dropped " + key);
        }
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













    @Test void spatialFrustumBroadPhaseKeepsEveryExactVisibleTile() {
        var index = new PredictionSpatialIndex<PredictionTileKey>();
        var keys = new HashSet<PredictionTileKey>();
        var random = new Random(921);
        for (int i = 0; i < 500; i++) {
            var key = new PredictionTileKey(Level.OVERWORLD, random.nextInt(80)-40,
                    random.nextInt(80)-40, random.nextInt(6));
            keys.add(key);
            index.put(key, key, -128, 384);
        }
        var camera = new Vec3(13, 95, -7);
        var projection = new Matrix4f().perspective((float)Math.toRadians(75), 1.6F, .1F, 20000F);
        for (int turn = 0; turn < 8; turn++) {
            var view = new Matrix4f().rotateY(turn * .45F);
            var frustum = new Frustum(view, projection);frustum.prepare(camera.x, camera.y, camera.z);
            var actual = new HashSet<PredictionTileKey>();
            index.intersectFrustum(frustum, camera, 4096, -128, 384, actual::add);
            for (var key : keys) {
                long span = 64L << key.lod(), bx = (long)key.tileX()*span, bz = (long)key.tileZ()*span;
                var box = new AABB(bx, -128, bz, bx+span, 384, bz+span).inflate(2);
                double dx = camera.x < bx ? bx-camera.x : camera.x > bx+span ? camera.x-(bx+span) : 0;
                double dz = camera.z < bz ? bz-camera.z : camera.z > bz+span ? camera.z-(bz+span) : 0;
                double margin = Math.sqrt(2) * span * .5;
                if (dx*dx+dz*dz <= (4096+margin)*(4096+margin) && frustum.isVisible(box))
                    assertTrue(actual.contains(key), "broad phase must retain exact-visible " + key);
            }
        }
    }


}
