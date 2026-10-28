package dev.xantha.vss.client.prediction;

import java.util.*;
import dev.xantha.vss.client.prediction.PredictionVoxySource.Detail;

/** Pending demand is bounded independently from completed residents retained inside the horizon. Caller owns the lock. */
final class PredictionVoxyDetailQueue {
    private record Rank(Detail key, int band, double distance) { }
    private static final Comparator<Rank> RANK_ORDER = Comparator.comparingInt(Rank::band)
            .thenComparingDouble(Rank::distance).thenComparingInt(r -> r.key().lod())
            .thenComparingInt(r -> r.key().x()).thenComparingInt(r -> r.key().z());
    private record Priority(double x, double z, java.util.function.ToIntFunction<Detail> band) {
        Rank rank(Detail key) {
            double span=64L<<key.lod();
            double dx=Math.max(0,Math.max(key.x()*span-x,x-(key.x()+1D)*span));
            double dz=Math.max(0,Math.max(key.z()*span-z,z-(key.z()+1D)*span));
            return new Rank(key,band.applyAsInt(key),dx*dx+dz*dz);
        }
    }
    private record PriorityInput(Priority priority, List<Detail> keys) { }
    private record Prepared(Priority priority, Map<Detail,Rank> entries, TreeSet<Rank> order) { }
    private final PredictionClientPreparation<PriorityInput,Prepared> preparation;
    private final Set<Detail> priorityChanges = new HashSet<>();
    private Priority priority = new Priority(0,0,key -> PredictionWorkView.VISIBLE);
    private Priority activePriority = priority;
    private Priority requestedPriority = priority;
    private final int capacity;
    private Map<Detail,Rank> entries = new HashMap<>();
    private final Set<Detail> completed = new HashSet<>();
    private final Set<Detail> added = new HashSet<>(), removed = new HashSet<>();
    record Changes(List<Detail> added, List<Detail> removed) {
        boolean empty() { return added.isEmpty() && removed.isEmpty(); }
    }
    private void membership(Detail key, boolean present) {
        if (present) { if (!removed.remove(key)) added.add(key); }
        else if (!added.remove(key)) removed.add(key);
    }
    Changes drainChanges() {
        var result = new Changes(List.copyOf(added), List.copyOf(removed));
        added.clear(); removed.clear(); return result;
    }
    private TreeSet<Rank> order = new TreeSet<>(RANK_ORDER);
    private long evicted, deferred;
    PredictionVoxyDetailQueue(int capacity) { this(capacity,PredictionClientPreparation.WORKER); }
    PredictionVoxyDetailQueue(int capacity, java.util.concurrent.Executor executor) {
        this.capacity=capacity;
        preparation = new PredictionClientPreparation<>(executor, input -> {
            var ranks = new HashMap<Detail,Rank>(); var sorted = new TreeSet<>(RANK_ORDER);
            for (var key : input.keys()) { var rank=input.priority().rank(key); ranks.put(key,rank); sorted.add(rank); }
            return new Prepared(input.priority(),ranks,sorted);
        });
    }
    /** Pure priority hints may lag one preparation; terrain readiness never depends on these ranks. */
    void priorityAsync(double x,double z,java.util.function.ToIntFunction<Detail> band) {
        priority = new Priority(x,z,band); pollPriority();
    }
    void pollPriority() {
        boolean wasPending=preparation.pending();
        Prepared ready=preparation.poll();
        if (wasPending && !preparation.pending() && ready==null && priority!=activePriority) requestedPriority=null;
        if (ready!=null) {
            // Replay only keys changed during preparation, preserving arrivals, completion and eviction.
            for (var key : priorityChanges) {
                var old=ready.entries().remove(key); if(old!=null)ready.order().remove(old);
                if (entries.containsKey(key)) {
                    var rank=ready.priority().rank(key);ready.entries().put(key,rank);ready.order().add(rank);
                }
            }
            entries=ready.entries();order=ready.order();activePriority=ready.priority();priorityChanges.clear();
        }
        if (!preparation.pending() && priority!=requestedPriority) {
            requestedPriority=priority;priorityChanges.clear();
            preparation.request(new PriorityInput(priority,List.copyOf(entries.keySet())));
        }
    }
    private void changedPriority(Detail key) { if(preparation.pending())priorityChanges.add(key); }
    void priority(double x,double z,java.util.function.ToIntFunction<Detail> band) {
        preparation.clear();priorityChanges.clear();
        priority=new Priority(x,z,band);activePriority=priority;requestedPriority=priority;
        var keys=List.copyOf(entries.keySet());order.clear();entries.clear();
        for(var key:keys) {var rank=rank(key);entries.put(key,rank);order.add(rank);}
    }
    private Rank rank(Detail key) { return activePriority.rank(key); }
    boolean request(Detail key,boolean ready) {
        if(ready) {
            var previous=entries.remove(key);
            if(previous!=null) {order.remove(previous);completed.add(key);changedPriority(key);}
            return false; // Desired membership remains unchanged when terrain completes.
        }
        if(entries.containsKey(key))return false;
        var rank=rank(key);
        boolean removed=false;
        if(entries.size()==capacity) {
            var worst=order.last();
            if(order.comparator().compare(rank,worst)>=0) {deferred++;return false;}
            order.remove(worst);entries.remove(worst.key());completed.remove(worst.key());
            changedPriority(worst.key());
            membership(worst.key(),false);evicted++;removed=true;
        }
        entries.put(key,rank);order.add(rank);
        changedPriority(key);
        boolean resident=completed.remove(key);if(!resident)membership(key,true);return removed || !resident;
    }
    boolean removeIf(java.util.function.Predicate<Detail> predicate) {
        boolean changed=false;
        for(var iterator=entries.entrySet().iterator();iterator.hasNext();) {
            var entry=iterator.next();if(!predicate.test(entry.getKey()))continue;
            iterator.remove();order.remove(entry.getValue());membership(entry.getKey(),false);changed=true;
            changedPriority(entry.getKey());
        }
        for(var iterator=completed.iterator();iterator.hasNext();) {
            var key=iterator.next();if(!predicate.test(key))continue;
            iterator.remove();membership(key,false);changed=true;
        }
        return changed;
    }
    boolean releaseResident(Detail key) {if(!completed.remove(key))return false;membership(key,false);return true;}
    Set<Detail> keys() {var keys=new HashSet<>(completed);keys.addAll(entries.keySet());return Set.copyOf(keys);}
    void clear() {
        preparation.clear();activePriority=priority;requestedPriority=priority;priorityChanges.clear();
        entries.clear();order.clear();completed.clear();added.clear();removed.clear();
    }
    long evicted() {return evicted;}
    long deferred() {return deferred;}
    int pending() {return entries.size();}
    int residents() {return completed.size();}
}
