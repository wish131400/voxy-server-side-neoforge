package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PredictionMeshCodecTest {
    @TempDir Path directory;
    @BeforeAll static void bootstrap(){ClientTerrainSamplerTest.bootstrapMinecraft();}
    static PredictionMesh fixture() { return fixture(16); }
    static PredictionMesh fixture(int axis) {
        int grid = axis + 2;
        ClientColumnSample[] samples=new ClientColumnSample[grid*grid];
        for(int i=0;i<samples.length;i++) {
            int y=60+i%13;
            samples[i]=new ClientColumnSample(y,Math.max(63,y),0,PredictionMaterialPalette.grassBlockIndex(),0,0,0,0,
                    y<63?1:0,0,0,ClientColumnSample.NO_BLOCK,ClientColumnSample.NO_BLOCK,
                    ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN);
        }
        var mesh=PredictionMeshBuilder.build(samples,null,63,0xff509050,1,grid,false,null,null,null,0,0,PredictionVegetation.Tile.EMPTY);
        // Use the builder's real cell grid, keeping the fixture independent of GPU/atlas initialization.
        var key=new PredictionTileManager.PredictionTileKey(net.minecraft.world.level.Level.OVERWORLD,0,0,0);
        mesh=mesh.compactForRendering();axis=mesh.cellAxis();int n=(axis+1)*(axis+1);
        var tile=new PredictionTileManager.PredictionTile(key,new int[n],new int[n],new ClientColumnSample[n],mesh,
                new PredictionDepthBound(60,80),0,1,axis,1);
        mesh.morph(new float[n]);mesh.prepareGpuPayload(tile);return mesh;
    }
    @Test void exactGeometryAndSeamsRestoreButOldParentMorphDoesNot()throws Exception {
        var mesh=fixture();byte[] signature=new byte[32];
        var restored=PredictionMeshCodec.decode(PredictionMeshCodec.encode(mesh,signature),signature,mesh.cellAxis());
        assertNotNull(restored);assertArrayEquals(mesh.gpuPayload().quads(),restored.gpuPayload().quads());
        assertEquals(mesh.vertexCount(),restored.vertexCount());
        for(int i=0;i<mesh.cellCount();i++) {
            assertEquals(mesh.seamMesh().hasTop(i),restored.seamMesh().hasTop(i));
            assertEquals(mesh.seamMesh().topY(i),restored.seamMesh().topY(i));
            assertEquals(mesh.seamMesh().topColor(i),restored.seamMesh().topColor(i));
        }
        assertNull(restored.gpuPayload().morph());float[] fresh={1,2,3};restored.morph(fresh);
        assertArrayEquals(fresh,restored.gpuPayload().morph());
        byte[] bytes=PredictionMeshCodec.encode(mesh,signature),changed=signature.clone();changed[0]=1;
        assertNull(PredictionMeshCodec.decode(bytes,changed,mesh.cellAxis()));
        assertThrows(java.io.IOException.class,()->PredictionMeshCodec.decode(Arrays.copyOf(bytes,bytes.length-1),signature,mesh.cellAxis()));
        assertThrows(java.io.IOException.class,()->PredictionMeshCodec.decode(bytes,signature,mesh.cellAxis()+1));
    }
    @Test void regionReopenAndTerrainInvalidationCoverFinishedGeometry()throws Exception {
        var mesh=fixture();byte[] signature=new byte[32];var key=PredictionDiskCache.Key.terrain(0,0,0);
        try(var cache=new PredictionDiskCache(directory,91);var lease=cache.lease(key)) {
            cache.writeMeshLater(lease,signature,mesh);cache.flushMeshes();cache.flush();
            assertNotNull(cache.readMesh(lease,signature,mesh.cellAxis()));
        }
        try(var cache=new PredictionDiskCache(directory,91)) {
            try(var lease=cache.lease(key)){assertNotNull(cache.readMesh(lease,signature,mesh.cellAxis()));}
            cache.invalidate(List.of(key));cache.flush();
            try(var lease=cache.lease(key)){assertNull(cache.readMesh(lease,signature,mesh.cellAxis()));}
        }
        try(var cache=new PredictionDiskCache(directory,91);var lease=cache.lease(key)) {
            assertNull(cache.readMesh(lease,signature,mesh.cellAxis()));
        }
    }

    @Test void queuedSaveCannotResurrectGeometryAfterTerrainInvalidation()throws Exception {
        var field=PredictionDiskCache.class.getDeclaredField("MESH_WRITES");field.setAccessible(true);
        var executor=(java.util.concurrent.ExecutorService)field.get(null);
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var gate=executor.submit(()->{entered.countDown();release.await();return true;});
        var key=PredictionDiskCache.Key.terrain(0,0,0);var mesh=fixture();byte[] signature=new byte[32];
        try(var cache=new PredictionDiskCache(directory,91)) {
            try {
                assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
                try(var lease=cache.lease(key)){cache.writeMeshLater(lease,signature,mesh);}
                cache.invalidate(List.of(key));cache.flush();
            }finally{release.countDown();gate.get();}
            cache.flushMeshes();cache.flush();
            try(var lease=cache.lease(key)){assertNull(cache.readMesh(lease,signature,mesh.cellAxis()));}
        }
    }

    @Test void identityTracksResourceColorAndCapturedGeometryChanges() {
        var sample=new ClientColumnSample(64,64,0,1,0,0,0,0,0,0,0,1,1,
                ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN);
        byte[] resources=new byte[32];int[] colors={0xff123456};var samples=new ClientColumnSample[]{sample};
        var plants=PredictionVegetation.Tile.EMPTY;var simple=PredictionSimpleVegetation.Result.EMPTY;
        byte[] a=PredictionMeshCodec.signature(resources,samples,colors,colors,colors,63,0,1,false,plants,simple);
        colors[0]^=1;
        assertFalse(Arrays.equals(a,PredictionMeshCodec.signature(resources,samples,colors,colors,colors,63,0,1,false,plants,simple)));
        colors[0]^=1;resources[0]=1;
        assertFalse(Arrays.equals(a,PredictionMeshCodec.signature(resources,samples,colors,colors,colors,63,0,1,false,plants,simple)));
        resources[0]=0;
        assertFalse(Arrays.equals(a,PredictionMeshCodec.signature(resources,samples,colors,colors,colors,63,0,2,false,plants,simple)));
        assertNull(PredictionMeshCodec.signature(null,samples,colors,colors,colors,63,0,1,false,plants,simple));
    }
}
