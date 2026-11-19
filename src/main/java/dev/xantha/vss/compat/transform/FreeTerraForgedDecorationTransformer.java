package dev.xantha.vss.compat.transform;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Replaces only verified read-only context lookups; upstream placement stays intact. */
public final class FreeTerraForgedDecorationTransformer {
    private static final String API = "dev/xantha/vss/client/prediction/FreeTerraForgedDecorationContext";
    private static final String OBJECT = "Ljava/lang/Object;";
    private static final List<String> PREFIXES = List.of("etcodehome/freeterraforged/",
            "raccoonman/reterraforged/", "com/pandaismyname1/ultraterraforged/");
    private static final Set<String> STATE_READERS = Set.of("feature/placement/poisson/FastPoissonModifier",
            "feature/placement/UndergroundFeatureEnclosure", "feature/RiverGasketFeature", "feature/DecorateSnowFeature");

    private FreeTerraForgedDecorationTransformer() { }

    public static int instrument(ClassNode node) {
        String suffix = null;
        for (String prefix : PREFIXES) if (node.name.startsWith(prefix + "world/worldgen/"))
            suffix = node.name.substring((prefix + "world/worldgen/").length());
        if (suffix == null) return 0;
        if (suffix.equals("feature/template/TemplateFeature")) return templates(node);
        if (!STATE_READERS.contains(suffix)) return 0;
        int changed = 0;
        for (var names : List.of(Names.NAMED, Names.INTERMEDIARY))
            for (var method : node.methods) {
                boolean placement = suffix.endsWith("FastPoissonModifier")
                        ? method.name.equals(names.positions) && method.desc.equals("(" + names.placement
                                + names.random + names.pos + ")Ljava/util/stream/Stream;")
                        : suffix.endsWith("UndergroundFeatureEnclosure")
                        ? method.name.equals("create") && method.desc.equals("(" + names.placement + ")Ljava/util/Optional;")
                        : method.name.equals(names.place) && method.desc.equals("(" + names.feature + ")Z");
                if (!placement) continue;
                for (var instruction : method.instructions.toArray()) {
                    if (!(instruction instanceof MethodInsnNode level)
                            || !call(level, Opcodes.INVOKEINTERFACE, names.level, names.getLevel, "()L" + names.server + ";")) continue;
                    var next = nextCode(level);
                    if (!(next instanceof MethodInsnNode chunks)
                            || !call(chunks, Opcodes.INVOKEVIRTUAL, names.server, names.getChunks, "()L" + names.chunks + ";")) continue;
                    var last = nextCode(chunks);
                    if (!(last instanceof MethodInsnNode state)
                            || !call(state, Opcodes.INVOKEVIRTUAL, names.chunks, names.getState, "()L" + names.state + ";")) continue;
                    method.instructions.set(level, new MethodInsnNode(Opcodes.INVOKESTATIC, API,
                            "randomState", "(" + OBJECT + ")" + OBJECT, false));
                    method.instructions.remove(chunks);
                    method.instructions.set(state, new TypeInsnNode(Opcodes.CHECKCAST, names.state));
                    changed++;
                }
            }
        return changed;
    }

    private static int templates(ClassNode node) {
        int changed = 0;
        String prefix = node.name.substring(0, node.name.indexOf("world/worldgen/"));
        for (var names : List.of(Names.NAMED, Names.INTERMEDIARY))
            for (var method : node.methods) {
                String descriptor = "(L" + names.level + ";" + names.random + names.pos
                        + names.mirror + names.rotation + "L" + node.name + "$Config;L" + prefix
                        + "world/worldgen/feature/template/paste/PasteType;Z)Z";
                if (!method.name.equals("paste") || !method.desc.equals(descriptor)) continue;
                for (var instruction : method.instructions.toArray()) {
                    if (!(instruction instanceof MethodInsnNode server)
                            || !call(server, Opcodes.INVOKEINTERFACE, names.level, names.getServer,
                                    "()Lnet/minecraft/server/MinecraftServer;")) continue;
                    var store = nextCode(server);
                    var read = store == null ? null : nextCode(store);
                    var test = read == null ? null : nextCode(read);
                    var branch = test == null ? null : nextCode(test);
                    var again = branch == null ? null : nextCode(branch);
                    var cast = again == null ? null : nextCode(again);
                    if (!(store instanceof VarInsnNode s) || s.getOpcode() != Opcodes.ASTORE
                            || !(read instanceof VarInsnNode r) || r.getOpcode() != Opcodes.ALOAD || r.var != s.var
                            || !(test instanceof TypeInsnNode t) || t.getOpcode() != Opcodes.INSTANCEOF
                            || !t.desc.startsWith(prefix + "server/") || !t.desc.endsWith("MinecraftServer")
                            || !(branch instanceof JumpInsnNode b) || b.getOpcode() != Opcodes.IFEQ
                            || !(again instanceof VarInsnNode a) || a.getOpcode() != Opcodes.ALOAD || a.var != s.var
                            || !(cast instanceof TypeInsnNode c) || c.getOpcode() != Opcodes.CHECKCAST || !c.desc.equals(t.desc)) continue;
                    method.instructions.insertBefore(server, new LdcInsnNode(org.objectweb.asm.Type.getObjectType(t.desc)));
                    method.instructions.set(server, new MethodInsnNode(Opcodes.INVOKESTATIC, API, "templateServer",
                            "(" + OBJECT + "Ljava/lang/Class;)" + OBJECT, false));
                    // Only this temporary receiver changes type; original interface casts and algorithms remain.
                    for (var i : method.instructions) if (i instanceof FrameNode frame && frame.local != null)
                        for (int n = 0; n < frame.local.size(); n++)
                            if ("net/minecraft/server/MinecraftServer".equals(frame.local.get(n)))
                                frame.local.set(n, "java/lang/Object");
                    if (method.localVariables != null) for (var local : method.localVariables)
                        if (local.index == s.var && local.desc.equals("Lnet/minecraft/server/MinecraftServer;"))
                            local.desc = OBJECT;
                    method.maxStack++;
                    changed++;
                }
            }
        return changed;
    }

    private static boolean call(MethodInsnNode call, int opcode, String owner, String name, String descriptor) {
        return call.getOpcode() == opcode && call.owner.equals(owner) && call.name.equals(name) && call.desc.equals(descriptor);
    }
    private static AbstractInsnNode nextCode(AbstractInsnNode node) {
        do { node = node.getNext(); } while (node != null && node.getOpcode() < 0);
        return node;
    }
    private record Names(String level, String server, String chunks, String state, String placement,
                         String random, String pos, String feature, String getLevel, String getChunks,
                         String getState, String positions, String place, String mirror, String rotation,
                         String getServer) {
        static final Names NAMED = new Names("net/minecraft/world/level/WorldGenLevel",
                "net/minecraft/server/level/ServerLevel", "net/minecraft/server/level/ServerChunkCache",
                "net/minecraft/world/level/levelgen/RandomState",
                "Lnet/minecraft/world/level/levelgen/placement/PlacementContext;", "Lnet/minecraft/util/RandomSource;",
                "Lnet/minecraft/core/BlockPos;", "Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;",
                "getLevel", "getChunkSource", "randomState", "getPositions", "place",
                "Lnet/minecraft/world/level/block/Mirror;", "Lnet/minecraft/world/level/block/Rotation;", "getServer");
        // The 1.21.1 Fabric release uses intermediary names; VSS methods keep their names.
        static final Names INTERMEDIARY = new Names("net/minecraft/class_5281", "net/minecraft/class_3218",
                "net/minecraft/class_3215", "net/minecraft/class_7138", "Lnet/minecraft/class_5444;",
                "Lnet/minecraft/class_5819;", "Lnet/minecraft/class_2338;", "Lnet/minecraft/class_5821;",
                "method_8410", "method_14178", "method_41248", "method_14452", "method_13151",
                "Lnet/minecraft/class_2415;", "Lnet/minecraft/class_2470;", "method_8503");
    }
}
