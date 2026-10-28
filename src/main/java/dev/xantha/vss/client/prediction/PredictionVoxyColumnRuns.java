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
    /** A page shares array headers, cache membership and retention across adjacent columns. */
    static final class Page {
        final int columns;
        private final int[] offsets, starts, ends;
        private final long[] values;
        Page(int columns,int[] offsets,int[] starts,int[] ends,long[] values) {
            this.columns=columns;this.offsets=offsets;this.starts=starts;this.ends=ends;this.values=values;
        }
        long bytes() {return 128L+offsets.length*4L+values.length*16L;}
        void write(int column,long[] target,int x,int z,int baseY,int width,int face) {
            int first=offsets[column],last=offsets[column+1];
            if(face>=0 && face>>1==1) {
                int y=baseY+(face&1)*31;
                int at=Arrays.binarySearch(starts,first,last,y);if(at<0)at=-at-2;
                target[z*32+x]=at>=first && y<ends[at] ? values[at] : 15L<<56;return;
            }
            for(int r=first;r<last;r++) {
                int lo=Math.max(0,starts[r]-baseY),hi=Math.min(width,ends[r]-baseY);
                for(int y=lo;y<hi;y++)target[face<0 ? (y*width+z)*width+x : y*32+(face>>1==0?z:x)]=values[r];
            }
        }
    }
    static final class PageBuilder {
        private final int[] offsets;
        private int[] starts=new int[64],ends=new int[64];
        private long[] values=new long[64];
        private int size,columns;
        PageBuilder(int columns) {offsets=new int[columns+1];}
        void append(Builder source) {
            for(int i=0;i+1<source.size;i++)if(source.materials[i]!=source.air) {
                if(size==values.length) {
                    starts=Arrays.copyOf(starts,size*2);ends=Arrays.copyOf(ends,size*2);values=Arrays.copyOf(values,size*2);
                }
                starts[size]=source.boundaries[i];ends[size]=source.boundaries[i+1];values[size++]=source.materials[i];
            }
            offsets[++columns]=size;
        }
        Page build() {return new Page(columns,offsets,Arrays.copyOf(starts,size),Arrays.copyOf(ends,size),Arrays.copyOf(values,size));}
    }
    /** Disjoint covered intervals, including explicit air. Only uncovered spans are written. */
    static final class Coverage {
        private int[] starts=new int[8],ends=new int[8];
        private int size;
        void reset() {size=0;}
        private int before(int y) {
            int at=Arrays.binarySearch(starts,0,size,y);return at>=0 ? at : -at-2;
        }
        boolean covers(int bottom,int top) {
            if(bottom>=top)return true;
            int at=before(bottom);return at>=0 && ends[at]>=top;
        }
        void put(Builder builder,int bottom,int top,long value) {
            if(bottom>=top || covers(bottom,top))return;
            int at=before(bottom),cursor=bottom;
            if(at>=0 && ends[at]>=bottom)cursor=ends[at];
            else at++;
            int first=at;
            while(at<size && starts[at]<=top) {
                if(cursor<starts[at])builder.put(cursor,Math.min(top,starts[at]),value);
                cursor=Math.max(cursor,ends[at]);at++;
            }
            if(cursor<top)builder.put(cursor,top,value);
            int lo=first<size && starts[first]<=bottom ? starts[first] : bottom;
            int hi=Math.max(top,cursor),length=size-(at-first)+1;
            if(length>starts.length) {starts=Arrays.copyOf(starts,length*2);ends=Arrays.copyOf(ends,length*2);}
            System.arraycopy(starts,at,starts,first+1,size-at);
            System.arraycopy(ends,at,ends,first+1,size-at);
            starts[first]=lo;ends[first]=hi;size=length;
        }
    }
    static final class Builder {
        private int[] boundaries=new int[16];
        private long[] materials=new long[16];
        private int size;
        private final long air;
        Builder(long air) { this.air=air; }
        void reset() {size=0;}
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
