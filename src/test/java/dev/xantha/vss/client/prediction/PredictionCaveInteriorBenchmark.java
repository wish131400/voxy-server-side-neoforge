package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.*;

/** Mesh-only comparison; no world generation or native sampler is invoked. */
class PredictionCaveInteriorBenchmark {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void compareSameOccupancyWithAndWithoutInteriorCaps() {
        var cave=PredictionCaveInteriorTest.cave();
        var noCaps=new ClientColumnSample(cave.surfaceY(),cave.fluidY(),cave.biomeIndex(),cave.topBlockIndex(),
                cave.structureIndex(),cave.treeKind(),cave.treeDensity(),cave.treeHeight(),cave.fluid(),
                cave.flags() & ~PredictionWallEvidence.CHECKED,cave.groundFeatureKind(),cave.underBlockIndex(),
                cave.deepBlockIndex(),cave.surfaceBottom(),cave.lowerTop(),cave.lowerBottom(),cave.spanFloor());
        for(int cells:new int[]{64,256,4096}) {
            var samples=new ClientColumnSample[2][66*66];
            for(var grid:samples) Arrays.fill(grid,PredictionSimpleVegetationTest.sample(120));
            for(int k=0;k<cells;k++) {
                int cell=k*67%4096,index=(cell/64+1)*66+cell%64+1;
                samples[0][index]=noCaps; samples[1][index]=cave;
            }
            long[][] times=new long[2][40]; int[] quads=new int[2];
            for(int iteration=0;iteration<64;iteration++) for(int order=0;order<2;order++) {
                int mode=(iteration+order)%2;
                long start=System.nanoTime();
                var mesh=PredictionMeshBuilder.build(samples[mode],null,63,0,1,66,false).compactForRendering();
                int count=mesh.packed().quadCount();
                long elapsed=System.nanoTime()-start;
                if(iteration>=24) times[mode][iteration-24]=elapsed;
                quads[mode]=count;
            }
            for(var values:times) Arrays.sort(values);
            assertEquals(cells*2,quads[1]-quads[0]);
            System.out.printf(Locale.ROOT,
                    "CAVE_CAPS cells=%d offMedianMs=%.3f onMedianMs=%.3f offP95Ms=%.3f onP95Ms=%.3f addedQuads=%d packedKiB=%.3f extraColumnQueries=0%n",
                    cells,(times[0][19]+times[0][20])/2e6,(times[1][19]+times[1][20])/2e6,
                    times[0][37]/1e6,times[1][37]/1e6,quads[1]-quads[0],(quads[1]-quads[0])*48/1024.0);
        }
    }
}
