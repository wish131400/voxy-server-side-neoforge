package dev.xantha.vss.client.prediction;

import static org.objectweb.asm.Opcodes.*;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CancellationException;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

/** Specializes raw density arithmetic; noise, splines and custom evaluators keep their original calls. */
final class DensityGraphCompiler {
    interface Kernel {
        double evaluate(int root, DensityMemo.Slots slots, DensityFunction.FunctionContext context, long xyz, long xz);
    }

    record Metrics(double buildMs, double analyzeMs, double emitMs, double defineMs,
                   int bytecodeBytes, int nodes, int specialized, int memoSlots, boolean reused) { }

    private record Node(DensityFunction function, DensityFunction delegate, int memoId,
                        boolean horizontal, String kind, Object[] parts, int[] children) { }

    // Templates retain code only. No world, noise instance, node array or mutable memo is shared.
    private record Template(Constructor<?> constructor, int bytecodeBytes) { }
    private static final LinkedHashMap<String, Template> TEMPLATES = new LinkedHashMap<>(16, .75f, true);
    static final int MAX_TEMPLATES = 8;
    static final int MAX_TEMPLATE_BYTES = 1024 * 1024;
    private static int templateBytes;
    private static final int MAX_NODES = 4096;
    private static final int MAX_BYTECODE_BYTES = 256 * 1024;
    private static final String CONTEXT = Type.getInternalName(DensityFunction.FunctionContext.class);
    private static final String FUNCTION = Type.getInternalName(DensityFunction.class);
    private static final String SLOTS = Type.getInternalName(DensityMemo.Slots.class);
    private static final String SUPPORT = Type.getInternalName(DensityCompilerSupport.class);
    private static final String NODE_DESC = "(L" + SLOTS + ";L" + CONTEXT + ";JJ)D";
    private static final String CLASS = "dev/xantha/vss/client/prediction/VssCompiledDensity";

    private final List<Node> nodes = new ArrayList<>();
    private final IdentityHashMap<DensityFunction, Integer> ids = new IdentityHashMap<>();
    private final PredictionRawDensity analysis = new PredictionRawDensity();
    private DensityMemo owner;

    static final class Compiled {
        private final DensityFunction[] originals;
        private final DensityMemo owner;
        private final boolean[] pure;
        private final ThreadLocal<DensityMemo.Slots> local;
        private final Kernel kernel;
        private final Template template;
        private final Metrics metrics;

        private Compiled(DensityFunction[] originals, DensityMemo owner, Kernel kernel,
                         Template template, Metrics metrics) {
            this.originals = originals.clone();
            this.owner = owner;
            this.kernel = kernel;
            this.template = template;
            this.metrics = metrics;
            var properties = new PredictionRawDensity();
            this.pure = new boolean[originals.length];
            for (int i = 0; i < pure.length; i++) pure[i] = properties.inspect(originals[i]).pure();
            Object identity = new Object();
            this.local = owner == null ? ThreadLocal.withInitial(() -> new DensityMemo.Slots(0, identity)) : null;
        }

        double compute(int root, DensityFunction.FunctionContext context) {
            DensityMemo.Slots slots = owner == null ? local.get() : owner.slotsFor(context);
            if (owner != null) slots.ensureCapacity(owner.capacity());
            if (owner != null) context = owner.queryContext(slots, context, pure[root]);
            int x = context.blockX(), y = context.blockY(), z = context.blockZ();
            return kernel.evaluate(root, slots, context, DensityMemo.pack(x, y, z), DensityMemo.pack(x, 0, z));
        }

        Metrics metrics() { return metrics; }
        boolean sharesCodeWith(Compiled other) { return template.constructor == other.template.constructor; }
        DensityFunction original(int root) { return originals[root]; }
    }

    static Compiled compile(DensityFunction... roots) throws ReflectiveOperationException {
        return new DensityGraphCompiler().build(roots, true);
    }

    static Compiled compileUncached(DensityFunction... roots) throws ReflectiveOperationException {
        return new DensityGraphCompiler().build(roots, false);
    }

    private Compiled build(DensityFunction[] roots, boolean reuse) throws ReflectiveOperationException {
        long start = System.nanoTime();
        if (roots.length == 0) throw new IllegalArgumentException("No density roots");
        int[] rootIds = new int[roots.length];
        for (int i = 0; i < roots.length; i++) rootIds[i] = add(roots[i], 0);
        String shape = shape(rootIds);
        long analyzed = System.nanoTime();
        Template template;
        synchronized (TEMPLATES) { template = reuse ? TEMPLATES.get(shape) : null; }
        boolean reused = template != null;
        long emitted = analyzed;
        if (template == null) {
            byte[] bytes = emit(rootIds);
            if (bytes.length > MAX_BYTECODE_BYTES) throw new IllegalArgumentException("Density bytecode exceeds budget");
            emitted = System.nanoTime();
            checkCancelled();
            Class<?> type = MethodHandles.lookup().defineHiddenClass(bytes, true).lookupClass();
            template = new Template(type.getConstructor(DensityFunction[].class), bytes.length);
            if (reuse) remember(shape, template);
        }
        checkCancelled();
        DensityFunction[] fallback = nodes.stream().map(Node::function).toArray(DensityFunction[]::new);
        Kernel kernel = (Kernel) template.constructor.newInstance((Object) fallback);
        long defined = System.nanoTime();
        Metrics metrics = new Metrics((defined - start) / 1e6, (analyzed - start) / 1e6,
                (emitted - analyzed) / 1e6, (defined - emitted) / 1e6, template.bytecodeBytes, nodes.size(),
                (int) nodes.stream().filter(node -> !node.kind.equals("opaque")).count(),
                owner == null ? 0 : owner.capacity(), reused);
        return new Compiled(roots, owner, kernel, template, metrics);
    }

    private static void remember(String shape, Template template) {
        synchronized (TEMPLATES) {
            Template previous = TEMPLATES.put(shape, template);
            if (previous != null) templateBytes -= previous.bytecodeBytes;
            templateBytes += template.bytecodeBytes;
            while (TEMPLATES.size() > MAX_TEMPLATES || templateBytes > MAX_TEMPLATE_BYTES) {
                var iterator = TEMPLATES.entrySet().iterator();
                templateBytes -= iterator.next().getValue().bytecodeBytes;
                iterator.remove();
            }
        }
    }

    static int cachedTemplates() { synchronized (TEMPLATES) { return TEMPLATES.size(); } }
    static int cachedBytecodeBytes() { synchronized (TEMPLATES) { return templateBytes; } }

    private int add(DensityFunction function, int depth) throws ReflectiveOperationException {
        checkCancelled();
        if (function == null) throw new IllegalArgumentException("Null density node");
        Integer previous = ids.get(function);
        if (previous != null) {
            if (nodes.get(previous) == null) throw new IllegalArgumentException("Cyclic density graph");
            return previous;
        }
        if (depth > 128 || nodes.size() >= MAX_NODES) throw new IllegalArgumentException("Density graph exceeds budget");
        int index = nodes.size();
        ids.put(function, index);
        nodes.add(null);
        DensityFunction delegate = function;
        int memo = -1;
        boolean horizontal = false;
        if (function instanceof DensityMemo.Memoized cached) {
            if (owner != null && owner != cached.owner()) throw new IllegalArgumentException("Mixed density memo owners");
            owner = cached.owner();
            delegate = cached.delegate();
            memo = cached.slotId();
            horizontal = cached.horizontal();
        }
        Class<?> type = delegate.getClass();
        String kind = type.getName().startsWith("net.minecraft.world.level.levelgen.DensityFunctions$")
                && type.isRecord() && !analysis.inspect(delegate).stateful() ? type.getSimpleName() : "opaque";
        Object[] parts;
        List<DensityFunction> children = new ArrayList<>(3);
        switch (kind) {
            case "Constant" -> { parts = parts(delegate, 1); number(parts[0]); }
            case "YClampedGradient" -> { parts = parts(delegate, 4); for (Object p : parts) number(p); }
            case "Marker" -> { parts = parts(delegate, 2); children.add((DensityFunction) parts[1]); }
            case "HolderHolder" -> {
                parts = parts(delegate, 1); children.add((DensityFunction) ((Holder<?>) parts[0]).value());
            }
            case "Mapped", "MulOrAdd" -> {
                parts = parts(delegate, kind.equals("Mapped") ? 4 : 5);
                String op = ((Enum<?>) parts[0]).name();
                if (kind.equals("Mapped") && !List.of("ABS", "SQUARE", "CUBE", "HALF_NEGATIVE", "QUARTER_NEGATIVE", "SQUEEZE").contains(op)
                        || kind.equals("MulOrAdd") && !List.of("MUL", "ADD").contains(op))
                    throw new IllegalArgumentException("Unknown density operation");
                children.add((DensityFunction) parts[1]);
            }
            case "Clamp" -> { parts = parts(delegate, 3); children.add((DensityFunction) parts[0]); }
            case "Ap2" -> {
                parts = parts(delegate, 5);
                if (!List.of("ADD", "MUL", "MIN", "MAX").contains(((Enum<?>) parts[0]).name()))
                    throw new IllegalArgumentException("Unknown binary operation");
                children.add((DensityFunction) parts[1]); children.add((DensityFunction) parts[2]);
            }
            case "RangeChoice" -> {
                parts = parts(delegate, 5); children.add((DensityFunction) parts[0]);
                children.add((DensityFunction) parts[3]); children.add((DensityFunction) parts[4]);
            }
            default -> { kind = "opaque"; parts = new Object[0]; }
        }
        int[] childIds = new int[children.size()];
        for (int i = 0; i < childIds.length; i++) childIds[i] = add(children.get(i), depth + 1);
        nodes.set(index, new Node(function, delegate, memo, horizontal, kind, parts, childIds));
        return index;
    }

    private byte[] emit(int[] rootIds) {
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_FINAL, CLASS, null, "java/lang/Object",
                new String[]{Type.getInternalName(Kernel.class)});
        writer.visitField(ACC_PRIVATE | ACC_FINAL, "fallback", "[L" + FUNCTION + ";", null, null).visitEnd();
        var ctor = writer.visitMethod(ACC_PUBLIC, "<init>", "([L" + FUNCTION + ";)V", null, null);
        ctor.visitCode(); ctor.visitVarInsn(ALOAD, 0);
        ctor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitVarInsn(ALOAD, 0); ctor.visitVarInsn(ALOAD, 1);
        ctor.visitFieldInsn(PUTFIELD, CLASS, "fallback", "[L" + FUNCTION + ";");
        ctor.visitInsn(RETURN); finish(ctor);

        var eval = writer.visitMethod(ACC_PUBLIC, "evaluate", "(I" + NODE_DESC.substring(1), null, null);
        eval.visitCode(); eval.visitVarInsn(ILOAD, 1);
        Label[] labels = new Label[rootIds.length];
        for (int i = 0; i < labels.length; i++) labels[i] = new Label();
        Label invalid = new Label(); eval.visitTableSwitchInsn(0, labels.length - 1, invalid, labels);
        for (int i = 0; i < labels.length; i++) {
            eval.visitLabel(labels[i]); eval.visitVarInsn(ALOAD, 0); eval.visitVarInsn(ALOAD, 2);
            eval.visitVarInsn(ALOAD, 3); eval.visitVarInsn(LLOAD, 4); eval.visitVarInsn(LLOAD, 6);
            eval.visitMethodInsn(INVOKESPECIAL, CLASS, "n" + rootIds[i], NODE_DESC, false); eval.visitInsn(DRETURN);
        }
        eval.visitLabel(invalid); eval.visitTypeInsn(NEW, "java/lang/IllegalArgumentException");
        eval.visitInsn(DUP); eval.visitMethodInsn(INVOKESPECIAL, "java/lang/IllegalArgumentException", "<init>", "()V", false);
        eval.visitInsn(ATHROW); finish(eval);
        for (int i = 0; i < nodes.size(); i++) { checkCancelled(); emitNode(writer, i, nodes.get(i)); }
        writer.visitEnd(); return writer.toByteArray();
    }

    private void emitNode(ClassWriter writer, int id, Node node) {
        var m = writer.visitMethod(ACC_PRIVATE | ACC_FINAL, "n" + id, NODE_DESC, null, null);
        m.visitCode();
        if (node.kind.equals("opaque")) {
            fallback(m, id); m.visitVarInsn(ALOAD, 2);
            m.visitMethodInsn(INVOKESTATIC, SUPPORT, "compute", "(L" + FUNCTION + ";L" + CONTEXT + ";)D", false);
            m.visitInsn(DRETURN); finish(m); return;
        }
        // Opaque spline/noise paths and generated arithmetic share the existing memo arrays.
        boolean memo = node.memoId >= 0 && !node.kind.equals("Constant");
        if (memo) {
            Label miss = new Label(); array(m, "valid", "[Z"); integer(m, node.memoId); m.visitInsn(BALOAD);
            m.visitJumpInsn(IFEQ, miss); array(m, "coords", "[J"); integer(m, node.memoId); m.visitInsn(LALOAD);
            m.visitVarInsn(LLOAD, node.horizontal ? 5 : 3); m.visitInsn(LCMP); m.visitJumpInsn(IFNE, miss);
            array(m, "values", "[D"); integer(m, node.memoId); m.visitInsn(DALOAD); m.visitInsn(DRETURN);
            m.visitLabel(miss);
        }
        Object[] p = node.parts;
        switch (node.kind) {
            case "Constant" -> number(m, p[0]);
            case "Marker", "HolderHolder" -> child(m, node.children[0]);
            case "YClampedGradient" -> {
                m.visitVarInsn(ALOAD, 2); for (Object v : p) number(m, v);
                m.visitMethodInsn(INVOKESTATIC, SUPPORT, "gradient", "(L" + CONTEXT + ";DDDD)D", false);
            }
            case "Clamp" -> {
                child(m, node.children[0]); number(m, p[1]); number(m, p[2]);
                m.visitMethodInsn(INVOKESTATIC, SUPPORT, "clamp", "(DDD)D", false);
            }
            case "MulOrAdd" -> {
                child(m, node.children[0]); number(m, p[4]);
                m.visitInsn(((Enum<?>) p[0]).name().equals("MUL") ? DMUL : DADD);
            }
            case "Mapped" -> mapped(m, node, ((Enum<?>) p[0]).name());
            case "Ap2" -> binary(m, node, ((Enum<?>) p[0]).name());
            case "RangeChoice" -> {
                child(m, node.children[0]); m.visitVarInsn(DSTORE, 7);
                Label outside = new Label(), done = new Label();
                m.visitVarInsn(DLOAD, 7); number(m, p[1]); m.visitInsn(DCMPL); m.visitJumpInsn(IFLT, outside);
                m.visitVarInsn(DLOAD, 7); number(m, p[2]); m.visitInsn(DCMPG); m.visitJumpInsn(IFGE, outside);
                child(m, node.children[1]); m.visitJumpInsn(GOTO, done);
                m.visitLabel(outside); child(m, node.children[2]); m.visitLabel(done);
            }
            default -> throw new IllegalStateException(node.kind);
        }
        if (memo) {
            m.visitVarInsn(DSTORE, 7); array(m, "coords", "[J"); integer(m, node.memoId);
            m.visitVarInsn(LLOAD, node.horizontal ? 5 : 3); m.visitInsn(LASTORE);
            array(m, "values", "[D"); integer(m, node.memoId); m.visitVarInsn(DLOAD, 7); m.visitInsn(DASTORE);
            array(m, "valid", "[Z"); integer(m, node.memoId); m.visitInsn(ICONST_1); m.visitInsn(BASTORE);
            m.visitVarInsn(DLOAD, 7);
        }
        m.visitInsn(DRETURN); finish(m);
    }

    private void binary(MethodVisitor m, Node node, String op) {
        child(m, node.children[0]); m.visitVarInsn(DSTORE, 7);
        if (op.equals("ADD")) { m.visitVarInsn(DLOAD, 7); child(m, node.children[1]); m.visitInsn(DADD); return; }
        Label evaluate = new Label(), done = new Label();
        m.visitVarInsn(DLOAD, 7);
        if (op.equals("MUL")) {
            m.visitInsn(DCONST_0); m.visitInsn(DCMPL); m.visitJumpInsn(IFNE, evaluate);
            m.visitInsn(DCONST_0); m.visitJumpInsn(GOTO, done);
        } else {
            int right = node.children[1];
            DensityFunction function = nodes.get(right).function;
            if (analysis.inspect(function).pure()) m.visitLdcInsn(op.equals("MIN") ? function.minValue() : function.maxValue());
            else {
                // Custom bounds can depend on runtime state; vanilla reads them on every query.
                fallback(m, right);
                m.visitMethodInsn(INVOKESTATIC, SUPPORT, op.equals("MIN") ? "minValue" : "maxValue", "(L" + FUNCTION + ";)D", false);
            }
            m.visitInsn(op.equals("MIN") ? DCMPG : DCMPL);
            m.visitJumpInsn(op.equals("MIN") ? IFGE : IFLE, evaluate);
            m.visitVarInsn(DLOAD, 7); m.visitJumpInsn(GOTO, done);
        }
        m.visitLabel(evaluate); m.visitVarInsn(DLOAD, 7); child(m, node.children[1]);
        if (op.equals("MUL")) m.visitInsn(DMUL);
        else m.visitMethodInsn(INVOKESTATIC, "java/lang/Math", op.equals("MIN") ? "min" : "max", "(DD)D", false);
        m.visitLabel(done);
    }

    private void mapped(MethodVisitor m, Node node, String op) {
        child(m, node.children[0]); m.visitVarInsn(DSTORE, 7);
        switch (op) {
            case "ABS" -> { m.visitVarInsn(DLOAD, 7); m.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "abs", "(D)D", false); }
            case "SQUARE", "CUBE" -> {
                m.visitVarInsn(DLOAD, 7); m.visitVarInsn(DLOAD, 7); m.visitInsn(DMUL);
                if (op.equals("CUBE")) { m.visitVarInsn(DLOAD, 7); m.visitInsn(DMUL); }
            }
            case "HALF_NEGATIVE", "QUARTER_NEGATIVE" -> {
                Label positive = new Label(), done = new Label();
                m.visitVarInsn(DLOAD, 7); m.visitInsn(DCONST_0); m.visitInsn(DCMPL); m.visitJumpInsn(IFGT, positive);
                m.visitVarInsn(DLOAD, 7); m.visitLdcInsn(op.equals("HALF_NEGATIVE") ? .5 : .25); m.visitInsn(DMUL);
                m.visitJumpInsn(GOTO, done); m.visitLabel(positive); m.visitVarInsn(DLOAD, 7); m.visitLabel(done);
            }
            case "SQUEEZE" -> {
                m.visitVarInsn(DLOAD, 7); m.visitLdcInsn(-1.0); m.visitLdcInsn(1.0);
                m.visitMethodInsn(INVOKESTATIC, SUPPORT, "clamp", "(DDD)D", false);
                m.visitVarInsn(DSTORE, 7); m.visitVarInsn(DLOAD, 7); m.visitLdcInsn(2.0); m.visitInsn(DDIV);
                m.visitVarInsn(DLOAD, 7); m.visitVarInsn(DLOAD, 7); m.visitInsn(DMUL);
                m.visitVarInsn(DLOAD, 7); m.visitInsn(DMUL); m.visitLdcInsn(24.0); m.visitInsn(DDIV); m.visitInsn(DSUB);
            }
            default -> throw new IllegalArgumentException(op);
        }
    }

    private static void fallback(MethodVisitor m, int id) {
        m.visitVarInsn(ALOAD, 0); m.visitFieldInsn(GETFIELD, CLASS, "fallback", "[L" + FUNCTION + ";");
        integer(m, id); m.visitInsn(AALOAD);
    }

    private static void child(MethodVisitor m, int id) {
        m.visitVarInsn(ALOAD, 0); m.visitVarInsn(ALOAD, 1); m.visitVarInsn(ALOAD, 2);
        m.visitVarInsn(LLOAD, 3); m.visitVarInsn(LLOAD, 5);
        m.visitMethodInsn(INVOKESPECIAL, CLASS, "n" + id, NODE_DESC, false);
    }

    private static void array(MethodVisitor m, String name, String descriptor) {
        m.visitVarInsn(ALOAD, 1); m.visitFieldInsn(GETFIELD, SLOTS, name, descriptor);
    }

    private static void integer(MethodVisitor m, int value) { m.visitLdcInsn(value); }
    private static double number(Object value) { return ((Number) value).doubleValue(); }
    private static void number(MethodVisitor m, Object value) { m.visitLdcInsn(number(value)); }
    private static void finish(MethodVisitor m) { m.visitMaxs(0, 0); m.visitEnd(); }

    private static Object[] parts(Object record, int count) throws ReflectiveOperationException {
        var components = record.getClass().getRecordComponents();
        if (components.length != count) throw new IllegalArgumentException("Unknown density record layout");
        Object[] values = new Object[count];
        for (int i = 0; i < count; i++) {
            values[i] = PredictionRawDensity.recordValue(record, components[i]);
        }
        return values;
    }

    private String shape(int[] roots) {
        var result = new StringBuilder(Arrays.toString(roots));
        for (Node node : nodes) {
            result.append('|').append(node.kind).append(':').append(node.delegate.getClass().getName())
                    .append(':').append(node.memoId).append(':').append(node.horizontal)
                    .append(':').append(Arrays.toString(node.children));
            for (Object part : node.parts) {
                if (part instanceof Number number) result.append(':').append(Double.doubleToRawLongBits(number.doubleValue()));
                else if (part instanceof Enum<?> value) result.append(':').append(value.name());
            }
            if (analysis.inspect(node.function).pure())
                result.append(':').append(Double.doubleToRawLongBits(node.function.minValue()))
                        .append(':').append(Double.doubleToRawLongBits(node.function.maxValue()));
        }
        return result.toString();
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Density compilation cancelled");
    }
}
