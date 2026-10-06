package dev.xantha.vss.mixin.lostcities;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.stream.Stream;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

class LostCityTimedCacheConcurrencyTest {
    private static final String CACHE = "mcjty.lostcities.varia.TimedCache";

    @Test void optionalMixinIsRegisteredWithoutChangingOtherConfigurations() throws Exception {
        try (var reader = Files.newBufferedReader(Path.of("src/main/resources/vss.lostcities.mixins.json"))) {
            var json = JsonParser.parseReader(reader).getAsJsonObject();
            assertFalse(json.get("required").getAsBoolean());
            assertEquals(LostCitiesMixinPlugin.class.getName(), json.get("plugin").getAsString());
            assertEquals("TimedCacheConcurrencyMixin", json.getAsJsonArray("mixins").get(0).getAsString());
            try (var input = LostCityTimedCacheConcurrencyTest.class.getResourceAsStream("/vss.lostcities.mixins.json")) {
                assertNotNull(input, "the optional configuration must survive the build resource filters");
                assertEquals(json, JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)));
            }
        }
        assertTrue(Files.readString(Path.of("build.gradle")).contains("vss.lostcities.mixins.json"));
    }

    @TestFactory Stream<DynamicTest> releasedCachesReproduceAndPreventConcurrentCleanup() throws Exception {
        String directory = System.getProperty("vss.lostCitiesMatrix");
        Assumptions.assumeTrue(directory != null, "Supply the Lost Cities release matrix");
        List<Path> releases;
        try (var files = Files.list(Path.of(directory))) {
            releases = files.filter(p -> p.getFileName().toString().matches("lostcities-.*\\.jar")).sorted().toList();
        }
        assertFalse(releases.isEmpty());
        return releases.stream().map(path -> DynamicTest.dynamicTest(path.getFileName().toString(), () -> {
            try (var zip = new ZipFile(path.toFile())) {
                var entry = zip.getEntry(CACHE.replace('.', '/') + ".class");
                if (entry == null) {
                    assertFalse(LostCitiesMixinPlugin.protectLegacyCache(new ClassNode()));
                    return;
                }
                byte[] source;
                try (var in = zip.getInputStream(entry)) { source = in.readAllBytes(); }
                ClassNode node = node(source);
                boolean legacy = node.fields.stream().anyMatch(f -> f.name.equals("cache") && f.desc.equals("Ljava/util/Map;"));
                assertEquals(legacy, LostCitiesMixinPlugin.legacyCache(node));
                if (!legacy) {
                    byte[] before = write(node);
                    assertFalse(LostCitiesMixinPlugin.protectLegacyCache(node));
                    assertArrayEquals(before, write(node), "new ConcurrentMap/pinned caches must not be rewritten");
                    return;
                }
                assertTrue(LostCitiesMixinPlugin.protectLegacyCache(node));
                byte[] protectedBytes = write(node);
                assertTrue(LostCitiesMixinPlugin.protectLegacyCache(node));
                assertArrayEquals(protectedBytes, write(node), "protection must be idempotent");
                Class<?> original = load(zip, source);
                Class<?> protectedCache = load(zip, protectedBytes);
                race(original, false);
                race(protectedCache, true);
                semantics(protectedCache);
                parallelOperations(protectedCache);
                System.out.println("TIMED_CACHE verified " + path.getFileName()
                        + ": original cleanup CME, protected get/put/clear, TTL and factory outside lock");
            }
        }));
    }

    private static ClassNode node(byte[] bytes) {
        var node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static byte[] write(ClassNode node) {
        // Keep the explicit frames: loading must also work without a frame recomputation.
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static Class<?> load(ZipFile zip, byte[] cacheBytes) throws Exception {
        var classes = new HashMap<String, byte[]>();
        classes.put(CACHE, cacheBytes);
        for (var entries = zip.entries(); entries.hasMoreElements();) {
            var entry = entries.nextElement();
            if (entry.getName().startsWith(CACHE.replace('.', '/') + "$") && entry.getName().endsWith(".class")) {
                try (var input = zip.getInputStream(entry)) {
                    classes.put(entry.getName().replace('/', '.').replace(".class", ""), input.readAllBytes());
                }
            }
        }
        return new ClassLoader(LostCityTimedCacheConcurrencyTest.class.getClassLoader()) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name);
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        }.loadClass(CACHE);
    }

    private static Object newCache(Class<?> type, IntSupplier ttl) throws Exception {
        return type.getConstructor(IntSupplier.class).newInstance(ttl);
    }

    private static Object invoke(Object cache, String method, Object... arguments) throws Exception {
        Class<?>[] parameters = switch (method) {
            case "clear" -> new Class<?>[0];
            case "get" -> new Class<?>[]{Object.class};
            case "put" -> new Class<?>[]{Object.class, Object.class};
            default -> new Class<?>[]{Object.class, Function.class};
        };
        try { return cache.getClass().getMethod(method, parameters).invoke(cache, arguments); }
        catch (InvocationTargetException wrapped) {
            if (wrapped.getCause() instanceof Exception cause) throw cause;
            if (wrapped.getCause() instanceof Error cause) throw cause;
            throw wrapped;
        }
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static final class PausedCleanupMap extends HashMap<Object, Object> {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        PausedCleanupMap(Map<?, ?> values) { super(values); }
        @Override public Set<Map.Entry<Object, Object>> entrySet() {
            Set<Map.Entry<Object, Object>> delegate = super.entrySet();
            return new AbstractSet<>() {
                @Override public int size() { return delegate.size(); }
                @Override public Iterator<Map.Entry<Object, Object>> iterator() {
                    Iterator<Map.Entry<Object, Object>> iterator = delegate.iterator();
                    return new Iterator<>() {
                        @Override public boolean hasNext() { return iterator.hasNext(); }
                        @Override public Map.Entry<Object, Object> next() {
                            if (entered.getCount() > 0) {
                                entered.countDown();
                                try { assertTrue(release.await(5, TimeUnit.SECONDS), "cleanup gate timeout"); }
                                catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                    throw new AssertionError(interrupted);
                                }
                            }
                            return iterator.next();
                        }
                        @Override public void remove() { iterator.remove(); }
                    };
                }
            };
        }
    }

    private static void race(Class<?> type, boolean protectedCache) throws Exception {
        Object cache = newCache(type, () -> 3600);
        for (int i = 0; i < 128; i++) invoke(cache, "put", i, "value");
        Field mapField = field(type, "cache"), cleanupField = field(type, "nextCleanupAt");
        var map = new PausedCleanupMap((Map<?, ?>) mapField.get(cache));
        mapField.set(cache, map);
        cleanupField.setLong(cache, 0);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> cleanup = pool.submit(() -> invoke(cache, "get", -1));
            assertTrue(map.entered.await(5, TimeUnit.SECONDS));
            cleanupField.setLong(cache, Long.MAX_VALUE);
            var attempting = new CountDownLatch(1);
            var writer = new AtomicReference<Thread>();
            Future<?> put = pool.submit(() -> {
                writer.set(Thread.currentThread());
                attempting.countDown();
                return invoke(cache, "put", 129, "new value");
            });
            assertTrue(attempting.await(5, TimeUnit.SECONDS));
            if (protectedCache) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (writer.get().getState() != Thread.State.BLOCKED && !put.isDone()
                        && System.nanoTime() < deadline) Thread.sleep(1);
                assertEquals(Thread.State.BLOCKED, writer.get().getState(), "put must share cleanup's monitor");
                assertFalse(put.isDone());
                map.release.countDown();
                cleanup.get(5, TimeUnit.SECONDS);
                put.get(5, TimeUnit.SECONDS);
                assertEquals("new value", invoke(cache, "get", 129));
            } else {
                put.get(5, TimeUnit.SECONDS);
                map.release.countDown();
                var failed = assertThrows(ExecutionException.class, () -> cleanup.get(5, TimeUnit.SECONDS));
                assertInstanceOf(ConcurrentModificationException.class, failed.getCause());
            }
        } finally {
            map.release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static void semantics(Class<?> type) throws Exception {
        var ttl = new AtomicInteger(1);
        Object cache = newCache(type, ttl::get);
        invoke(cache, "put", "old", "expired");
        Object entry = ((Map<?, ?>) field(type, "cache").get(cache)).get("old");
        field(entry.getClass(), "lastAccess").setLong(entry, System.currentTimeMillis() - 4000);
        assertNull(invoke(cache, "get", "old"));
        assertEquals("first", invoke(cache, "computeIfAbsent", "new", (Function<Object, Object>) key -> {
            assertFalse(Thread.holdsLock(cache), "factory must not acquire the cache monitor");
            return "first";
        }));
        assertEquals("first", invoke(cache, "computeIfAbsent", "new", (Function<Object, Object>) key -> {
            throw new AssertionError("live cache entries must not invoke the factory");
        }));
        var pool = Executors.newSingleThreadExecutor();
        try {
            assertEquals("outer", invoke(cache, "computeIfAbsent", "outer", (Function<Object, Object>) key -> {
                assertFalse(Thread.holdsLock(cache));
                try { pool.submit(() -> invoke(cache, "put", "inner", "inner")).get(5, TimeUnit.SECONDS); }
                catch (Exception failure) { throw new AssertionError("factory blocked another cache operation", failure); }
                return "outer";
            }));
            assertEquals("inner", invoke(cache, "get", "inner"));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertNull(invoke(cache, "computeIfAbsent", "null", (Function<Object, Object>) key -> null));
        assertFalse(((Map<?, ?>) field(type, "cache").get(cache)).containsKey("null"));
        assertThrows(IllegalStateException.class, () ->
                invoke(cache, "computeIfAbsent", "failed", (Function<Object, Object>) key -> {
                    throw new IllegalStateException("factory failure");
                }));
        assertNull(invoke(cache, "get", "failed"));
        invoke(cache, "clear");
        assertTrue(((Map<?, ?>) field(type, "cache").get(cache)).isEmpty());
        ttl.set(0);
        invoke(cache, "put", "disabled", "value");
        assertNull(invoke(cache, "get", "disabled"));
    }

    private static void parallelOperations(Class<?> type) throws Exception {
        Object cache = newCache(type, () -> 1);
        var pool = Executors.newFixedThreadPool(4);
        Method cleanup = type.getDeclaredMethod("cleanup", long.class);
        cleanup.setAccessible(true);
        try {
            var futures = new ArrayList<Future<?>>();
            for (int thread = 0; thread < 4; thread++) {
                int base = thread * 512;
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < 2000; i++) {
                        invoke(cache, "put", base + i % 512, i);
                        invoke(cache, "get", base + i % 512);
                        if (i % 32 == 0) synchronized (cache) { cleanup.invoke(cache, System.currentTimeMillis()); }
                        if (i % 127 == 0) invoke(cache, "clear");
                    }
                    return null;
                }));
            }
            for (Future<?> future : futures) future.get(15, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
