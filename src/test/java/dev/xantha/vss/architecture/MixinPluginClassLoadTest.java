package dev.xantha.vss.architecture;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * A mixin config plugin's preApply and postApply run while a target class is transformed, after Mixin registered every
 * config's mixin package; any class they load from such a package fails with IllegalClassLoadError ("is in a defined
 * mixin package ... cannot be referenced directly"). The r35 boot failed that way: TheBeyondMixinPlugin.postApply loaded
 * BeyondHandlerPatch from dev.xantha.vss.mixin.thebeyond.
 */
class MixinPluginClassLoadTest {
    private static final Pattern TYPE = Pattern.compile("L([^;<>]+);");

    @Test
    void transformTimePluginCodeLoadsNothingFromAMixinPackage() throws IOException {
        List<Path> roots = mainClassRoots();
        Map<String, String> packages = new LinkedHashMap<>();
        Map<String, String> plugins = new LinkedHashMap<>();
        try (var files = Files.list(Path.of("src/main/resources"))) {
            for (Path config : files.filter(f -> f.getFileName().toString().endsWith(".mixins.json")).sorted().toList()) {
                var json = JsonParser.parseString(Files.readString(config)).getAsJsonObject();
                String name = config.getFileName().toString();
                packages.put(name, json.get("package").getAsString().replace('.', '/') + '/');
                if (json.has("plugin")) plugins.put(name, json.get("plugin").getAsString().replace('.', '/'));
            }
        }
        assertTrue(plugins.containsKey("vss.thebeyond.mixins.json"), "The Beyond's config keeps its plugin");
        List<String> problems = new ArrayList<>();
        for (var entry : plugins.entrySet()) {
            String plugin = entry.getValue();
            ClassNode root = read(roots, plugin);
            assertNotNull(root, "plugin class of " + entry.getKey() + " is compiled: " + plugin);
            for (var reached : reachedAtTransform(roots, root).entrySet()) {
                for (var pkg : packages.entrySet()) {
                    if (reached.getKey().startsWith(pkg.getValue())) {
                        problems.add(entry.getKey() + ": " + reached.getValue() + " loads " + reached.getKey()
                                + " from the mixin package of " + pkg.getKey());
                    }
                }
            }
        }
        assertEquals(List.of(), problems);
    }

    @Test
    void theCheckFindsTheR35Failure() throws IOException {
        // The same walk over a plugin whose postApply reaches a class of its own mixin package, as in 51cee98.
        ClassNode plugin = new ClassNode();
        plugin.name = "fixture/mixin/Plugin";
        MethodNode postApply = new MethodNode(0, "postApply", "()V", null, null);
        postApply.instructions.add(new MethodInsnNode(184, "fixture/mixin/Patch", "apply", "()V", false));
        plugin.methods.add(postApply);
        var reached = reachedAtTransform(List.of(), plugin);
        assertTrue(reached.containsKey("fixture/mixin/Patch"), reached.toString());
    }

    private static List<Path> mainClassRoots() {
        String directories = System.getProperty("vss.mainClasses");
        assertNotNull(directories, "Run architectureTest or verifyModules, not a bare JUnit launch");
        return Arrays.stream(directories.split(Pattern.quote(File.pathSeparator))).map(Path::of).filter(Files::isDirectory).toList();
    }

    private static ClassNode read(List<Path> roots, String name) throws IOException {
        for (Path root : roots) {
            Path file = root.resolve(name + ".class");
            if (Files.isRegularFile(file)) {
                ClassNode node = new ClassNode();
                new ClassReader(Files.readAllBytes(file)).accept(node, ClassReader.SKIP_FRAMES);
                return node;
            }
        }
        return null;
    }

    /** Classes the plugin's preApply/postApply (and the plugin methods and lambdas they call) can load, then everything those name. */
    private static Map<String, String> reachedAtTransform(List<Path> roots, ClassNode plugin) throws IOException {
        Map<String, MethodNode> methods = new HashMap<>();
        for (MethodNode m : plugin.methods) methods.put(m.name + m.desc, m);
        Map<String, String> reached = new LinkedHashMap<>();
        ArrayDeque<MethodNode> todo = new ArrayDeque<>();
        for (MethodNode m : plugin.methods) if (m.name.equals("preApply") || m.name.equals("postApply")) todo.add(m);
        Set<MethodNode> seen = new HashSet<>();
        while (!todo.isEmpty()) {
            MethodNode method = todo.poll();
            if (!seen.add(method)) continue;
            Set<String> classes = new HashSet<>();
            types(method.desc, classes);
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof TypeInsnNode t) { if (t.desc.startsWith("[")) types(t.desc, classes); else classes.add(t.desc); }
                if (insn instanceof MultiANewArrayInsnNode a) types(a.desc, classes);
                if (insn instanceof FieldInsnNode f) { classes.add(f.owner); types(f.desc, classes); }
                if (insn instanceof MethodInsnNode call) {
                    classes.add(call.owner);
                    types(call.desc, classes);
                    if (call.owner.equals(plugin.name) && methods.containsKey(call.name + call.desc)) todo.add(methods.get(call.name + call.desc));
                }
                if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Type type) types(type.getDescriptor(), classes);
                if (insn instanceof InvokeDynamicInsnNode indy) {
                    types(indy.desc, classes);
                    for (Object arg : indy.bsmArgs) {
                        if (arg instanceof Handle handle) {
                            classes.add(handle.getOwner());
                            types(handle.getDesc(), classes);
                            if (handle.getOwner().equals(plugin.name) && methods.containsKey(handle.getName() + handle.getDesc()))
                                todo.add(methods.get(handle.getName() + handle.getDesc()));
                        }
                    }
                }
            }
            for (TryCatchBlockNode block : method.tryCatchBlocks) if (block.type != null) classes.add(block.type);
            for (String c : classes) if (!c.equals(plugin.name)) reached.putIfAbsent(c, plugin.name + "." + method.name);
        }
        // Classes compiled with VSS are followed as whole classes; library and game classes stop the walk.
        ArrayDeque<String> queue = new ArrayDeque<>(reached.keySet());
        while (!queue.isEmpty()) {
            String name = queue.poll();
            if (!name.startsWith("dev/xantha/vss/")) continue;
            ClassNode node = read(roots, name);
            if (node == null) continue;
            Set<String> classes = new HashSet<>();
            if (node.superName != null) classes.add(node.superName);
            classes.addAll(node.interfaces);
            node.fields.forEach(f -> types(f.desc, classes));
            for (MethodNode m : node.methods) {
                types(m.desc, classes);
                for (AbstractInsnNode insn : m.instructions) {
                    if (insn instanceof TypeInsnNode t) { if (t.desc.startsWith("[")) types(t.desc, classes); else classes.add(t.desc); }
                    if (insn instanceof FieldInsnNode f) classes.add(f.owner);
                    if (insn instanceof MethodInsnNode call) classes.add(call.owner);
                }
            }
            for (String c : classes) {
                if (!c.equals(plugin.name) && !reached.containsKey(c)) {
                    reached.put(c, reached.get(name) + " > " + name);
                    queue.add(c);
                }
            }
        }
        return reached;
    }

    private static void types(String descriptor, Set<String> into) {
        if (descriptor == null) return;
        Matcher m = TYPE.matcher(descriptor);
        while (m.find()) into.add(m.group(1));
    }
}
