package dev.xantha.vss.client.prediction;

import java.util.*;
import dev.xantha.vss.compat.VoxyPredictionBridge;

/** Stable conservative mask for predicted voxels; real Voxy data is merged afterwards. */
record PredictionVoxyHorizon(double x,double z,int radius) {
    static PredictionVoxyHorizon of(double x,double z,int radius) {
        // Every camera position in this 64-block cell remains inside the mask.
        // Moving within it changes priorities, without remeshing the horizon.
        return new PredictionVoxyHorizon(Math.floor(x/64)*64+32,Math.floor(z/64)*64+32,radius+46);
    }
    boolean intersects(double bx,double bz,double span) {
        double dx=Math.max(0,Math.max(bx-x,x-bx-span)),dz=Math.max(0,Math.max(bz-z,z-bz-span));
        return dx*dx+dz*dz<=(double)radius*radius;
    }
    private boolean contains(double bx,double bz,double span) {
        double dx=Math.max(Math.abs(bx-x),Math.abs(bx+span-x)),dz=Math.max(Math.abs(bz-z),Math.abs(bz+span-z));
        return dx*dx+dz*dz<=(double)radius*radius;
    }
    boolean affected(long pos,PredictionVoxyHorizon next) {
        int span=32<<VoxyPredictionBridge.level(pos),step=border(VoxyPredictionBridge.level(pos));
        double bx=(double)VoxyPredictionBridge.x(pos)*span-step,bz=(double)VoxyPredictionBridge.z(pos)*span-step;
        return (intersects(bx,bz,span+2*step) || next.intersects(bx,bz,span+2*step))
                && !(contains(bx,bz,span+2*step) && next.contains(bx,bz,span+2*step));
    }
    boolean clips(long pos) {
        int span=32<<VoxyPredictionBridge.level(pos),step=border(VoxyPredictionBridge.level(pos));
        return !contains((double)VoxyPredictionBridge.x(pos)*span-step,
                (double)VoxyPredictionBridge.z(pos)*span-step,span+2*step);
    }
    private static int border(int lod) {
        int step=1<<lod;
        return ((PredictionVoxyLighting.REACH+step-1)/step)*step;
    }
    record Box(int x,int z,int span) { }
    /** Visit only boundary buckets, skipping common interior and exterior subtrees. */
    List<Box> changes(PredictionVoxyHorizon next) {
        if(equals(next))return List.of();
        int minX=(int)Math.floor(Math.min(x-radius,next.x-next.radius)/512)*512;
        int minZ=(int)Math.floor(Math.min(z-radius,next.z-next.radius)/512)*512;
        double maxX=Math.max(x+radius,next.x+next.radius),maxZ=Math.max(z+radius,next.z+next.radius);
        int size=512;while(minX+(double)size<maxX || minZ+(double)size<maxZ)size*=2;
        var result=new ArrayList<Box>();collect(next,minX,minZ,size,result);return result;
    }
    private void collect(PredictionVoxyHorizon next,int bx,int bz,int size,List<Box> result) {
        if(!intersects(bx,bz,size) && !next.intersects(bx,bz,size)
                || contains(bx,bz,size) && next.contains(bx,bz,size))return;
        // Teleports and distance changes can invalidate whole subtrees. Keep
        // these as one indexed query instead of expanding every interior bucket.
        if(size==512 || contains(bx,bz,size) && !next.intersects(bx,bz,size)
                || next.contains(bx,bz,size) && !intersects(bx,bz,size)) {
            result.add(new Box(bx,bz,size));return;
        }
        int half=size/2;
        for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++)collect(next,bx+dx*half,bz+dz*half,half,result);
    }
}
