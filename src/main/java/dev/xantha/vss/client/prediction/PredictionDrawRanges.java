package dev.xantha.vss.client.prediction;

/** Consecutive selected index ranges, in the same order as the original draws. */
final class PredictionDrawRanges {
    final int[] first, count;
    final int quads;
    /** Already selected ordered ranges, including plans with more than 32 runs. */
    PredictionDrawRanges(int[] first, int[] count) {
        this.first = first; this.count = count;
        this.quads = java.util.Arrays.stream(count).sum();
    }
    PredictionDrawRanges(int[] starts, int[] counts, int visible) {
        int[] f=new int[counts.length], c=new int[counts.length];
        int size=0,total=0;
        for(int i=0;i<counts.length;i++) {
            if(counts[i]==0 || (visible & (1<<i))==0) continue;
            if(size>0 && f[size-1]+c[size-1]==starts[i]) c[size-1]+=counts[i];
            else {f[size]=starts[i];c[size++]=counts[i];}
            total+=counts[i];
        }
        first=java.util.Arrays.copyOf(f,size);count=java.util.Arrays.copyOf(c,size);quads=total;
    }
}
