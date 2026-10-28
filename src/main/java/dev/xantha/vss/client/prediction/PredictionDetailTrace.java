package dev.xantha.vss.client.prediction;

import java.util.Map;

/** Per-demand observations only. Never consulted by scheduling or quality decisions. */
final class PredictionDetailTrace {
    final long demandSince;
    private long dirtySince, dirtyRevision, changedAt;
    private boolean dirtyTimeExact;
    private String lastEvent="requested";
    PredictionDetailTrace(long now,boolean alreadyDirty,long revision) {
        demandSince=now;changedAt=now;dirtyRevision=revision;
        if(alreadyDirty)dirtySince=now;
    }
    synchronized void dirty(long now,long revision) {
        // A new content revision starts a new invalidation interval, even if the
        // preceding revision never finished. Demand age remains independent.
        if(dirtySince!=0 && dirtyRevision==revision)return;
        dirtySince=now;dirtyRevision=revision;dirtyTimeExact=true;
        lastEvent="invalidated";changedAt=now;
    }
    synchronized void event(String event,long now) {lastEvent=event;changedAt=now;}
    synchronized void published(long now,long revision) {
        dirtySince=0;dirtyRevision=revision;dirtyTimeExact=true;
        lastEvent="published";changedAt=now;
    }
    synchronized Map<String,Object> snapshot(long now,boolean dirty) {
        return Map.of("demandAgeSeconds",Math.max(0,now-demandSince)/1e9,
                "dirtyAgeSeconds",!dirty || dirtySince==0 ? 0d : Math.max(0,now-dirtySince)/1e9,
                "dirtyTimeExact",dirtyTimeExact,"contentRevision",dirtyRevision,
                "lastEvent",lastEvent,"lastEventAgeSeconds",Math.max(0,now-changedAt)/1e9);
    }
}
