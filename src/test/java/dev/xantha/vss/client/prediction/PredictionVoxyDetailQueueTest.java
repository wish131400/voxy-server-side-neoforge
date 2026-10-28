package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Set;
import org.junit.jupiter.api.Test;
import dev.xantha.vss.client.prediction.PredictionVoxySource.Detail;

class PredictionVoxyDetailQueueTest {
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

}
