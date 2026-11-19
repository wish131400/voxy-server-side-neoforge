package dev.xantha.vss.client.prediction;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Quality of terrain before runtime surface hooks; independent of the grid resolution. */
final class PredictionSurfaceRefinement {
    private PredictionSurfaceRefinement() { }

    static boolean pending(ClientColumnSample[] samples) {
        for (var sample : samples) if (sample != null && sample.surfacePending()) return true;
        return false;
    }

    static boolean pending(PredictionTileManager.PredictionTile tile) {
        return pending(tile.data().surfaceRefinement == null ? tile.samples() : tile.data().surfaceRefinement);
    }

    static ClientColumnSample basic(ClientColumnSample sample) {
        if (sample == null || sample.captured() || sample.surfacePending()) return sample;
        return new ClientColumnSample(sample.surfaceY(), sample.fluidY(), sample.biomeIndex(),
                sample.topBlockIndex(), sample.structureIndex(), sample.treeKind(), sample.treeDensity(),
                sample.treeHeight(), sample.fluid(), sample.flags() | ClientColumnSample.FLAG_SURFACE_PENDING,
                sample.groundFeatureKind(), sample.underBlockIndex(), sample.deepBlockIndex(),
                sample.surfaceBottom(), sample.lowerTop(), sample.lowerBottom(), sample.spanFloor(), sample.volume());
    }

    /** Resolution is primary; within one grid, completed surface columns must never regress on disk. */
    static int quality(ClientColumnSample[] samples) {
        int complete = 0;
        for (var sample : samples) if (sample != null && !sample.surfacePending()) complete++;
        return samples.length * (66 * 66 + 1) + complete;
    }

    static boolean basicPass(boolean staged, ClientColumnSample[] resident, int axis, int target) {
        return staged && (resident == null || pending(resident) && axis < target);
    }

    /** Consume all requested columns of a chunk before another chunk can evict its cached result. */
    static void completeGrid(ClientTerrainSampler sampler, int baseX, int baseZ, int step, int grid,
                             ClientColumnSample[] samples, BooleanSupplier current) {
        completeChunks(sampler,baseX,baseZ,step,grid,samples,current,System::nanoTime,Long.MAX_VALUE,Integer.MAX_VALUE);
    }

    /** Commit a bounded slice, including every requested column in each admitted chunk. */
    static void completeGridSlice(ClientTerrainSampler sampler, int baseX, int baseZ, int step, int grid,
                                  ClientColumnSample[] samples, BooleanSupplier current) {
        completeChunks(sampler,baseX,baseZ,step,grid,samples,current,System::nanoTime,32_000_000L,8);
    }

    static void completeChunks(ClientTerrainSampler sampler, int baseX, int baseZ, int step, int grid,
                               ClientColumnSample[] samples, BooleanSupplier current, LongSupplier clock,
                               long budgetNanos, int maxChunks) {
        long started=clock.getAsLong();
        int completed=0;
        for(int z=0;z<grid;) {
          int endZ=end(baseZ,z,step,grid);
          for(int x=0;x<grid;) {
            int endX=end(baseX,x,step,grid);
            boolean needed=false;
            for(int zz=z;zz<endZ && !needed;zz++)for(int xx=x;xx<endX;xx++)
                if(required(samples[zz*grid+xx])){needed=true;break;}
            if(!needed){x=endX;continue;}
            if(completed>0 && (completed>=maxChunks || clock.getAsLong()-started>=budgetNanos))break;
            sampler.refineSurfaceChunk(baseX,baseZ,step,grid,samples,x,endX,z,endZ,current);
            completed++;x=endX;
          }
          if(completed>0 && (completed>=maxChunks || clock.getAsLong()-started>=budgetNanos))break;
          z=endZ;
        }
    }
    static boolean required(ClientColumnSample sample) {
        return sample==null || !sample.captured() && !(sample.reusableFor(false) && sample.volume()!=null);
    }
    private static int end(int base,int start,int step,int grid) {
        int world=base+(start-VssLodLayout.SAMPLE_MARGIN)*step;
        return Math.min(grid,start+(16-Math.floorMod(world,16)-1)/step+1);
    }
}
