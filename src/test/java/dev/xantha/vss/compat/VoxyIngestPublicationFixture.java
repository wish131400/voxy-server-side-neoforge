package dev.xantha.vss.compat;

import java.util.concurrent.ConcurrentLinkedDeque;

/** Executable direct-task queue with a shared publication and an unrelated raw overload. */
public class VoxyIngestPublicationFixture {
    public final ConcurrentLinkedDeque<IngestSection> ingestQueue = new ConcurrentLinkedDeque<>();
    public int processed;

    public static final class IngestSection {
        public final Object section;
        public IngestSection(Object section) { this.section = section; }
    }

    public boolean rawIngest0(IngestSection task) { return submit(task); }
    public boolean rawIngest0(Object unused, IngestSection task) { return rawIngest0(task); }
    public synchronized boolean submit(IngestSection task) { return ingestQueue.add(task); }
    public void processJob() {
        IngestSection task = ingestQueue.poll();
        if (task == null) return;
        Object section = task.section;
        if (section instanceof RuntimeException failure) throw failure;
        processed++;
    }
}
