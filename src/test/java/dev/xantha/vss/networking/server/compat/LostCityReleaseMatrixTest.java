package dev.xantha.vss.networking.server.compat;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;
import java.net.URLClassLoader;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.objectweb.asm.*;

/** Inspect shipped bytecode without initializing a foreign Minecraft/loader version. */
class LostCityReleaseMatrixTest {
    record Api(Map<String, Integer> methods, Set<String> fields) { }
    static Api read(JarFile jar, String name) throws Exception {
        Map<String,Integer> methods = new HashMap<>(); Set<String> fields = new HashSet<>();
        try (var in = jar.getInputStream(Objects.requireNonNull(jar.getJarEntry("mcjty/lostcities/" + name + ".class"), name))) {
            new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access,String n,String d,String signature,String[] exceptions) {
                    methods.put(n+d,access); return null;
                }
                @Override public FieldVisitor visitField(int access,String n,String d,String signature,Object value) {
                    fields.add(n); return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return new Api(methods,fields);
    }
    static void method(Api api,String name) { assertTrue(api.methods.keySet().stream().anyMatch(s->s.startsWith(name+"(")),name); }
    @TestFactory Stream<DynamicTest> installedReleaseContracts() throws Exception {
        String directory = System.getProperty("vss.lostCitiesMatrix");
        Assumptions.assumeTrue(directory != null, "Supply -PvssLostCitiesMatrix containing release jars");
        List<Path> jars;
        try (var paths=Files.list(Path.of(directory))) { jars=paths.filter(p->p.getFileName().toString().matches("lostcities-.*\\.jar")).sorted().toList(); }
        assertFalse(jars.isEmpty(),"No Lost Cities release jars found");
        return jars.stream().map(path->DynamicTest.dynamicTest(path.getFileName().toString(),()->{
            try (var jar=new JarFile(path.toFile())) {
                Api building=read(jar,"worldgen/lost/BuildingInfo");
                boolean dimension=building.methods.containsKey("getDimensionLock(Lnet/minecraft/resources/ResourceKey;)Ljava/lang/Object;");
                if (!dimension) {
                    assertTrue(building.methods.entrySet().stream().anyMatch(e->e.getKey().startsWith("getBuildingInfo(")
                            && (e.getValue() & (Opcodes.ACC_STATIC|Opcodes.ACC_SYNCHRONIZED)) == (Opcodes.ACC_STATIC|Opcodes.ACC_SYNCHRONIZED)));
                    assertTrue(building.fields.contains("palette"));
                    for(String asset:List.of("Building","BuildingPart")) {
                        Api a=read(jar,"worldgen/lost/cityassets/"+asset);
                        assertTrue(a.fields.containsAll(Set.of("localPalette","refPaletteName")),asset);
                    }
                    method(read(jar,"worldgen/lost/cityassets/Palette"),"getPalette");
                    assertTrue(read(jar,"worldgen/lost/cityassets/Palette").methods.containsKey("<init>(Ljava/lang/String;)V"));
                    assertTrue(read(jar,"worldgen/lost/cityassets/Palette$Info").methods.containsKey(
                            "<init>(Ljava/lang/String;Ljava/lang/String;ZLnet/minecraft/nbt/CompoundTag;)V"));
                    assertTrue(read(jar,"worldgen/lost/cityassets/Palette$PE").methods.containsKey(
                            "<init>(Ljava/lang/Object;Lmcjty/lostcities/worldgen/lost/cityassets/Palette$Info;)V"));
                    assertTrue(read(jar,"worldgen/lost/cityassets/RegistryAssetRegistry").fields.contains("registryKey"));
                    method(read(jar,"worldgen/lost/regassets/PaletteRE"),"getPaletteEntries");
                    method(read(jar,"worldgen/lost/regassets/VariantRE"),"getBlocks");
                    for(String m:List.of("getChr","getBlock","getVariant","getFrompalette","getBlocks"))
                        method(read(jar,"worldgen/lost/regassets/data/PaletteEntry"),m);
                    for(String m:List.of("block","random")) method(read(jar,"worldgen/lost/regassets/data/BlockEntry"),m);
                    assertTrue(read(jar,"varia/Tools").methods.containsKey("stringToState(Ljava/lang/String;)Lnet/minecraft/world/level/block/state/BlockState;"));
                    assertTrue(read(jar,"worldgen/lost/regassets/data/DataTools").methods.containsKey("fromName(Ljava/lang/String;)Lnet/minecraft/resources/ResourceLocation;"));
                }
                assertTrue(building.fields.containsAll(Set.of("floorTypes","floorTypes2","cellars","parkType","fountainType","frontType","streetType","profile")));
                for(String m:List.of("getCityGroundLevel","getCompiledPalette","getFloor","getFloorPart2","getNumFloors","getBuildingId","isCity")) method(building,m);
                for(String m:List.of("getXmin","getXmax","getZmin","getZmax","isElevatedParkSection","doesRoadExtendTo",
                        "hasXBridge","hasZBridge","getCityLevel","getHighwayXLevel","getHighwayZLevel","getRailInfo","isValidFloor")) method(building,m);
                Api style=read(jar,"worldgen/lost/cityassets/CityStyle");
                for(String m:List.of("getStreetParts","getStreetBlock","getStreetBaseBlock","getGrassBlock")) method(style,m);
                Api profile=read(jar,"config/LostCityProfile");
                for(var setting:Map.of("getParkElevation","PARK_ELEVATION","getParkBorder","PARK_BORDER").entrySet()) {
                    boolean styleSetting=style.methods.keySet().stream().anyMatch(s->s.startsWith(setting.getKey()+"("));
                    assertTrue(styleSetting || profile.fields.contains(setting.getValue()),setting.getKey()+" or profile fallback");
                }
                Api streets=read(jar,"worldgen/lost/regassets/data/StreetParts");
                for(String m:List.of("none","end","straight","bend","t","all","full")) method(streets,m);
                Api rawPart=read(jar,"worldgen/lost/regassets/BuildingPartRE");
                method(rawPart,"getMetadata");
                for (String m : List.of("key", "chr")) method(read(jar,"worldgen/lost/regassets/data/PartMeta"), m);
                for (String m : List.of("bridge", "bridgeBi", "open", "openBi", "tunnel", "tunnelBi"))
                    method(read(jar,"worldgen/lost/regassets/data/HighwayParts"), m);
                for (String m : List.of("railsHorizontal", "railsHorizontalWater", "railsVertical", "railsVerticalWater"))
                    method(read(jar,"worldgen/lost/regassets/data/RailwayParts"), m);
                method(read(jar,"worldgen/lost/cityassets/WorldStyle"), "getPartSelector");
                for(String m:List.of("getSlices","getxSize","getzSize","getRefPaletteName")) method(rawPart,m);
                assertTrue(rawPart.methods.containsKey("getLocalPalette()Lmcjty/lostcities/worldgen/lost/regassets/PaletteRE;"));
                if(dimension) {
                    for(String m:List.of("getEffectiveCitySettings","isHierarchicalOpen","isPrimaryRoad","getStreetSlopeDirection")) method(building,m);
                    for(String m:List.of("getLargeStreetParts","getTertiaryStreetParts")) method(style,m);
                    for(String m:List.of("connector","stair")) method(streets,m);
                }
                Api part=read(jar,"worldgen/lost/cityassets/BuildingPart");
                for(String m:List.of("getSlices","getXSize","getZSize","getLocalPalette")) method(part,m);
                Api palette=read(jar,"worldgen/lost/cityassets/CompiledPalette");
                assertTrue(palette.methods.containsKey("get(CLjava/util/Random;)Lnet/minecraft/world/level/block/state/BlockState;"));
                assertTrue(palette.methods.containsKey("<init>([Lmcjty/lostcities/worldgen/lost/cityassets/Palette;)V"));
                method(read(jar,"api/ILostCityInformation"),"getChunkInfo");
                method(read(jar,"api/ILostCityInformation"),"getRealHeight");
            }
            try(var loader=new URLClassLoader(new java.net.URL[]{path.toUri().toURL()},getClass().getClassLoader())) {
                String filename=path.getFileName().toString();
                assertEquals(!filename.contains("7.3.6") && !filename.contains("8.2.6"),
                        LostCityStreetPlan.reselectsStreets(loader),"coordinate street-type reselection contract");
            }
        }));
    }
}
