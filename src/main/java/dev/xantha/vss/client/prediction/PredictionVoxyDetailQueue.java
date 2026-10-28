package dev.xantha.vss.client.prediction;

import java.util.*;
import dev.xantha.vss.client.prediction.PredictionVoxySource.Detail;

/** Bounded admitted work, durable waiting identities and view-scoped completed residency. Caller owns the lock. */
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
    // GPU retries are suppressed while samples are absent. Keep horizontal demand
    // here so a view change or completion can admit it without another GPU retry.
    private Map<Detail,Rank> deferredEntries = new HashMap<>();
    private final Set<Detail> completed = new HashSet<>(), dormant = new HashSet<>();
    private final Map<Detail,Long> lastRelevant=new HashMap<>();
    private final ArrayDeque<Detail> residencyScan=new ArrayDeque<>();
    private final Set<Detail> residencyTracked=new HashSet<>();
    private final java.util.function.LongSupplier clock;
    private final Set<Detail> added = new HashSet<>(), removed = new HashSet<>();
    private long membershipRevision;
    record Changes(List<Detail> added, List<Detail> removed) {
        boolean empty() { return added.isEmpty() && removed.isEmpty(); }
    }
    private void membership(Detail key, boolean present) {
        membershipRevision++;
        if (present) { if (!removed.remove(key)) added.add(key); }
        else if (!added.remove(key)) removed.add(key);
    }
    Changes drainChanges() {
        var result = new Changes(List.copyOf(added), List.copyOf(removed));
        added.clear(); removed.clear(); return result;
    }
    private TreeSet<Rank> order = new TreeSet<>(RANK_ORDER);
    private TreeSet<Rank> deferredOrder = new TreeSet<>(RANK_ORDER);
    private long evicted, deferred;
    PredictionVoxyDetailQueue(int capacity) { this(capacity,PredictionClientPreparation.WORKER); }
    PredictionVoxyDetailQueue(int capacity, java.util.concurrent.Executor executor) {
        this(capacity,executor,System::nanoTime);
    }
    PredictionVoxyDetailQueue(int capacity,java.util.concurrent.Executor executor,java.util.function.LongSupplier clock) {
        this.capacity=capacity;this.clock=clock;
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
                if (entries.containsKey(key) || deferredEntries.containsKey(key)) {
                    var rank=ready.priority().rank(key);ready.entries().put(key,rank);ready.order().add(rank);
                }
            }
            // Reuse the worker's ordered waiting set. Only the bounded admitted set
            // is separated here; a large backlog must not be sorted on the caller.
            entries.replaceAll((key,old) -> {
                var rank=ready.entries().remove(key);ready.order().remove(rank);return rank;
            });
            order.clear();order.addAll(entries.values());
            deferredEntries=ready.entries();deferredOrder=ready.order();
            activePriority=ready.priority();priorityChanges.clear();rebalance();
        }
        if (!preparation.pending() && priority!=requestedPriority) {
            requestedPriority=priority;priorityChanges.clear();
            var keys=new ArrayList<>(entries.keySet());keys.addAll(deferredEntries.keySet());
            preparation.request(new PriorityInput(priority,List.copyOf(keys)));
        }
    }
    private void changedPriority(Detail key) { if(preparation.pending())priorityChanges.add(key); }
    void priority(double x,double z,java.util.function.ToIntFunction<Detail> band) {
        preparation.clear();priorityChanges.clear();
        priority=new Priority(x,z,band);activePriority=priority;requestedPriority=priority;
        var keys=List.copyOf(entries.keySet());order.clear();entries.clear();
        for(var key:keys) {var rank=rank(key);entries.put(key,rank);order.add(rank);}
        var waiting=List.copyOf(deferredEntries.keySet());deferredOrder.clear();deferredEntries.clear();
        for(var key:waiting) {var rank=rank(key);deferredEntries.put(key,rank);deferredOrder.add(rank);}
        rebalance();
    }
    private Rank rank(Detail key) { return activePriority.rank(key); }
    private void promote(Rank rank) {
        deferredOrder.remove(rank);deferredEntries.remove(rank.key());
        entries.put(rank.key(),rank);order.add(rank);
        if(!completed.remove(rank.key()))membership(rank.key(),true);
    }
    private void rebalance() {
        while(entries.size()<capacity && !deferredOrder.isEmpty())promote(deferredOrder.first());
        while(!deferredOrder.isEmpty() && !order.isEmpty()
                && RANK_ORDER.compare(deferredOrder.first(),order.last())<0) {
            var best=deferredOrder.first();var worst=order.pollLast();entries.remove(worst.key());
            deferredEntries.put(worst.key(),worst);deferredOrder.add(worst);
            membership(worst.key(),false);evicted++;promote(best);
        }
    }
    boolean request(Detail key,boolean ready) {
        long before=membershipRevision;
        if(ready) {
            var previous=entries.remove(key);
            if(previous!=null) {order.remove(previous);completed.add(key);trackResident(key);changedPriority(key);}
            if(dormant.remove(key)) {completed.add(key);trackResident(key);membership(key,true);}
            var waiting=deferredEntries.remove(key);
            if(waiting!=null) {
                deferredOrder.remove(waiting);changedPriority(key);
                if(completed.add(key)) {trackResident(key);membership(key,true);}
            }
            rebalance();
            return before!=membershipRevision;
        }
        if(entries.containsKey(key) || deferredEntries.containsKey(key))return false;
        dormant.remove(key);
        var rank=rank(key);
        if(entries.size()==capacity && RANK_ORDER.compare(rank,order.last())>=0)deferred++;
        deferredEntries.put(key,rank);deferredOrder.add(rank);
        changedPriority(key);
        rebalance();
        if(deferredEntries.containsKey(key) && completed.remove(key))membership(key,false);
        return before!=membershipRevision;
    }
    /** A visible sampled region also needs a resident identity for its surface work. */
    boolean observe(Detail key,boolean ready) {
        long before=membershipRevision;
        request(key,ready);
        if(ready && completed.add(key)) {
            dormant.remove(key);trackResident(key);membership(key,true);
        }
        return before!=membershipRevision;
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
        for(var iterator=deferredEntries.entrySet().iterator();iterator.hasNext();) {
            var entry=iterator.next();if(!predicate.test(entry.getKey()))continue;
            iterator.remove();deferredOrder.remove(entry.getValue());changedPriority(entry.getKey());
        }
        dormant.removeIf(predicate);
        rebalance();
        return changed;
    }
    private void trackResident(Detail key) {
        lastRelevant.put(key,clock.getAsLong());
        if(residencyTracked.add(key))residencyScan.addLast(key);
    }
    /** Cold residency is disposable; its demand identity survives so a turn restores it without GPU retries. */
    void maintainResidency(java.util.function.Predicate<Detail> available) {
        long now=clock.getAsLong();int count=Math.min(512,residencyScan.size());
        while(count-->0) {
            var key=residencyScan.removeFirst();
            if(!completed.contains(key) && !dormant.contains(key)) {
                residencyTracked.remove(key);lastRelevant.remove(key);continue;
            }
            if(activePriority.band().applyAsInt(key)<=PredictionWorkView.BUFFER) {
                lastRelevant.put(key,now);
                if(dormant.contains(key))request(key,available.test(key));
            } else if(completed.contains(key) && now-lastRelevant.getOrDefault(key,now)>=PredictionCpuCachePolicy.COLD_AGE_NANOS) {
                completed.remove(key);dormant.add(key);membership(key,false);
            }
            residencyScan.addLast(key);
        }
    }
    boolean releaseResident(Detail key) {
        if(!completed.remove(key))return false;
        dormant.add(key);trackResident(key);membership(key,false);return true;
    }
    Set<Detail> keys() {var keys=new HashSet<>(completed);keys.addAll(entries.keySet());return Set.copyOf(keys);}
    void clear() {
        preparation.clear();activePriority=priority;requestedPriority=priority;priorityChanges.clear();
        entries.clear();order.clear();deferredEntries.clear();deferredOrder.clear();completed.clear();dormant.clear();
        lastRelevant.clear();residencyScan.clear();residencyTracked.clear();added.clear();removed.clear();
    }
    long evicted() {return evicted;}
    long deferred() {return deferred;}
    int pending() {return entries.size();}
    int waiting() {return deferredEntries.size();}
    long revision() {return membershipRevision;}
    int residents() {return completed.size();}
    int dormant() {return dormant.size();}
}
