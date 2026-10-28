package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Set;
import org.junit.jupiter.api.Test;
import dev.xantha.vss.client.prediction.PredictionVoxySource.Detail;

class PredictionVoxyDetailQueueTest {
    @Test void completedWorkAdmitsDeferredDemandWithoutAnotherNativeRequest() {
        var q=new PredictionVoxyDetailQueue(1);var near=new Detail(0,0,0);var far=new Detail(100,0,0);
        q.request(near,false);q.drainChanges();
        assertFalse(q.request(far,false));assertEquals(1,q.waiting());
        assertTrue(q.request(near,true),"Completion publishes the newly admitted target");
        assertEquals(Set.of(near,far),q.keys());assertEquals(1,q.pending());assertEquals(0,q.waiting());
        assertEquals(java.util.List.of(far),q.drainChanges().added());
    }
    @Test void zoomPromotesDeferredAndPreviouslyEvictedTargetsWithoutGpuRetries() {
        var jobs=new java.util.ArrayDeque<Runnable>();var q=new PredictionVoxyDetailQueue(2,jobs::add);
        var a=new Detail(0,0,0);var b=new Detail(1,0,0);var target=new Detail(-100,-100,0);
        q.request(a,false);q.request(b,false);assertFalse(q.request(target,false));q.drainChanges();
        q.priorityAsync(0,0,d -> d.equals(target)?0:3);jobs.removeFirst().run();q.pollPriority();
        assertEquals(Set.of(a,target),q.keys());assertEquals(2,q.pending());assertEquals(1,q.waiting());
        var change=q.drainChanges();assertEquals(java.util.List.of(target),change.added());assertEquals(java.util.List.of(b),change.removed());
        q.priorityAsync(0,0,d -> d.equals(b)?0:3);jobs.removeFirst().run();q.pollPriority();
        assertEquals(Set.of(a,b),q.keys(),"Evicted identities remain available on the next view");
        q.request(a,true);assertEquals(Set.of(a,b,target),q.keys());assertEquals(0,q.waiting());
    }
    @Test void asynchronousReorderingReplaysCompletionArrivalAndHorizonRetirement() {
        var jobs=new java.util.ArrayDeque<Runnable>();var q=new PredictionVoxyDetailQueue(1,jobs::add);
        var a=new Detail(0,0,0);var b=new Detail(10,0,0);var c=new Detail(20,0,0);var d=new Detail(30,0,0);
        q.request(a,false);q.request(b,false);q.request(c,false);
        q.priorityAsync(0,0,k -> k.equals(c)?0:3);
        q.request(a,true);q.removeIf(c::equals);q.request(d,false);
        jobs.removeFirst().run();q.pollPriority();
        assertEquals(Set.of(a,b),q.keys());assertEquals(1,q.waiting());
        q.request(b,true);assertEquals(Set.of(a,b,d),q.keys());assertFalse(q.keys().contains(c));
        q.clear();q.priority(0,0,k -> 0);assertTrue(q.keys().isEmpty());assertEquals(0,q.waiting());
    }
    @Test void membershipDeltasCancelOppositeChangesAndIgnoreCompletionRenewal() {
        var q=new PredictionVoxyDetailQueue(2);var a=new Detail(0,0,0);var b=new Detail(1,0,0);
        q.request(a,false);q.removeIf(a::equals);assertTrue(q.drainChanges().empty());
        q.request(a,false);assertEquals(java.util.List.of(a),q.drainChanges().added());
        q.request(a,true);q.request(a,false);assertTrue(q.drainChanges().empty());
        q.removeIf(a::equals);q.request(a,false);assertTrue(q.drainChanges().empty());
        q.request(b,false);q.keys();assertEquals(java.util.List.of(b),q.drainChanges().added());
        q.request(b,true);q.releaseResident(b);assertEquals(java.util.List.of(b),q.drainChanges().removed());
        q.clear();assertTrue(q.drainChanges().empty());
    }
    @Test void aFullQueueKeepsNearDemandAndAdmitsNewVisibleDemand() {
        var q=new PredictionVoxyDetailQueue(2);
        var near=new Detail(0,0,0);var far=new Detail(100,0,0);var visible=new Detail(200,0,0);
        q.priority(0,0,d -> d.equals(visible)?PredictionWorkView.VISIBLE:PredictionWorkView.BACKGROUND);
        assertTrue(q.request(near,false));assertTrue(q.request(far,false));
        assertFalse(q.request(new Detail(300,0,0),false));
        assertTrue(q.request(visible,false));assertEquals(Set.of(near,visible),q.keys());
        assertEquals(1,q.evicted());assertEquals(1,q.deferred());
    }
    @Test void completedResidentsReleasePendingCapacityAndRemainDesiredUntilTheyLeave() {
        var q=new PredictionVoxyDetailQueue(2);
        var a=new Detail(0,0,0);var b=new Detail(1,0,0);var outer=new Detail(100,0,0);
        assertTrue(q.request(a,false));assertTrue(q.request(b,false));
        assertFalse(q.request(a,true));assertFalse(q.request(b,true));
        assertEquals(0,q.pending());assertEquals(2,q.residents());
        assertTrue(q.request(outer,false),"Completed near requests must not lock out an outer request");
        assertEquals(Set.of(a,b,outer),q.keys());assertEquals(0,q.deferred());
        assertFalse(q.request(a,false),"A renewed resident keeps the same desired membership");
        assertEquals(2,q.pending());assertEquals(1,q.residents());
        q.priority(6400,0,d -> PredictionWorkView.VISIBLE);
        assertTrue(q.request(new Detail(99,0,0),false));
        assertTrue(q.keys().contains(b),"Queue pressure cannot cancel completed terrain or its plants");
        assertTrue(q.removeIf(d -> d.x()<99));assertEquals(0,q.residents());
        q.clear();assertTrue(q.keys().isEmpty());assertEquals(0,q.pending());
    }
    @Test void thousandsOfCompletedNearRequestsDoNotDeferTheNextUnmetRegion() {
        var q=new PredictionVoxyDetailQueue(4096);
        for(int x=0;x<4096;x++)assertTrue(q.request(new Detail(x,0,0),false));
        for(int x=0;x<4096;x++)assertFalse(q.request(new Detail(x,0,0),true));
        assertTrue(q.request(new Detail(5000,0,0),false));
        assertEquals(1,q.pending());assertEquals(4096,q.residents());assertEquals(0,q.deferred());
    }
    @Test void renewingAResidentPublishesAnyPendingEvictionToTheManager() {
        var q=new PredictionVoxyDetailQueue(1);var resident=new Detail(0,0,0);var far=new Detail(10,0,0);
        assertTrue(q.request(resident,false));assertFalse(q.request(resident,true));
        assertTrue(q.request(far,false));assertEquals(Set.of(resident,far),q.keys());
        assertTrue(q.request(resident,false),"Evicting a pending request changes desired membership even when the new request was a resident");
        assertEquals(Set.of(resident),q.keys());assertEquals(1,q.pending());assertEquals(0,q.residents());
    }

    @Test void completedColdDemandReleasesItsPinButSurvivesForTurnAndWarmReuse() {
        var clock=new java.util.concurrent.atomic.AtomicLong();
        var q=new PredictionVoxyDetailQueue(1,Runnable::run,clock::get);
        var cold=new Detail(10,0,0);var pending=new Detail(20,0,0);
        q.request(cold,false);q.request(cold,true);q.request(pending,false);q.drainChanges();
        q.priority(0,0,k->PredictionWorkView.BACKGROUND);
        clock.set(PredictionCpuCachePolicy.COLD_AGE_NANOS-1);q.maintainResidency(k->true);
        assertTrue(q.keys().contains(cold));
        clock.incrementAndGet();q.maintainResidency(k->true);
        assertFalse(q.keys().contains(cold));assertEquals(1,q.dormant());assertEquals(1,q.pending());
        assertEquals(java.util.List.of(cold),q.drainChanges().removed());
        q.priority(0,0,k->k.equals(cold)?PredictionWorkView.VISIBLE:PredictionWorkView.BACKGROUND);
        q.maintainResidency(k->true);
        assertTrue(q.keys().contains(cold));assertEquals(1,q.residents());assertEquals(1,q.pending());
        assertEquals(java.util.List.of(cold),q.drainChanges().added());
        q.priority(0,0,k->PredictionWorkView.BACKGROUND);clock.addAndGet(PredictionCpuCachePolicy.COLD_AGE_NANOS);
        q.maintainResidency(k->false);q.drainChanges();
        q.priority(0,0,k->k.equals(cold)?PredictionWorkView.VISIBLE:PredictionWorkView.BACKGROUND);
        q.maintainResidency(k->false);
        assertTrue(q.keys().contains(cold));assertEquals(0,q.residents());assertEquals(1,q.waiting());
        q.request(cold,true);assertTrue(q.keys().contains(pending));assertEquals(0,q.waiting());
        q.removeIf(k->true);clock.addAndGet(PredictionCpuCachePolicy.COLD_AGE_NANOS);
        q.maintainResidency(k->false);assertTrue(q.keys().isEmpty());assertEquals(0,q.dormant());
    }

    @Test void displacedCompletionAndResidentRenewalHaveOneDemandLifecycle() {
        var clock=new java.util.concurrent.atomic.AtomicLong();
        var q=new PredictionVoxyDetailQueue(1,Runnable::run,clock::get);
        var a=new Detail(0,0,0);var b=new Detail(10,0,0);
        q.request(a,false);q.request(a,true);q.request(b,false);
        q.priority(0,0,k->k.equals(b)?0:3);q.drainChanges();
        assertTrue(q.request(a,false),"Unready resident waiting behind active work releases its old completion pin");
        assertFalse(q.keys().contains(a));assertEquals(0,q.residents());assertEquals(1,q.waiting());
        q.maintainResidency(k->false);assertEquals(0,q.dormant());
        assertTrue(q.request(a,true),"A displaced task can still complete through another valid plan demand");
        assertTrue(q.keys().contains(a));assertEquals(1,q.residents());assertEquals(0,q.waiting());
        q.priority(0,0,k->PredictionWorkView.BACKGROUND);clock.addAndGet(PredictionCpuCachePolicy.COLD_AGE_NANOS);
        q.maintainResidency(k->false);assertEquals(0,q.residents());assertEquals(1,q.dormant());
        q.priority(0,0,k->k.equals(a)?0:3);q.maintainResidency(k->false);
        assertTrue(q.keys().contains(a));assertEquals(0,q.dormant());assertEquals(1,q.waiting());
        q.request(a,true);assertTrue(q.keys().contains(b));assertEquals(1,q.residents());
    }

}
