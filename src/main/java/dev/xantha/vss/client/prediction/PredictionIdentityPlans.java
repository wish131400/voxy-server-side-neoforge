package dev.xantha.vss.client.prediction;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.function.Supplier;

/** Feature records have recursive structural hashes; plans belong to object identities. */
final class PredictionIdentityPlans<K, V> {
    private final ReferenceQueue<K> retired = new ReferenceQueue<>();
    private final HashMap<Key<K>, V> entries = new HashMap<>();

    synchronized V get(K owner, Supplier<V> compile) {
        for (var key = retired.poll(); key != null; key = retired.poll()) entries.remove(key);
        var lookup = new Key<>(owner, null);
        V plan = entries.get(lookup);
        if (plan == null) {
            plan = compile.get();
            entries.put(new Key<>(owner, retired), plan);
        }
        return plan;
    }

    private static final class Key<K> extends WeakReference<K> {
        private final int hash;
        Key(K owner, ReferenceQueue<K> queue) { super(owner, queue); hash = System.identityHashCode(owner); }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            return other instanceof Key<?> key && get() != null && get() == key.get();
        }
    }
}
