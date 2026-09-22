package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="VSS_FINISHED_MESH_BENCH",matches=".+")
class PredictionMeshCacheIntegrationBenchmark {
    private static volatile Object sink;
    @Test void measureIndexedProductionRestoreIncludingInputIdentity()throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        Path root=Path.of(System.getenv("VSS_FINISHED_MESH_BENCH"));Files.createDirectories(root);
        var report=new StringBuilder("axis,quads,buildMs,restoreWithIdentityMs,buildAllocatedBytes,restoreAllocatedBytes\n");
        var bean=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);long thread=Thread.currentThread().getId();
        for(int axis:new int[]{16,64}) {
            int grid=axis+2;ClientColumnSample[] samples=new ClientColumnSample[grid*grid];int[] colors=new int[samples.length];
            for(int i=0;i<samples.length;i++) {
                int y=i%grid<grid/3?52:68+(i*13+i/grid*7)%11;
                samples[i]=new ClientColumnSample(y,Math.max(y,63),0,PredictionMaterialPalette.grassBlockIndex(),0,0,0,0,
                        y<63?1:0,0,0,ClientColumnSample.NO_BLOCK,ClientColumnSample.NO_BLOCK,
                        ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN);
                colors[i]=0xff507850;
            }
            var mesh=build(samples,colors,grid);byte[] identity=identity(samples,colors);
            try(var cache=new PredictionDiskCache(root.resolve("axis-"+axis),1);var lease=cache.lease(PredictionDiskCache.Key.terrain(0,0,0))) {
                cache.writeMeshLater(lease,identity,mesh);cache.flushMeshes();cache.flush();
                assertNotNull(cache.readMesh(lease,identity,axis));
                long[][] time=new long[2][31],alloc=new long[2][31];
                for(int round=-8;round<31;round++)for(int order=0;order<2;order++) {
                    int mode=Math.floorMod(round+order,2);long before=bean.getThreadAllocatedBytes(thread),start=System.nanoTime();
                    var result=mode==0?build(samples,colors,grid):cache.readMesh(lease,identity(samples,colors),axis);
                    long elapsed=System.nanoTime()-start,bytes=bean.getThreadAllocatedBytes(thread)-before;
                    assertNotNull(result);sink=result;
                    if(round>=0){time[mode][round]=elapsed;alloc[mode][round]=bytes;}
                    if(round==0||round==30)assertArrayEquals(mesh.gpuPayload().quads(),result.gpuPayload().quads());
                }
                for(var values:time)Arrays.sort(values);for(var values:alloc)Arrays.sort(values);
                String line=String.format(Locale.ROOT,"%d,%d,%.6f,%.6f,%d,%d%n",axis,mesh.gpuPayload().quadCount(),time[0][15]/1e6,time[1][15]/1e6,alloc[0][15],alloc[1][15]);
                report.append(line);System.out.print("PRODUCTION_CACHE "+line);
            }
        }
        Files.writeString(root.resolve("benchmark.csv"),report);
    }
    private static byte[] identity(ClientColumnSample[] samples,int[] colors) {
        return PredictionMeshCodec.signature(new byte[32],samples,colors,colors,colors,63,0xff3878a0,1,false,
                PredictionVegetation.Tile.EMPTY,PredictionSimpleVegetation.Result.EMPTY);
    }
    private static PredictionMesh build(ClientColumnSample[] samples,int[] colors,int grid) {
        var mesh=PredictionMeshBuilder.build(samples,colors,63,0xff3878a0,1,grid,false,null,null,null,0,0,PredictionVegetation.Tile.EMPTY).compactForRendering();
        int n=(mesh.cellAxis()+1)*(mesh.cellAxis()+1);
        var tile=new PredictionTileManager.PredictionTile(new PredictionTileManager.PredictionTileKey(net.minecraft.world.level.Level.OVERWORLD,0,0,0),
                new int[n],new int[n],new ClientColumnSample[n],mesh,new PredictionDepthBound(48,96),0,1,mesh.cellAxis(),1);
        mesh.prepareGpuPayload(tile);return mesh;
    }
}
