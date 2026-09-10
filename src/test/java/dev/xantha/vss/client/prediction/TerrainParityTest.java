package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import dev.xantha.vss.common.worldgen.DensityFunctionReferences;
import dev.xantha.vss.common.worldgen.DensityFunctionSnapshot;
import java.nio.*;
import java.nio.file.*;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.levelgen.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TerrainParityTest {
    @BeforeAll static void bootstrap() {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        String library = System.getProperty("vss.testNativeLibrary");
        if (library != null) RustWorldgenBackend.load(Path.of(library));
        else assertTrue(RustTerrainSampler.available());
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "vss.liveSnapshot", matches = ".+")
    void liveSnapshotMatchesServerAndNative() throws Exception {
        Path dir=Path.of(System.getProperty("vss.liveSnapshot"));
        var doc=JsonParser.parseString(Files.readString(dir.resolve("minecraft_overworld.json"))).getAsJsonObject();
        var snapshot=JsonParser.parseString(Files.readString(dir.resolve("registries.json"))).getAsJsonObject();
        var base=LithostitchedNativeTest.document();
        for(String key:java.util.List.of("density_functions","noises"))doc.add(key,snapshot.get(key));
        for(String key:java.util.List.of("biomes","block_definitions","configured_features","placed_features","grass_colormap","foliage_colormap"))doc.add(key,base.get(key));
        doc.add("biome_source",base.get("biome_source"));
        // These root-level aliases are what the new server snapshot exports.
        var method=Class.forName("dev.xantha.vss.networking.server.session.WorldgenCodecSnapshot")
                .getDeclaredMethod("noiseSeedAliases",java.util.Set.class,boolean.class);
        method.setAccessible(true);
        doc.add("noise_seed_aliases",(JsonObject)method.invoke(null,doc.getAsJsonObject("noises").keySet().stream()
                .map(net.minecraft.resources.ResourceLocation::parse).collect(java.util.stream.Collectors.toSet()),true));
        long seed=Long.parseLong(Files.readString(dir.resolve("minecraft_overworld-seed.txt")).trim());
        var input=ByteBuffer.allocateDirect(12).order(ByteOrder.LITTLE_ENDIAN);
        var output=ByteBuffer.allocateDirect(4096*4+16).order(ByteOrder.LITTLE_ENDIAN);
        long world=RustWorldgenBackend.create(seed,0,doc.toString());
        var baselineDoc=doc.deepCopy();baselineDoc.remove("noise_seed_aliases");
        long baseline=RustWorldgenBackend.create(seed,0,baselineDoc.toString());
        int densities=0,columns=0,oldHeightDifferences=0;
        var densityLine=java.util.regex.Pattern.compile("DENSITY \\[(-?\\d+), (-?\\d+)\\] y=(-?\\d+) (\\w+) server=([^ ]+).*");
        var columnLine=java.util.regex.Pattern.compile("COLUMN \\[(-?\\d+), (-?\\d+)\\] serverBase=(-?\\d+).*");
        try {
            for(String line:Files.readAllLines(dir.resolve("height-oracle.txt"))) {
                var density=densityLine.matcher(line);var column=columnLine.matcher(line);
                if(density.matches()) {
                    input.putInt(0,Integer.parseInt(density.group(1))).putInt(4,Integer.parseInt(density.group(3))).putInt(8,Integer.parseInt(density.group(2)));
                    String root=switch(density.group(4)){case "finalDensity"->"final_density";case "initialDensityWithoutJaggedness"->"initial_density_without_jaggedness";default->density.group(4);};
                    RustWorldgenBackend.density(world,root,input,output,1);
                    assertEquals(Double.parseDouble(density.group(5)),output.getDouble(0),1e-10,line);
                    densities++;
                } else if(column.matches()) {
                    input.putInt(0,Integer.parseInt(column.group(1))).putInt(4,Integer.parseInt(column.group(2)));
                    RustWorldgenBackend.columns(world,input,output,1);
                    int fixed=output.getInt(4),expected=Integer.parseInt(column.group(3));
                    assertEquals(expected,fixed,line);
                    RustWorldgenBackend.columns(baseline,input,output,1);
                    int old=output.getInt(4);
                    if(old!=expected)oldHeightDifferences++;
                    System.out.printf("COLUMN_PARITY x=%s z=%s server=%d old=%d fixed=%d%n",column.group(1),column.group(2),expected,old,fixed);
                    columns++;
                }
            }
        } finally {RustWorldgenBackend.close(world);RustWorldgenBackend.close(baseline);}
        assertTrue(densities>=100);assertTrue(columns>=5);assertTrue(oldHeightDifferences>0);
        System.out.println("LIVE_SERVER_PARITY densities="+densities+" columns="+columns);
    }

    @Test void vanillaAndCompactedSnapshotMatchMinecraftColumns() throws Exception {
        check(LithostitchedNativeTest.document(), "vanilla");
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "vss.tectonicJar", matches = ".+")
    void tectonicPackMatchesMinecraftColumns() throws Exception {
        var doc = LithostitchedNativeTest.document();
        try (var zip = new java.util.zip.ZipFile(System.getProperty("vss.tectonicJar"))) {
            var urls = new java.util.ArrayList<java.net.URL>();
            urls.add(Path.of(System.getProperty("vss.tectonicJar")).toUri().toURL());
            for (var entry : java.util.Collections.list(zip.entries())) {
                if (!entry.getName().startsWith("META-INF/jarjar/") || !entry.getName().endsWith(".jar")) continue;
                Path nested = Path.of("build/dev-comparison/tectonic-dependencies", Path.of(entry.getName()).getFileName().toString());
                Files.createDirectories(nested.getParent());
                try (var input = zip.getInputStream(entry)) {
                    Files.copy(input, nested, StandardCopyOption.REPLACE_EXISTING);
                }
                urls.add(nested.toUri().toURL());
            }
            try (var loader = new java.net.URLClassLoader(urls.toArray(java.net.URL[]::new), TerrainParityTest.class.getClassLoader())) {
            Object config;
            try {
                config = loader.loadClass("dev.worldgen.tectonic.config.ConfigState").getField("DEFAULT_STATE").get(null);
            } catch (ClassNotFoundException legacyRelease) {
                config = loader.loadClass("dev.worldgen.tectonic.config.ConfigHandler").getMethod("getState").invoke(null);
            }
            registerCodec(loader,"Invert","invert");
            try {
                loader.loadClass("dev.worldgen.tectonic.worldgen.densityfunction.ConfigClamp");
                registerCodec(loader,"ConfigClamp","config_clamp");
            } catch (ClassNotFoundException legacyRelease) {
                // Earlier datapacks do not use configurable clamps.
            }
            for (String prefix : new String[]{"resourcepacks/tectonic/data/", "resourcepacks/tectonic/overlay.mod/data/"}) {
            for (var entry : java.util.Collections.list(zip.entries())) {
                String path = entry.getName();
                if (!path.startsWith(prefix) || !path.endsWith(".json")) continue;
                String[] parts = path.substring(prefix.length()).split("/", 4);
                if (parts.length != 4 || !parts[1].equals("worldgen")) continue;
                String field = switch(parts[2]) {case "density_function" -> "density_functions"; case "noise" -> "noises"; default -> null;};
                try (var reader = new java.io.InputStreamReader(zip.getInputStream(entry), java.nio.charset.StandardCharsets.UTF_8)) {
                    if (field != null) doc.getAsJsonObject(field).add(parts[0] + ":" + parts[3].replaceFirst("\\.json$", ""), normalizeConfig(JsonParser.parseReader(reader),config));
                    else if (parts[0].equals("minecraft") && parts[2].equals("noise_settings") && parts[3].equals("overworld.json")) doc.add("settings", JsonParser.parseReader(reader));
                }
            }
            }
            // This field is consumed only by newer Minecraft versions.
            doc.getAsJsonObject("settings").getAsJsonObject("noise_router").remove("preliminary_surface_level");
            check(doc, "tectonic");
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void registerCodec(ClassLoader loader,String name,String id) throws Exception {
        var registry = net.minecraft.core.registries.BuiltInRegistries.DENSITY_FUNCTION_TYPE;
        var key = net.minecraft.resources.ResourceLocation.parse("tectonic:"+id);
        if (!registry.containsKey(key)) {
            var frozen = net.minecraft.core.MappedRegistry.class.getDeclaredField("frozen");
            frozen.setAccessible(true);
            boolean previous = frozen.getBoolean(registry);
            frozen.setBoolean(registry,false);
            try { net.minecraft.core.Registry.register(registry,key,
                    (com.mojang.serialization.MapCodec)loader.loadClass("dev.worldgen.tectonic.worldgen.densityfunction."+name).getField("DATA_CODEC").get(null)); }
            finally { frozen.setBoolean(registry,previous); }
        }
    }

    private static JsonElement normalizeConfig(JsonElement value,Object config) throws Exception {
        if (value.isJsonArray()) {
            var result = new JsonArray();
            for (var child : value.getAsJsonArray()) result.add(normalizeConfig(child,config));
            return result;
        }
        if (!value.isJsonObject()) return value;
        var object = value.getAsJsonObject();
        String type = object.has("type") ? object.get("type").getAsString() : "";
        if (type.equals("tectonic:config_constant")) return new JsonPrimitive((Double)config.getClass()
                .getMethod("getValue",String.class).invoke(config,object.get("key").getAsString()));
        if (type.equals("tectonic:config_noise")) {
            Object state = config.getClass().getMethod("getNoiseState",String.class).invoke(config,object.get("key").getAsString());
            try {
                assertFalse(state.getClass().getField("smootherScaling").getBoolean(state));
            } catch (NoSuchFieldException legacyRelease) {
                // Older releases only implement ordinary shifted noise scaling.
            }
            var noise = new JsonObject(); noise.addProperty("type","minecraft:shifted_noise");
            noise.add("noise",object.get("noise")); noise.add("shift_x",object.get("shift_x")); noise.add("shift_z",object.get("shift_z"));
            noise.addProperty("shift_y",0); noise.addProperty("y_scale",0); noise.addProperty("xz_scale",state.getClass().getField("scale").getDouble(state));
            var mul = new JsonObject(); mul.addProperty("type","minecraft:mul");mul.add("argument1",noise);mul.addProperty("argument2",state.getClass().getField("multiplier").getDouble(state));
            var add = new JsonObject(); add.addProperty("type","minecraft:add");add.add("argument1",mul);add.addProperty("argument2",state.getClass().getField("offset").getDouble(state));
            return add;
        }
        var result = new JsonObject();
        for (var entry : object.entrySet()) result.add(entry.getKey(),normalizeConfig(entry.getValue(),config));
        return result;
    }

    static void check(JsonObject doc, String name) throws Exception {
        var registries = ClientWorldgenRegistries.decode(doc, RegistryAccess.EMPTY);
        var settings = NoiseGeneratorSettings.DIRECT_CODEC.parse(registries.ops(), doc.get("settings")).getOrThrow();
        var lookup = net.minecraft.data.registries.VanillaRegistries.createLookup();
        var biome = lookup.lookupOrThrow(net.minecraft.core.registries.Registries.BIOME)
                .getOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS);
        var generator = new NoiseBasedChunkGenerator(new FixedBiomeSource(biome), net.minecraft.core.Holder.direct(settings));
        long seed = 1011965752357175303L;
        var random = RandomState.create(settings, registries.noiseLookup(), seed);
        var heights = LevelHeightAccessor.create(settings.noiseSettings().minY(), settings.noiseSettings().height());
        var references = new DensityFunctionReferences();
        var compact = doc.deepCopy();
        compact.add("settings", references.settings(NoiseGeneratorSettings.DIRECT_CODEC.encodeStart(registries.ops(),
                DensityFunctionSnapshot.applied(settings)).getOrThrow().getAsJsonObject()));
        for (var e : references.definitions().entrySet()) compact.getAsJsonObject("density_functions").add(e.getKey(), e.getValue());
        Files.createDirectories(Path.of("build/dev-comparison"));
        Files.writeString(Path.of("build/dev-comparison/" + name + "-document.json"), compact.toString());
        for (var version : new JsonObject[]{compact}) {
            long world = RustWorldgenBackend.create(seed, net.minecraft.world.level.biome.BiomeManager.obfuscateSeed(seed), version.toString());
            try {
                var input = ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN);
                var output = ByteBuffer.allocateDirect((4 + heights.getHeight()) * 4).order(ByteOrder.LITTLE_ENDIAN);
                var densityInput = ByteBuffer.allocateDirect(12).order(ByteOrder.LITTLE_ENDIAN);
                var densityOutput = ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN);
                for (int[] p : new int[][]{{71,217},{-17,35},{256,256},{-128,1024},{777,-919}}) {
                    for (int y : new int[]{0,64,128,192,256}) {
                        densityInput.putInt(0,p[0]).putInt(4,y).putInt(8,p[1]);
                        RustWorldgenBackend.density(world,"final_density",densityInput,densityOutput,1);
                        double expected = random.router().finalDensity().compute(new DensityFunction.SinglePointContext(p[0],y,p[1]));
                        assertEquals(expected,densityOutput.getDouble(0),1e-10,name+" density " + java.util.Arrays.toString(p)+" y="+y);
                    }
                    input.putInt(0,p[0]).putInt(4,p[1]);
                    long start = System.nanoTime();
                    RustWorldgenBackend.columns(world,input,output,1);
                    double ms = (System.nanoTime()-start)/1e6;
                    var expected = generator.getBaseColumn(p[0],p[1],heights,random);
                    int floor = heights.getMinBuildHeight();
                    for (int y = heights.getMaxBuildHeight()-1;y >= heights.getMinBuildHeight();y--) {
                        var block = expected.getBlock(y);
                        if (!block.isAir() && block.getFluidState().isEmpty()) {floor=y+1;break;}
                    }
                    System.out.printf("%s compact=%s x=%d z=%d JavaFloor=%d RustFloor=%d ms=%.3f%n",name,version==compact,p[0],p[1],floor,output.getInt(4),ms);
                    assertEquals(floor,output.getInt(4),name+" base column");
                }
                var sparseInput = ByteBuffer.allocateDirect(64*8).order(ByteOrder.LITTLE_ENDIAN);
                var sparseOutput = ByteBuffer.allocateDirect(64*40).order(ByteOrder.LITTLE_ENDIAN);
                var times = new java.util.ArrayList<Double>();
                long checksum = 0;
                for (int pass=0;pass<6;pass++) {
                    for (int i=0;i<64;i++) sparseInput.putInt(i*8,pass*4096+i%8*32).putInt(i*8+4,-pass*4096+i/8*32);
                    long start=System.nanoTime();
                    assertEquals(64,RustWorldgenBackend.surfacePoints(world,sparseInput,sparseOutput,64));
                    if(pass>0) times.add((System.nanoTime()-start)/1e6);
                    for(int i=0;i<640;i++) checksum=checksum*31+sparseOutput.getInt(i*4);
                }
                java.util.Collections.sort(times);
                System.out.printf("SURFACE_BENCH %s median64Ms=%.3f checksum=%d%n",name,times.get(2),checksum);
            } finally { RustWorldgenBackend.close(world); }
        }
        for (int axis : new int[]{16,8}) {
            long world = RustWorldgenBackend.create(seed,net.minecraft.world.level.biome.BiomeManager.obfuscateSeed(seed),compact.toString());
            try {
                int grid=axis+2, spacing=4096/axis;
                var input=ByteBuffer.allocateDirect(64*8).order(ByteOrder.LITTLE_ENDIAN);
                var output=ByteBuffer.allocateDirect(64*40).order(ByteOrder.LITTLE_ENDIAN);
                long start=System.nanoTime();
                for(int index=0;index<grid*grid;) {
                    input.clear();int count=0;
                    while(index<grid*grid && count<64) {
                        input.putInt(8192+(index%grid-1)*spacing).putInt(-8192+(index/grid-1)*spacing);index++;count++;
                    }
                    assertEquals(count,RustWorldgenBackend.surfacePoints(world,input,output,count));
                }
                System.out.printf("FIRST_COVERAGE_BENCH %s axis=%d points=%d ms=%.3f%n",name,axis,grid*grid,(System.nanoTime()-start)/1e6);
            } finally { RustWorldgenBackend.close(world); }
        }
    }
}
