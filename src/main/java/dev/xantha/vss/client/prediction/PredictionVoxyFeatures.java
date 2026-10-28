package dev.xantha.vss.client.prediction;

import java.util.List;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntArrays;

/** Flat immutable bounds tree; query results preserve overlapping publication order. */
final class PredictionVoxyFeatures {
    interface Coordinates { int coordinate(int id,int axis); }
    private static final int LEAF_SIZE=16, STRIDE=10;
    private static final PredictionVoxyFeatures EMPTY=new PredictionVoxyFeatures(List.of());
    private final Coordinates boxes;
    private final PredictionVoxyBoxes pages;
    private final PredictionVoxyFeatures pageTree;
    private final int[] order,bounds;
    private int nodes;
    private static final class PageQuery {
        boolean busy;
        final IntArrayList hits=new IntArrayList();
    }
    private static final ThreadLocal<PageQuery> PAGE_QUERIES=ThreadLocal.withInitial(PageQuery::new);
    static PredictionVoxyFeatures of(List<PredictionVoxySource.Box> boxes) {
        return boxes.isEmpty()?EMPTY:PredictionVoxyBoxes.of(boxes).index();
    }
    PredictionVoxyFeatures(List<PredictionVoxySource.Box> boxes) {
        pages=PredictionVoxyBoxes.of(boxes);this.boxes=pages;
        order=new int[0];bounds=new int[0];
        pageTree=pages.pageCount()>1?new PredictionVoxyFeatures(pages::pageCoordinate,pages.pageCount()):null;
    }
    PredictionVoxyFeatures(Coordinates boxes,int size) {
        this.boxes=boxes;pages=null;pageTree=null;order=new int[size];
        bounds=new int[nodeCount(order.length)*STRIDE];
        for(int i=0;i<order.length;i++)order[i]=i;
        if(order.length!=0)build(0,order.length);
    }
    private static int nodeCount(int n) {return n==0?0:n<=LEAF_SIZE?1:1+nodeCount(n/2)+nodeCount(n-n/2);}
    // Reserve the lazy indexes up front so first mesh use cannot bypass residency accounting.
    long bytes() {return pages!=null ? pages.indexBytes()+(pageTree==null?0:pageTree.bytes()) : treeBytes(order.length);}
    static long treeBytes(int size) {return size==0?0:80L+size*4L+nodeCount(size)*STRIDE*4L;}
    private int build(int start,int end) {
        int at=nodes++*STRIDE;
        for(int a=0;a<3;a++){bounds[at+a]=Integer.MAX_VALUE;bounds[at+a+3]=Integer.MIN_VALUE;}
        for(int i=start;i<end;i++)for(int a=0;a<3;a++) {
            bounds[at+a]=Math.min(bounds[at+a],boxes.coordinate(order[i],a));
            bounds[at+a+3]=Math.max(bounds[at+a+3],boxes.coordinate(order[i],a+3));
        }
        bounds[at+6]=start;bounds[at+7]=end;
        if(end-start>LEAF_SIZE) {
            int axis=(long)bounds[at+3]-bounds[at]>=(long)bounds[at+5]-bounds[at+2]?0:2;
            int middle=(start+end)>>>1;
            partition(start,end,middle,axis);
            bounds[at+8]=build(start,middle);bounds[at+9]=build(middle,end);
        }
        return at;
    }
    private int compare(int a,int b,int axis) {
        long ac=(long)boxes.coordinate(a,axis)+boxes.coordinate(a,axis+3);
        long bc=(long)boxes.coordinate(b,axis)+boxes.coordinate(b,axis+3);
        int cmp=Long.compare(ac,bc);return cmp==0?Integer.compare(a,b):cmp;
    }
    /** Only the median partition is needed; sorting every subtree repeats O(n log n) work. */
    private void partition(int start,int end,int middle,int axis) {
        int budget=2*(32-Integer.numberOfLeadingZeros(end-start));
        while(end-start>1) {
            if(budget--==0) {IntArrays.quickSort(order,start,end,(a,b)->compare(a,b,axis));return;}
            int a=order[start],b=order[(start+end)>>>1],c=order[end-1];
            int pivot=compare(a,b,axis)<0 ? (compare(b,c,axis)<0?b:compare(a,c,axis)<0?c:a)
                    : (compare(a,c,axis)<0?a:compare(b,c,axis)<0?c:b);
            int left=start,right=end-1;
            while(left<=right) {
                while(compare(order[left],pivot,axis)<0)left++;
                while(compare(order[right],pivot,axis)>0)right--;
                if(left<=right) {int tmp=order[left];order[left++]=order[right];order[right--]=tmp;}
            }
            if(middle<=right)end=right+1;
            else if(middle>=left)start=left;
            else return;
        }
    }
    int[] intersect(int minX,int minY,int minZ,int maxX,int maxY,int maxZ) {
        var result=new IntArrayList();intersect(minX,minY,minZ,maxX,maxY,maxZ,result);return result.toIntArray();
    }
    void intersect(int minX,int minY,int minZ,int maxX,int maxY,int maxZ,IntArrayList result) {
        if(pages!=null) {
            if(pageTree==null) {pages.intersect(minX,minY,minZ,maxX,maxY,maxZ,result,null);return;}
            var query=PAGE_QUERIES.get();if(query.busy)query=new PageQuery();query.busy=true;
            try {
                pageTree.intersect(minX,minY,minZ,maxX,maxY,maxZ,query.hits);
                pages.intersect(minX,minY,minZ,maxX,maxY,maxZ,result,query.hits);
            } finally {query.hits.clear();query.busy=false;}
            return;
        }
        result.clear();append(minX,minY,minZ,maxX,maxY,maxZ,result,0);
    }
    void append(int minX,int minY,int minZ,int maxX,int maxY,int maxZ,IntArrayList result,int base) {
        if(order.length==0)return;
        if(minX<=bounds[0] && minY<=bounds[1] && minZ<=bounds[2]
                && maxX>=bounds[3] && maxY>=bounds[4] && maxZ>=bounds[5]) {
            for(int i=0;i<order.length;i++)result.add(base+i);return;
        }
        int start=result.size();
        visit(0,minX,minY,minZ,maxX,maxY,maxZ,result);
        IntArrays.quickSort(result.elements(),start,result.size());
        for(int i=start;i<result.size();i++)result.set(i,result.getInt(i)+base);
    }
    private void visit(int at,int minX,int minY,int minZ,int maxX,int maxY,int maxZ,IntArrayList result) {
        if(bounds[at]>=maxX || bounds[at+3]<=minX || bounds[at+1]>=maxY || bounds[at+4]<=minY
                || bounds[at+2]>=maxZ || bounds[at+5]<=minZ)return;
        if(bounds[at+9]!=0) {visit(bounds[at+8],minX,minY,minZ,maxX,maxY,maxZ,result);
            visit(bounds[at+9],minX,minY,minZ,maxX,maxY,maxZ,result);return;}
        for(int i=bounds[at+6];i<bounds[at+7];i++) {
            int id=order[i];
            if(boxes.coordinate(id,0)<maxX && boxes.coordinate(id,3)>minX && boxes.coordinate(id,1)<maxY
                    && boxes.coordinate(id,4)>minY && boxes.coordinate(id,2)<maxZ && boxes.coordinate(id,5)>minZ)result.add(id);
        }
    }
}
