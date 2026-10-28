package dev.xantha.vss.client.prediction;

import java.util.Arrays;

/** Quantized world-Y intervals. Later columns overwrite exactly the same cells as rasterization. */
final class PredictionVoxyColumnRuns {
    private final int[] starts, ends;
    private final long[] values;
    private PredictionVoxyColumnRuns(int[] starts, int[] ends, long[] values) {
        this.starts=starts; this.ends=ends; this.values=values;
    }
    long bytes() { return 96L + values.length*16L; }
    long value(int y,long air) {
        int at=java.util.Arrays.binarySearch(starts,y);
        if(at<0)at=-at-2;
        return at>=0 && y<ends[at] ? values[at] : air;
    }
    void write(long[] target,int x,int z,int baseY,int width,int face) {
        if(face>=0 && face>>1==1) {
            target[z*32+x]=value(baseY+(face&1)*31,15L<<56);return;
        }
        for(int r=0;r<values.length;r++) {
            int lo=Math.max(0,starts[r]-baseY), hi=Math.min(width,ends[r]-baseY);
            for(int y=lo;y<hi;y++)target[face<0 ? (y*width+z)*width+x : y*32+(face>>1==0?z:x)]=values[r];
        }
    }
    static final class Builder {
        private int[] boundaries=new int[16];
        private long[] materials=new long[16];
        private int size;
        private final long air;
        Builder(long air) { this.air=air; }
        void put(int bottom,int top,long value) {
            if(bottom>=top)return;
            int left=Arrays.binarySearch(boundaries,0,size,bottom);if(left<0)left=-left-1;
            int right=Arrays.binarySearch(boundaries,0,size,top);
            long after=right>=0 ? materials[right] : right < -1 ? materials[-right-2] : air;
            right=right>=0 ? right+1 : -right-1;
            long before=left==0 ? air : materials[left-1];
            int count=(before==value?0:1)+(after==value?0:1),length=size-(right-left)+count;
            if(length>boundaries.length) {
                int capacity=Math.max(length,boundaries.length*2);
                boundaries=Arrays.copyOf(boundaries,capacity);materials=Arrays.copyOf(materials,capacity);
            }
            System.arraycopy(boundaries,right,boundaries,left+count,size-right);
            System.arraycopy(materials,right,materials,left+count,size-right);
            int at=left;
            if(before!=value) {boundaries[at]=bottom;materials[at++]=value;}
            if(after!=value) {boundaries[at]=top;materials[at]=after;}
            size=length;
        }
        PredictionVoxyColumnRuns build() {
            int count=0;
            for(int i=0;i+1<size;i++)if(materials[i]!=air)count++;
            int[] starts=new int[count],ends=new int[count];long[] values=new long[count];int i=0;
            for(int at=0;at+1<size;at++) {
                if(materials[at]==air)continue;
                starts[i]=boundaries[at];ends[i]=boundaries[at+1];values[i++]=materials[at];
            }
            return new PredictionVoxyColumnRuns(starts,ends,values);
        }
    }
}
