package dev.xantha.vss.testutil;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.launch.platform.container.ContainerHandleVirtual;
import org.spongepowered.asm.launch.platform.container.IContainerHandle;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.service.*;

/** Real Sponge application in an isolated test JVM; no game or resource-owning Voxy constructors. */
public final class VoxyMixinHarness extends MixinServiceAbstract implements IClassProvider, IClassBytecodeProvider {
    private final Map<String, byte[]> classes = new HashMap<>();
    private final String configuration;
    private IMixinTransformer transformer;

    public VoxyMixinHarness(String jar, String configuration) throws IOException {
        this.configuration = configuration;
        try (ZipFile zip = new ZipFile(jar)) {
            for (var entries = zip.entries(); entries.hasMoreElements();) {
                var entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) continue;
                try (var input = zip.getInputStream(entry)) {
                    classes.put(entry.getName(), input.readAllBytes());
                }
            }
        }
    }

    public byte[] apply(String name) throws Exception {
        if (transformer == null) {
            var instance = MixinService.class.getDeclaredMethod("getInstance");
            instance.setAccessible(true);
            Object singleton = instance.invoke(null);
            var service = MixinService.class.getDeclaredField("service");
            service.setAccessible(true);
            service.set(singleton, this);
            var properties = MixinService.class.getDeclaredField("propertyService");
            properties.setAccessible(true);
            properties.set(singleton, new Properties());
            MixinBootstrap.init();
            MixinEnvironment.getCurrentEnvironment().setSide(MixinEnvironment.Side.CLIENT);
            Mixins.addConfiguration("vss.ingest.test.mixins.json");
            var constructor = Class.forName("org.spongepowered.asm.mixin.transformer.MixinTransformer")
                    .getDeclaredConstructor();
            constructor.setAccessible(true);
            transformer = (IMixinTransformer) constructor.newInstance();
        }
        byte[] bytes = classes.get(name.replace('.', '/') + ".class");
        if (bytes == null) throw new ClassNotFoundException(name);
        return transformer.transformClass(MixinEnvironment.getCurrentEnvironment(), name, bytes);
    }

    /** Fixture variants are installed before Sponge prepares any target metadata. */
    public void replaceClass(String name, byte[] bytes) {
        if (transformer != null) throw new IllegalStateException("Mixin already prepared");
        classes.put(name.replace('.', '/') + ".class", bytes);
    }

    @Override public String getName() { return "VSS focused regression"; }
    @Override public boolean isValid() { return true; }
    @Override public MixinEnvironment.Phase getInitialPhase() { return MixinEnvironment.Phase.DEFAULT; }
    @Override public IClassProvider getClassProvider() { return this; }
    @Override public IClassBytecodeProvider getBytecodeProvider() { return this; }
    @Override public ITransformerProvider getTransformerProvider() { return null; }
    @Override public IClassTracker getClassTracker() { return null; }
    @Override public IMixinAuditTrail getAuditTrail() { return null; }
    @Override public Collection<String> getPlatformAgents() { return List.of(); }
    @Override public IContainerHandle getPrimaryContainer() { return new ContainerHandleVirtual("VSS ingest tests"); }
    @Override public InputStream getResourceAsStream(String name) {
        if (name.equals("vss.ingest.test.mixins.json"))
            return new ByteArrayInputStream(configuration.getBytes(StandardCharsets.UTF_8));
        byte[] bytes = classes.get(name);
        return bytes != null ? new ByteArrayInputStream(bytes) : getClass().getClassLoader().getResourceAsStream(name);
    }

    @Override public URL[] getClassPath() { return new URL[0]; }
    @Override public Class<?> findClass(String name) throws ClassNotFoundException { return findClass(name, false); }
    @Override public Class<?> findClass(String name, boolean initialize) throws ClassNotFoundException {
        return Class.forName(name, initialize, getClass().getClassLoader());
    }
    @Override public Class<?> findAgentClass(String name, boolean initialize) throws ClassNotFoundException {
        return findClass(name, initialize);
    }
    @Override public ClassNode getClassNode(String name) throws IOException, ClassNotFoundException {
        return getClassNode(name, true);
    }
    @Override public ClassNode getClassNode(String name, boolean transform) throws IOException, ClassNotFoundException {
        return getClassNode(name, transform, 0);
    }
    // The flags overload was added after Forge 1.20.1's Mixin 0.8.5.
    public ClassNode getClassNode(String name, boolean transform, int flags)
            throws IOException, ClassNotFoundException {
        try (InputStream input = getResourceAsStream(name.replace('.', '/') + ".class")) {
            if (input == null) throw new ClassNotFoundException(name);
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, flags);
            return node;
        }
    }

    private static final class Properties implements IGlobalPropertyService {
        private record Key(String name) implements IPropertyKey { }
        private final Map<IPropertyKey, Object> values = new HashMap<>();
        @Override public IPropertyKey resolveKey(String name) { return new Key(name); }
        @Override @SuppressWarnings("unchecked") public <T> T getProperty(IPropertyKey key) { return (T) values.get(key); }
        @Override public void setProperty(IPropertyKey key, Object value) { values.put(key, value); }
        @Override @SuppressWarnings("unchecked") public <T> T getProperty(IPropertyKey key, T fallback) {
            return (T) values.getOrDefault(key, fallback);
        }
        @Override public String getPropertyString(IPropertyKey key, String fallback) {
            Object value = values.get(key);
            return value == null ? fallback : value.toString();
        }
    }
}
