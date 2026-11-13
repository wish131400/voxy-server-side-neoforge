package dev.xantha.vss.common.worldgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import dev.xantha.vss.common.VSSLogger;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;

/**
 * Registry sections kept between the worldgen profile builds of one server.
 *
 * A cached section is the JSON the section's own encoder produced, together with the custom registries that call looked
 * up. The lookups are recorded on a detached WorldgenRegistryDependencies: RegistryOps answers a registry from its own
 * memo after the first lookup, so the instance of a running build no longer reports a registry the generator of that
 * build asked for earlier, and a section recorded there would lose it for the next dimension.
 *
 * The sections live exactly as long as the profile holder's payloads: its clear() drops them, and so does a build for
 * another server or another registry access. Only a build bracketed by begin/end on the same thread uses the cache.
 * Any failure inside the cache switches it off for the rest of the run with one ERROR; the section is then encoded as
 * without the cache.
 */
public final class WorldgenSectionCache {
    /** One section encoder of WorldgenCodecSnapshot. */
    public interface SectionEncoder {
        <T> JsonObject encode(RegistryAccess access, RegistryOps<JsonElement> ops, ResourceKey<? extends Registry<T>> key, Codec<T> codec);
    }

    /** The density section encoder of WorldgenCodecSnapshot. */
    public interface DensityEncoder {
        JsonObject encode(RegistryAccess access, RegistryOps<JsonElement> ops, DensityFunctionReferences references);
    }

    private record Section(JsonObject entries, List<ResourceKey<? extends Registry<?>>> requested) { }

    private record SectionKey(ResourceKey<?> registry, Object codec) { }

    private static final class State {
        final WeakReference<Object> owner;
        final WeakReference<RegistryAccess> access;
        Set<ResourceKey<? extends Registry<?>>> catalog;
        final Map<SectionKey, Section> sections = new HashMap<>();
        Section densities;
        JsonObject definitions;
        final Map<ResourceKey<?>, Section> custom = new HashMap<>();
        final Map<Set<ResourceKey<? extends Registry<?>>>, JsonObject> tags = new HashMap<>();
        long nodes;
        long bytes;
        int declined;

        State(Object owner, RegistryAccess access) {
            this.owner = new WeakReference<>(owner);
            this.access = new WeakReference<>(access);
        }
    }

    /** Counts of the last build, for the per-build summary line. */
    public record Counts(int sectionHits, int sectionMisses, int customHits, int customMisses, int tagHits, int tagMisses,
                         long nodes, long bytes, int declined) { }

    private static State state;
    private static Thread builder;
    private static boolean bypass;
    private static volatile String refused;
    private static int sectionHits, sectionMisses, customHits, customMisses, tagHits, tagMisses;
    /** Test only: the name of a cache step that throws, or name#k for its k-th call after the name was set. */
    static volatile String fault = System.getProperty(WorldgenProfileCacheConfig.PREFIX + "fault");
    private static String faultCounted;
    private static int faultCalls;

    private WorldgenSectionCache() { }

    /** False after the first failure of a run; the profile holder then also keeps its four-payload bound. */
    public static boolean open() { return refused == null; }

    public static String refused() { return refused; }

    /** Only the first refusal of a run is logged; afterwards the cache stays off. */
    public static void refuse(String reason) {
        synchronized (WorldgenSectionCache.class) {
            if (refused != null) return;
            refused = String.valueOf(reason);
        }
        try {
            VSSLogger.error(reason + ". The worldgen profile holder keeps four dimensions and every build encodes every registry section", null);
        } catch (RuntimeException | LinkageError ignored) {
            // Logging is best effort; the refusal itself is already recorded.
        }
    }

    /** Opens a build on the calling thread for this owner (the server) and its registries; false: build without the cache. */
    public static synchronized boolean begin(Object owner, RegistryAccess access) {
        try {
            if (!open()) return false;
            State s = state;
            if (s == null || s.owner.get() != owner || s.access.get() != access) state = new State(owner, access);
            builder = Thread.currentThread();
            sectionHits = sectionMisses = customHits = customMisses = tagHits = tagMisses = 0;
            return true;
        } catch (RuntimeException | LinkageError failure) {
            refuse("Switched the worldgen profile cache off: a cache step failed (" + failure + ")");
            return false;
        }
    }

    public static synchronized void end() { builder = null; }

    /** The diagnostic rebuild of the verify switch runs with the cache bypassed. */
    public static synchronized void bypass(boolean value) { bypass = value; }

    public static synchronized Counts counts() {
        State s = state;
        return new Counts(sectionHits, sectionMisses, customHits, customMisses, tagHits, tagMisses,
                s == null ? 0 : s.nodes, s == null ? 0 : s.bytes, s == null ? 0 : s.declined);
    }

    /** The profile holder was cleared: server start, server stop or a datapack sync for all players. */
    public static synchronized void clear() {
        if (state != null) VSSLogger.info("Worldgen profile section cache dropped with the profile holder (" + state.nodes + " JSON values)");
        state = null;
    }

    // ---- the section call sites ----

    public static <T> JsonObject section(RegistryAccess access, WorldgenRegistryDependencies dependencies,
            ResourceKey<? extends Registry<T>> key, Codec<T> codec, SectionEncoder encoder) {
        RegistryOps<JsonElement> ops = dependencies.ops();
        JsonObject hit = cachedSection(access, dependencies, key, codec);
        if (hit != null) return hit;
        WorldgenRegistryDependencies detached = detached(access, dependencies);
        if (detached == null) return encoder.encode(access, ops, key, codec);
        JsonObject encoded = encoder.encode(access, detached.ops(), key, codec);
        JsonObject stored = storeSection(access, dependencies, key, codec, encoded, detached);
        return stored != null ? stored : encoder.encode(access, ops, key, codec);
    }

    public static JsonObject densities(RegistryAccess access, WorldgenRegistryDependencies dependencies,
            DensityFunctionReferences references, DensityEncoder encoder) {
        RegistryOps<JsonElement> ops = dependencies.ops();
        JsonObject hit = cachedDensities(access, dependencies, references);
        if (hit != null) return hit;
        WorldgenRegistryDependencies detached = detached(access, dependencies);
        if (detached == null) return encoder.encode(access, ops, references);
        var detachedReferences = new DensityFunctionReferences();
        JsonObject encoded = encoder.encode(access, detached.ops(), detachedReferences);
        JsonObject stored = storeDensities(access, dependencies, references, encoded, detached, detachedReferences);
        return stored != null ? stored : encoder.encode(access, ops, references);
    }

    /** One custom registry of WorldgenRegistryDependencies.encode(). */
    static JsonObject custom(WorldgenRegistryDependencies self, ResourceKey<?> registry,
            Function<WorldgenRegistryDependencies, JsonObject> encoder) {
        RegistryAccess access = self.access();
        JsonObject hit = cachedCustom(access, self, registry);
        if (hit != null) return hit;
        WorldgenRegistryDependencies detached = detached(access, self);
        if (detached == null) return encoder.apply(self);
        JsonObject encoded = encoder.apply(detached);
        JsonObject stored = storeCustom(access, self, registry, encoded, detached);
        return stored != null ? stored : encoder.apply(self);
    }

    /** The tag section of WorldgenRegistryDependencies.encodeTags(). */
    static JsonObject tags(WorldgenRegistryDependencies self, Supplier<JsonObject> encoder) {
        RegistryAccess access = self.access();
        JsonObject hit = cachedTags(access, self);
        if (hit != null) return hit;
        JsonObject encoded = encoder.get();
        storeTags(access, self, encoded);
        return encoded;
    }

    // ---- the cache ----

    private static State usable(RegistryAccess access, WorldgenRegistryDependencies dependencies) {
        State s = state;
        if (s == null || bypass || !WorldgenProfileCacheConfig.SECTIONS || builder != Thread.currentThread() || !open()) return null;
        if (access == null || s.access.get() != access || dependencies == null) return null;
        if (dependencies.access() != access) return null;
        Set<ResourceKey<? extends Registry<?>>> catalog = dependencies.catalogKeys();
        if (s.catalog == null) s.catalog = Set.copyOf(catalog);
        else if (!s.catalog.equals(catalog)) return null;
        return s;
    }

    /** Non-null when the caller should encode with the returned instance and hand the result to a store method. */
    static synchronized WorldgenRegistryDependencies detached(RegistryAccess access, WorldgenRegistryDependencies dependencies) {
        try {
            return usable(access, dependencies) == null ? null : dependencies.detached();
        } catch (RuntimeException | LinkageError failure) {
            refuse("Switched the worldgen profile cache off: a cache step failed (" + failure + ")");
            return null;
        }
    }

    private interface Step<T> { T run(); }

    /**
     * Every entry point of the section cache runs through here. Whatever goes wrong inside switches the cache off with
     * one ERROR and returns null, which each caller reads as "encode as without the cache".
     */
    private static <T> T guarded(String step, Step<T> body) {
        try {
            String wanted = fault;
            if (wanted != null && wanted.startsWith(step) && due(step, wanted)) throw new IllegalStateException("fault: " + wanted);
            return body.run();
        } catch (RuntimeException | LinkageError | StackOverflowError failure) {
            refuse("Switched the worldgen profile cache off: a cache step failed (" + step + ": " + failure + ")");
            return null;
        }
    }

    private static boolean due(String step, String wanted) {
        if (wanted.length() == step.length()) return true;
        if (wanted.charAt(step.length()) != '#') return false;
        if (!wanted.equals(faultCounted)) {
            faultCounted = wanted;
            faultCalls = 0;
        }
        return ++faultCalls == Integer.parseInt(wanted.substring(step.length() + 1));
    }

    static synchronized JsonObject cachedSection(RegistryAccess access, WorldgenRegistryDependencies dependencies,
            ResourceKey<?> registry, Object codec) {
        return guarded("cachedSection", () -> {
            State s = usable(access, dependencies);
            Section hit = s == null ? null : s.sections.get(new SectionKey(registry, codec));
            if (hit == null) return null;
            sectionHits++;
            return replay(hit, dependencies);
        });
    }

    /** null: the cache failed; the caller encodes again with the build's own instance. */
    static synchronized JsonObject storeSection(RegistryAccess access, WorldgenRegistryDependencies dependencies,
            ResourceKey<?> registry, Object codec, JsonObject encoded, WorldgenRegistryDependencies detached) {
        return guarded("storeSection", () -> {
            Section section = new Section(encoded, requestedBy(detached));
            State s = usable(access, dependencies);
            if (s != null && admit(s, encoded)) s.sections.put(new SectionKey(registry, codec), section);
            sectionMisses++;
            return replay(section, dependencies);
        });
    }

    static synchronized JsonObject cachedDensities(RegistryAccess access, WorldgenRegistryDependencies dependencies,
            DensityFunctionReferences references) {
        return guarded("cachedDensities", () -> {
            State s = usable(access, dependencies);
            if (s == null || s.densities == null) return null;
            sectionHits++;
            return replayDensities(s.densities, s.definitions, dependencies, references);
        });
    }

    static synchronized JsonObject storeDensities(RegistryAccess access, WorldgenRegistryDependencies dependencies,
            DensityFunctionReferences references, JsonObject encoded, WorldgenRegistryDependencies detached,
            DensityFunctionReferences detachedReferences) {
        return guarded("storeDensities", () -> {
            Section section = new Section(encoded, requestedBy(detached));
            JsonObject definitions = copy(detachedReferences.definitions());
            State s = usable(access, dependencies);
            if (s != null && admit(s, encoded, definitions)) {
                s.densities = section;
                s.definitions = definitions;
            }
            sectionMisses++;
            return replayDensities(section, definitions, dependencies, references);
        });
    }

    static synchronized JsonObject cachedCustom(RegistryAccess access, WorldgenRegistryDependencies dependencies, ResourceKey<?> registry) {
        return guarded("cachedCustom", () -> {
            State s = usable(access, dependencies);
            Section hit = s == null ? null : s.custom.get(registry);
            if (hit == null) return null;
            customHits++;
            return replay(hit, dependencies);
        });
    }

    static synchronized JsonObject storeCustom(RegistryAccess access, WorldgenRegistryDependencies dependencies,
            ResourceKey<?> registry, JsonObject encoded, WorldgenRegistryDependencies detached) {
        return guarded("storeCustom", () -> {
            Section section = new Section(encoded, requestedBy(detached));
            State s = usable(access, dependencies);
            if (s != null && admit(s, encoded)) s.custom.put(registry, section);
            customMisses++;
            return replay(section, dependencies);
        });
    }

    static synchronized JsonObject cachedTags(RegistryAccess access, WorldgenRegistryDependencies dependencies) {
        return guarded("cachedTags", () -> {
            State s = usable(access, dependencies);
            JsonObject hit = s == null ? null : s.tags.get(dependencies.requested());
            if (hit == null) return null;
            tagHits++;
            return copy(hit);
        });
    }

    static synchronized void storeTags(RegistryAccess access, WorldgenRegistryDependencies dependencies, JsonObject encoded) {
        boolean ours = encoded != null && builder == Thread.currentThread() && !bypass && open();
        guarded("storeTags", () -> {
            State s = usable(access, dependencies);
            if (s == null || encoded == null) return null;
            tagMisses++;
            if (admit(s, encoded)) s.tags.put(Set.copyOf(dependencies.requested()), copy(encoded));
            return null;
        });
        if (ours && WorldgenProfileCacheConfig.DIAGNOSE) diagnoseTags(encoded);
    }

    private static List<ResourceKey<? extends Registry<?>>> requestedBy(WorldgenRegistryDependencies detached) {
        return List.copyOf(detached.requested());
    }

    /** What the codec call would have done to the build's own instance, then a copy the caller may add to. */
    private static JsonObject replay(Section section, WorldgenRegistryDependencies dependencies) {
        dependencies.requested().addAll(section.requested());
        return copy(section.entries());
    }

    private static JsonObject replayDensities(Section section, JsonObject definitions, WorldgenRegistryDependencies dependencies,
            DensityFunctionReferences references) {
        JsonObject live = references.definitions();
        for (var entry : definitions.entrySet()) if (!live.has(entry.getKey())) live.add(entry.getKey(), entry.getValue());
        return replay(section, dependencies);
    }

    private static boolean admit(State s, JsonElement... trees) {
        long[] cost = new long[2];
        for (JsonElement tree : trees) estimate(tree, cost);
        if (s.bytes + cost[1] > WorldgenProfileCacheConfig.MAX_SECTION_BYTES
                || WorldgenProfileCacheConfig.MAX_NODES > 0L && s.nodes + cost[0] > WorldgenProfileCacheConfig.MAX_NODES) {
            s.declined++;
            return false;
        }
        s.nodes += cost[0];
        s.bytes += cost[1];
        return true;
    }

    /**
     * cost[0]: JSON values; cost[1]: an estimate of the heap the tree holds (64-bit JVM, compressed references): an object
     * with its map 64 B and 88 B per member plus the key, an array 56 B and 8 B per element, a string 56 B plus its
     * length, a number 40 B, a boolean 16 B.
     */
    public static void estimate(JsonElement root, long[] cost) {
        var stack = new ArrayDeque<JsonElement>();
        stack.push(root);
        while (!stack.isEmpty()) {
            JsonElement element = stack.pop();
            cost[0]++;
            if (element.isJsonObject()) {
                cost[1] += 64L;
                for (var member : element.getAsJsonObject().entrySet()) {
                    cost[1] += 88L + member.getKey().length();
                    stack.push(member.getValue());
                }
            } else if (element.isJsonArray()) {
                cost[1] += 56L;
                for (JsonElement child : element.getAsJsonArray()) {
                    cost[1] += 8L;
                    stack.push(child);
                }
            } else if (element.isJsonPrimitive()) {
                var primitive = element.getAsJsonPrimitive();
                cost[1] += primitive.isString() ? 56L + primitive.getAsString().length() : primitive.isNumber() ? 40L : 16L;
            }
        }
    }

    /** The members are shared; the object itself is the caller's, so nothing added to it reaches the cache. */
    private static JsonObject copy(JsonObject source) {
        var result = new JsonObject();
        for (var member : source.entrySet()) result.add(member.getKey(), member.getValue());
        return result;
    }

    /**
     * One line per encoded tag section: for every registry a short hash of its tags as emitted (members in live order)
     * and one with the members of each tag sorted. Two sessions whose first hashes differ and whose second hashes agree
     * have the same tags in another order.
     */
    private static void diagnoseTags(JsonObject encoded) {
        try {
            var text = new StringBuilder();
            for (String registry : encoded.keySet().stream().sorted().toList()) {
                JsonElement tags = encoded.get(registry);
                JsonElement sorted = tags;
                if (tags.isJsonObject()) {
                    var copy = new JsonObject();
                    for (var tag : tags.getAsJsonObject().entrySet()) {
                        if (!tag.getValue().isJsonArray()) { copy.add(tag.getKey(), tag.getValue()); continue; }
                        var members = new ArrayList<String>();
                        for (JsonElement member : tag.getValue().getAsJsonArray()) members.add(member.toString());
                        Collections.sort(members);
                        var array = new JsonArray();
                        members.forEach(array::add);
                        copy.add(tag.getKey(), array);
                    }
                    sorted = copy;
                }
                if (text.length() > 0) text.append(", ");
                text.append(registry).append('=').append(WorldgenJson.sha256(tags), 0, 8).append('/')
                        .append(WorldgenJson.sha256(sorted), 0, 8);
            }
            VSSLogger.info("Worldgen profile tag sections of " + encoded.size() + " registries (as emitted/members sorted): " + text);
        } catch (RuntimeException | LinkageError | StackOverflowError unavailable) {
            // Diagnostic only.
        }
    }
}
