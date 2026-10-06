package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Inspects released Xaero bytecode without substituting our fixtures or requiring XaeroLib. */
@EnabledIfSystemProperty(named = "vss.xaeroJars", matches = ".+")
class XaeroActualJarContractTest {
    static Stream<Path> jars() {
        return Arrays.stream(System.getProperty("vss.xaeroJars").split("\\|"))
                .map(Path::of);
    }

    @ParameterizedTest(name = "write/save API: {0}")
    @MethodSource("jars")
    void reflectedWriteAndSaveMembersExistInReleasedJar(Path path) throws Exception {
        try (var api = new JarApi(path)) {
            api.methods("WorldMapSession", "getCurrentSession()Lxaero/map/WorldMapSession;",
                    "isUsable()Z", "getMapProcessor()Lxaero/map/MapProcessor;");
            api.fields("MapProcessor", "renderThreadPauseSync", "mainStuffSync", "mainWorld");
            api.methods("MapProcessor", "isWritingPaused()Z", "isWaitingForWorldUpdate()Z",
                    "isCurrentMapLocked()Z", "isCurrentMultiworldWritable()Z",
                    "getCurrentWorldId()Ljava/lang/String;", "getCurrentDimension()Ljava/lang/String;",
                    "getMapWorld()Lxaero/map/world/MapWorld;",
                    "getMapSaveLoad()Lxaero/map/file/MapSaveLoad;",
                    "getLeafMapRegion(IIIZ)Lxaero/map/region/MapRegion;",
                    "getTilePool()Lxaero/map/pool/MapTilePool;",
                    "getOverlayManager()Lxaero/map/region/OverlayManager;",
                    "getBlockStateShortShapeCache()Lxaero/map/cache/BlockStateShortShapeCache;",
                    "getMapRegionHighlightsPreparer()Lxaero/map/highlight/MapRegionHighlightsPreparer;",
                    "getWorldBlockTintProvider()Lxaero/map/biome/BlockTintProvider;");
            api.publicMethodWithArity("MapProcessor", "getWorld", 0);
            api.publicMethodWithArity("MapProcessor", "ignoreWorld", 1);
            api.methods("world/MapWorld", "isCacheOnlyMode()Z",
                    "getCurrentDimensionId()Lnet/minecraft/resources/ResourceKey;");
            api.methods("file/MapSaveLoad", "isRegionDetectionComplete()Z",
                    "requestLoad(Lxaero/map/region/MapRegion;Ljava/lang/String;)V");
            api.fields("region/MapRegion", "writerThreadPauseSync");
            api.methods("region/MapRegion", "isWritingPaused()Z", "getLoadState()B",
                    "setLoadState(B)V", "isResting()Z", "registerVisit()V",
                    "setBeingWritten(Z)V", "canRequestReload_unsynced()Z", "setAllCachePrepared(Z)V",
                    "getChunk(II)Lxaero/map/region/MapTileChunk;",
                    "setChunk(IILxaero/map/region/MapTileChunk;)V");
            api.methods("region/MapTileChunk", "<init>(Lxaero/map/region/MapRegion;II)V",
                    "getLoadState()I", "setLoadState(B)V", "setChanged(Z)V", "wasChanged()Z",
                    "setHasHadTerrain()V", "includeInSave()Z",
                    "getLeafTexture()Lxaero/map/region/texture/LeafRegionTexture;",
                    "getTile(II)Lxaero/map/region/MapTile;",
                    "updateBuffers(Lxaero/map/MapProcessor;Lxaero/map/biome/BlockTintProvider;"
                            + "Lxaero/map/region/OverlayManager;ZLxaero/map/cache/BlockStateShortShapeCache;"
                            + "Lxaero/map/region/MapUpdateFastConfig;)V");
            api.methods("region/texture/LeafRegionTexture", "shouldDownloadFromPBO()Z");
            api.methods("pool/MapTilePool", "get(Ljava/lang/String;II)Lxaero/map/region/MapTile;");
            api.methods("region/MapTile", "isLoaded()Z", "wasWrittenOnce()Z",
                    "setBlock(IILxaero/map/region/MapBlock;)V", "setWorldInterpretationVersion(I)V",
                    "setWrittenCave(II)V", "setWrittenOnce(Z)V", "setLoaded(Z)V");
            api.methods("region/MapBlock", "<init>()V", "prepareForWriting(I)V",
                    "write(Lnet/minecraft/world/level/block/state/BlockState;II"
                            + "Lnet/minecraft/resources/ResourceKey;BZZ)V",
                    "addOverlay(Lxaero/map/region/Overlay;)V");
            api.methods("region/Overlay", "<init>(Lnet/minecraft/world/level/block/state/BlockState;BZ)V",
                    "increaseOpacity(I)V");
            api.methods("region/OverlayManager", "getOriginal(Lxaero/map/region/Overlay;)Lxaero/map/region/Overlay;");
            api.methods("highlight/MapRegionHighlightsPreparer", "prepare(Lxaero/map/region/MapRegion;IIZ)V");

            api.anyMethod("region/MapTileChunk",
                    "setTile(IILxaero/map/region/MapTile;Lxaero/map/cache/BlockStateShortShapeCache;"
                            + "Lxaero/map/MapProcessor;)V",
                    "setTile(IILxaero/map/region/MapTile;Lxaero/map/cache/BlockStateShortShapeCache;)V");
            api.anyMethod("region/MapUpdateFastConfig", "<init>(Lxaero/map/MapProcessor;)V", "<init>()V");
            if (!api.hasMethod("MapProcessor", "getCaveModeDepthConfig()I")) {
                api.fields("WorldMap", "INSTANCE");
                api.fields("common/config/option/WorldMapProfiledConfigOptions", "CAVE_MODE_DEPTH");
                api.publicMethodWithArity("WorldMap", "getConfigs", 0);
            }
        }
    }

    @ParameterizedTest(name = "terrain/save lifecycle: {0}")
    @MethodSource("jars")
    void terrainFlagPropagatesToRegionAndControlsSaving(Path path) throws Exception {
        try (var api = new JarApi(path)) {
            MethodNode mark = api.method("region/MapTileChunk", "setHasHadTerrain()V");
            assertTrue(Stream.of(mark.instructions.toArray()).anyMatch(instruction ->
                    instruction instanceof MethodInsnNode call && call.name.equals("setHasHadTerrain")
                            && call.desc.equals("()V") && call.owner.equals("xaero/map/region/MapRegion")),
                    "marking a successfully committed tile chunk must also mark its region");
            ClassNode save = api.read("xaero/map/file/MapSaveLoad");
            assertTrue(save.methods.stream().filter(method -> method.name.equals("saveRegion"))
                    .flatMap(method -> Stream.of(method.instructions.toArray())).anyMatch(instruction ->
                            instruction instanceof MethodInsnNode call && call.name.equals("hasHadTerrain")
                                    && call.desc.equals("()Z")),
                    "the real save path must use the terrain flag covered by our regression");
        }
    }

    private static final class JarApi implements AutoCloseable {
        private final ZipFile jar;
        private final Map<String, ClassNode> classes = new HashMap<>();

        JarApi(Path path) throws Exception { jar = new ZipFile(path.toFile()); }

        ClassNode read(String internalName) throws Exception {
            ClassNode cached = classes.get(internalName);
            if (cached != null) return cached;
            var entry = jar.getEntry(internalName + ".class");
            assertNotNull(entry, jar.getName() + ": missing " + internalName);
            var node = new ClassNode();
            try (var input = jar.getInputStream(entry)) {
                new ClassReader(input.readAllBytes()).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
            classes.put(internalName, node);
            return node;
        }

        MethodNode findMethod(String internalName, String signature) throws Exception {
            ClassNode node = read(internalName);
            for (MethodNode method : node.methods) {
                if ((method.name + method.desc).equals(signature) && (method.access & Opcodes.ACC_PUBLIC) != 0)
                    return method;
            }
            return node.superName != null && node.superName.startsWith("xaero/")
                    ? findMethod(node.superName, signature) : null;
        }

        boolean hasMethod(String owner, String signature) throws Exception {
            return findMethod("xaero/map/" + owner, signature) != null;
        }

        MethodNode method(String owner, String signature) throws Exception {
            MethodNode found = findMethod("xaero/map/" + owner, signature);
            assertNotNull(found, jar.getName() + ": missing public " + owner + "." + signature);
            return found;
        }

        void methods(String owner, String... signatures) throws Exception {
            for (String signature : signatures) method(owner, signature);
        }

        void anyMethod(String owner, String... signatures) throws Exception {
            for (String signature : signatures) if (hasMethod(owner, signature)) return;
            fail(jar.getName() + ": no supported signature for " + owner + " " + Arrays.toString(signatures));
        }

        void publicMethodWithArity(String owner, String name, int arity) throws Exception {
            assertTrue(read("xaero/map/" + owner).methods.stream().anyMatch(method ->
                    method.name.equals(name) && (method.access & Opcodes.ACC_PUBLIC) != 0
                            && org.objectweb.asm.Type.getArgumentTypes(method.desc).length == arity),
                    jar.getName() + ": missing public " + owner + "." + name);
        }

        void fields(String owner, String... names) throws Exception {
            for (String name : names) {
                ClassNode node = read("xaero/map/" + owner);
                boolean found = false;
                while (node != null) {
                    if (node.fields.stream().anyMatch(field -> field.name.equals(name)
                            && (field.access & Opcodes.ACC_PUBLIC) != 0)) {
                        found = true;
                        break;
                    }
                    node = node.superName != null && node.superName.startsWith("xaero/")
                            ? read(node.superName) : null;
                }
                assertTrue(found, jar.getName() + ": missing public " + owner + "." + name);
            }
        }

        @Override public void close() throws Exception { jar.close(); }
    }
}
