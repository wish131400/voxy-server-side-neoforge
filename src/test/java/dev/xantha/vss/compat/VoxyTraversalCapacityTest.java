package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import org.junit.jupiter.api.Test;

class VoxyTraversalCapacityTest {
    public static class Buffer {
        final int[] values; boolean freed;
        public Buffer(long bytes) { values=new int[Math.toIntExact(bytes/4)]; }
        public long size(){return values.length*4L;}
    }
    public static class Nodes { int max; public int getCurrentMaxNodeId(){return max;} }
    public static class Traverser {
        int[] idx2topNodeMapping=new int[200000];
        Buffer topNodeIds=new Buffer(800000),scratchQueueA=new Buffer(800000),scratchQueueB=new Buffer(800000);
        Nodes nodeManager=new Nodes();
    }
    static class MemoryBuffers implements VoxyTraversalCapacity.Buffers {
        int allocations, copies; int failOnAllocation=-1;
        public Object allocate(Class<?> type,long bytes) {
            if(++allocations==failOnAllocation)throw new IllegalStateException("allocation probe");
            return new Buffer(bytes);
        }
        public void copy(Object from,Object to,long bytes) {
            copies++;System.arraycopy(((Buffer)from).values,0,((Buffer)to).values,0,Math.toIntExact(bytes/4));
        }
        public void free(Object buffer){assertFalse(((Buffer)buffer).freed);((Buffer)buffer).freed=true;}
    }
    private static VoxyCapacityPlan small(){return VoxyCapacityPlan.create(16,0,8192,-64,2031);}

    @Test void repeatedSteadyFramesDoNotAllocateBuffersOrScanResidentNodes() {
        var nativeTree=new Traverser();var buffers=new MemoryBuffers();
        var capacity=new VoxyTraversalCapacity(nativeTree,small(),buffers);
        nativeTree.nodeManager.max=180000;
        for(int i=0;i<1000;i++)capacity.beforeTraversal();
        assertEquals(0,buffers.allocations);assertEquals(0,buffers.copies);
    }
    @Test void expansionKeepsRootIdsAndNeverCopiesScratchOrTouchesMeshes() {
        var tree=new Traverser();var buffers=new MemoryBuffers();
        var capacity=new VoxyTraversalCapacity(tree,small(),buffers);
        var ids=new Int2IntOpenHashMap();
        for(int i=0;i<12;i++) {capacity.beforeAdd(i,i,ids);ids.put(i,i);tree.idx2topNodeMapping[i]=i+73;tree.topNodeIds.values[i]=i+73;capacity.changed(i+1,ids.size(),true);}
        Buffer roots=tree.topNodeIds,a=tree.scratchQueueA,b=tree.scratchQueueB;
        capacity.prepare(VoxyCapacityPlan.create(64,128,65536,-64,2031));
        assertTrue(roots.freed&&a.freed&&b.freed);assertEquals(1,buffers.copies);
        for(int i=0;i<12;i++){assertEquals(i+73,tree.idx2topNodeMapping[i]);assertEquals(i+73,tree.topNodeIds.values[i]);}
        assertTrue(tree.idx2topNodeMapping.length>=261285);
        assertEquals(12,capacity.snapshot().get("activeRoots"));
        int allocations=buffers.allocations;
        capacity.prepare(small());assertEquals(allocations,buffers.allocations);
    }
    @Test void uploadedNodeHighWaterGrowsIntermediateQueuesIndependentlyOfRootCount() {
        var tree=new Traverser();var buffers=new MemoryBuffers();
        var capacity=new VoxyTraversalCapacity(tree,small(),buffers);
        Buffer root=tree.topNodeIds;
        tree.nodeManager.max=450000;capacity.beforeTraversal();
        assertSame(root,tree.topNodeIds);assertEquals(200000,tree.idx2topNodeMapping.length);
        assertTrue(tree.scratchQueueA.values.length>=450001);
        assertEquals(2,buffers.allocations);assertEquals(0,buffers.copies);
        tree.nodeManager.max=2;capacity.beforeTraversal();assertEquals(2,buffers.allocations);
    }
    @Test void repeatedAddRemoveAndIdReuseKeepAccountingBalanced() {
        var tree=new Traverser();var capacity=new VoxyTraversalCapacity(tree,small(),new MemoryBuffers());
        var ids=new Int2IntOpenHashMap();
        for(int cycle=0;cycle<10;cycle++) {
            for(int i=0;i<1000;i++){capacity.beforeAdd(i,ids.size(),ids);ids.put(i,i);capacity.changed(ids.size(),ids.size(),true);}
            for(int i=999;i>=0;i--){capacity.beforeRemove(i,ids.size(),ids);ids.remove(i);capacity.changed(ids.size(),ids.size(),false);}
        }
        capacity.beforeTraversal();assertEquals(0,capacity.snapshot().get("activeRoots"));
        assertEquals(10000L,capacity.snapshot().get("addedRoots"));assertEquals(10000L,capacity.snapshot().get("removedRoots"));
    }
    @Test void lifecycleMismatchIsDetectedBeforeNativeCountCanBeCorrupted() {
        var capacity=new VoxyTraversalCapacity(new Traverser(),small(),new MemoryBuffers());var ids=new Int2IntOpenHashMap();ids.put(7,0);
        assertTrue(assertThrows(IllegalStateException.class,()->capacity.beforeAdd(7,1,ids)).getMessage().contains("duplicate root add"));
        assertThrows(IllegalStateException.class,()->capacity.beforeRemove(8,1,ids));
        assertThrows(IllegalStateException.class,()->capacity.beforeAdd(8,5,ids));
        assertEquals(1,ids.size());
    }
    @Test void failedSecondQueueAllocationPreservesBothOriginalHandles() {
        var tree=new Traverser();var buffers=new MemoryBuffers();buffers.failOnAllocation=2;
        var capacity=new VoxyTraversalCapacity(tree,small(),buffers);Buffer a=tree.scratchQueueA,b=tree.scratchQueueB;
        tree.nodeManager.max=400000;
        assertThrows(IllegalStateException.class,capacity::beforeTraversal);
        assertSame(a,tree.scratchQueueA);assertSame(b,tree.scratchQueueB);assertFalse(a.freed||b.freed);
    }
    @Test void aFreshRendererOwnsFreshCountersAndResources() {
        var old=new VoxyTraversalCapacity(new Traverser(),small(),new MemoryBuffers());
        old.changed(3,3,true);old.beforeTraversal();
        var next=new VoxyTraversalCapacity(new Traverser(),small(),new MemoryBuffers());
        assertEquals(0,next.snapshot().get("activeRoots"));assertEquals(0L,next.snapshot().get("addedRoots"));
    }
}
