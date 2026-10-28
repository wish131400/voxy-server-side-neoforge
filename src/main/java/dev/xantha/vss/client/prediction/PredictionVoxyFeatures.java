package dev.xantha.vss.client.prediction;

import java.util.List;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntArrays;

/** Immutable bounds index. Query results retain publication order, including overlapping writes. */
final class PredictionVoxyFeatures {
    private static final PredictionVoxyFeatures EMPTY=new PredictionVoxyFeatures(List.of());
    private static final int LEAF_SIZE=16;
    private record Node(int minX,int minY,int minZ,int maxX,int maxY,int maxZ,
                        int start,int end,Node left,Node right) { }
    private final List<PredictionVoxySource.Box> boxes;
    private final int[] order;
    private final Node root;
    private int nodes;

    static PredictionVoxyFeatures of(List<PredictionVoxySource.Box> boxes) {
        return boxes.isEmpty()?EMPTY:new PredictionVoxyFeatures(boxes);
    }

    PredictionVoxyFeatures(List<PredictionVoxySource.Box> boxes) {
        this.boxes=boxes;order=new int[boxes.size()];
        for(int i=0;i<order.length;i++)order[i]=i;
        root=order.length==0?null:build(0,order.length);
    }
    long bytes() {return root==null?0:48L+order.length*4L+nodes*64L;}
    private Node build(int start,int end) {
        int minX=Integer.MAX_VALUE,minY=minX,minZ=minX,maxX=Integer.MIN_VALUE,maxY=maxX,maxZ=maxX;
        for(int i=start;i<end;i++) {
            var b=boxes.get(order[i]);
            minX=Math.min(minX,b.minX());minY=Math.min(minY,b.minY());minZ=Math.min(minZ,b.minZ());
            maxX=Math.max(maxX,b.maxX());maxY=Math.max(maxY,b.maxY());maxZ=Math.max(maxZ,b.maxZ());
        }
        Node left=null,right=null;
        if(end-start>LEAF_SIZE) {
            boolean x=(long)maxX-minX>=(long)maxZ-minZ;
            IntArrays.quickSort(order,start,end,(a,b)-> {
                var aa=boxes.get(a);var bb=boxes.get(b);
                long ac=x?(long)aa.minX()+aa.maxX():(long)aa.minZ()+aa.maxZ();
                long bc=x?(long)bb.minX()+bb.maxX():(long)bb.minZ()+bb.maxZ();
                int cmp=Long.compare(ac,bc);return cmp==0?Integer.compare(a,b):cmp;
            });
            int middle=(start+end)>>>1;left=build(start,middle);right=build(middle,end);
        }
        nodes++;return new Node(minX,minY,minZ,maxX,maxY,maxZ,start,end,left,right);
    }
    int[] intersect(int minX,int minY,int minZ,int maxX,int maxY,int maxZ) {
        if(root!=null && minX<=root.minX && minY<=root.minY && minZ<=root.minZ
                && maxX>=root.maxX && maxY>=root.maxY && maxZ>=root.maxZ) {
            int[] all=new int[order.length];for(int i=0;i<all.length;i++)all[i]=i;return all;
        }
        var result=new IntArrayList();visit(root,minX,minY,minZ,maxX,maxY,maxZ,result);
        int[] ids=result.toIntArray();IntArrays.quickSort(ids);return ids;
    }
    private void visit(Node n,int minX,int minY,int minZ,int maxX,int maxY,int maxZ,IntArrayList result) {
        if(n==null || n.minX>=maxX || n.maxX<=minX || n.minY>=maxY || n.maxY<=minY
                || n.minZ>=maxZ || n.maxZ<=minZ)return;
        if(n.left!=null) {visit(n.left,minX,minY,minZ,maxX,maxY,maxZ,result);visit(n.right,minX,minY,minZ,maxX,maxY,maxZ,result);return;}
        for(int i=n.start;i<n.end;i++) {
            var b=boxes.get(order[i]);
            if(b.minX()<maxX && b.maxX()>minX && b.minY()<maxY && b.maxY()>minY && b.minZ()<maxZ && b.maxZ()>minZ)
                result.add(order[i]);
        }
    }
}
