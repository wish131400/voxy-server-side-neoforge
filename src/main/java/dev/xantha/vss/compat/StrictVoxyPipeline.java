package dev.xantha.vss.compat;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

/** Outstanding mesh work, scoped to one Voxy render service. No section or mesh data is retained. */
public final class StrictVoxyPipeline {
    private final Long2LongOpenHashMap pending = new Long2LongOpenHashMap();
    private long sequence;

    public synchronized void queued(long position) {
        pending.put(position, ++sequence);
        StrictLodVisibility.workChanged();
    }

    public synchronized Work started(long position) {
        return new Work(this, position, pending.get(position));
    }

    public synchronized void uploaded(Work work) {
        if (work.sequence() != 0 && pending.get(work.position()) == work.sequence()) {
            pending.remove(work.position());
            StrictLodVisibility.workChanged();
        }
    }

    public synchronized boolean idle(int cx, int sy, int cz) {
        for (int lod = 0; lod <= 4; lod++) {
            int shift = lod + 1;
            if (pending.containsKey(StrictVoxyNodeIndex.key(lod, cx >> shift, sy >> shift, cz >> shift))) return false;
        }
        return true;
    }

    public synchronized int size() { return pending.size(); }

    public record Work(StrictVoxyPipeline pipeline, long position, long sequence) {
        public void uploaded() { pipeline.uploaded(this); }
    }

    public interface Source { StrictVoxyPipeline vss$pipeline(); }
    public interface Mesh {
        Work vss$work();
        void vss$work(Work work);
    }
    public interface Batch { java.util.List<Work> vss$completedWork(); }
}
