package dev.xantha.vss.client.prediction;
import com.google.gson.*;
import com.mojang.serialization.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.*;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blending.Blender;

/** Real SurfaceSystem execution on complete synthetic columns, and real biome colors. */
public final class VanillaSurfaceReference {
    static final Gson JSON=new Gson();
    static final Map<BlockState,Integer> STATE_IDS=new LinkedHashMap<>();
    static DensityFunctions.BeardifierOrMarker empty(){return new DensityFunctions.BeardifierOrMarker(){
        public double compute(DensityFunction.FunctionContext p){return 0;}
        public double minValue(){return 0;}public double maxValue(){return 0;}
        public KeyDispatchDataCodec<? extends DensityFunction> codec(){return DensityFunctions.constant(0).codec();}
    };}
    static List<int[]> runs(ProtoChunk chunk,int min,int height){
        List<int[]> result=new ArrayList<>();int old=-1,count=0;
        for(int x=0;x<16;x++)for(int z=0;z<16;z++)for(int y=min;y<min+height;y++){
            var state=chunk.getBlockState(new BlockPos(chunk.getPos().getMinBlockX()+x,y,chunk.getPos().getMinBlockZ()+z));
            int id=STATE_IDS.computeIfAbsent(state,k->STATE_IDS.size());
            if(id==old){count++;}else{if(count>0)result.add(new int[]{count,old});old=id;count=1;}
        }if(count>0)result.add(new int[]{count,old});return result;
    }
    static void exportBlocks(Path root) throws Exception {
        Files.writeString(root.resolve("blocks.json"),JSON.toJson(RustWorldgenDocument.blockDefinitions()));
    }
    static <T extends Comparable<T>> String propertyValue(BlockState state,net.minecraft.world.level.block.state.properties.Property<T> p){return p.getName(state.getValue(p));}
    static <T extends Comparable<T>> List<String> propertyNames(net.minecraft.world.level.block.state.properties.Property<T> p){return p.getPossibleValues().stream().map(p::getName).toList();}
    static void exportStates(Path root,String name) throws Exception {Files.writeString(root.resolve(name),JSON.toJson(STATE_IDS.keySet().stream().map(s->BlockState.CODEC.encodeStart(JsonOps.INSTANCE,s).getOrThrow()).toList()));}
    public static void main(String[] args)throws Exception{
        net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(Path.of("build/tmp/rust-reference"));
        net.neoforged.fml.loading.LoadingModList.of(List.of(),List.of(),List.of(),List.of(),Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();RustReferenceMain.loadTags();RustReferenceMain.loadColors();
        var root=Path.of(args[0]);Files.createDirectories(root);exportBlocks(root);
        var lookup=VanillaRegistries.createLookup();var biomeRegistry=new MappedRegistry<Biome>(Registries.BIOME,Lifecycle.stable());
        lookup.lookupOrThrow(Registries.BIOME).listElements().forEach(h->biomeRegistry.register(h.key(),h.value(),RegistrationInfo.BUILT_IN));biomeRegistry.freeze();
        var biomeJson=new JsonObject();for(var h:lookup.lookupOrThrow(Registries.BIOME).listElements().toList())biomeJson.add(h.key().location().toString(),VanillaWorldgenReference.resource("worldgen/biome/"+h.key().location().getPath()));
        Files.writeString(root.resolve("biomes.json"),JSON.toJson(biomeJson));
        for(var name:List.of("grass","foliage")){try(var in=VanillaSurfaceReference.class.getResourceAsStream("/assets/minecraft/textures/colormap/"+name+".png")){
            var image=javax.imageio.ImageIO.read(in);Files.writeString(root.resolve(name+".json"),JSON.toJson(Arrays.stream(image.getRGB(0,0,256,256,null,0,256)).mapToLong(Integer::toUnsignedLong).toArray()));}}
        try(var out=Files.newBufferedWriter(root.resolve("colors.jsonl"))){
            var random=new Random(20260909);for(var h:lookup.lookupOrThrow(Registries.BIOME).listElements().toList())for(int i=0;i<48;i++){
                var b=h.value();int x=random.nextInt(60000000)-30000000,z=random.nextInt(60000000)-30000000,y=random.nextInt(384)-64;var p=new BlockPos(x,y,z);
                out.write(JSON.toJson(Map.of("biome",h.key().location().toString(),"pos",new int[]{x,y,z},"grass",Integer.toUnsignedLong(b.getGrassColor(x,z)),"foliage",Integer.toUnsignedLong(b.getFoliageColor()),"water",b.getWaterColor(),"cold",b.coldEnoughToSnow(p),"melt",b.shouldMeltFrozenOceanIcebergSlightly(p))));out.newLine();
            }
        }
        try(var out=Files.newBufferedWriter(root.resolve("surface.jsonl"))){
            for(var name:List.of("plains","desert","badlands","eroded_badlands","wooded_badlands","snowy_plains","frozen_ocean","deep_frozen_ocean","swamp","mangrove_swamp","windswept_hills","windswept_gravelly_hills","jungle","bamboo_jungle","stony_peaks","frozen_peaks","mushroom_fields","nether_wastes","soul_sand_valley","basalt_deltas","the_end")){
                String setting=List.of("nether_wastes","soul_sand_valley","basalt_deltas").contains(name)?"nether":name.equals("the_end")?"end":"overworld";
                var settings=lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(ResourceKey.create(Registries.NOISE_SETTINGS,ResourceLocation.withDefaultNamespace(setting)));
                var h=lookup.lookupOrThrow(Registries.BIOME).getOrThrow(ResourceKey.create(Registries.BIOME,ResourceLocation.withDefaultNamespace(name)));
                var gen=new NoiseBasedChunkGenerator(new FixedBiomeSource(h),settings);var ns=settings.value().noiseSettings();var heights=LevelHeightAccessor.create(ns.minY(),ns.height());
                for(long seed:new long[]{0,693280690516334765L}){
                    var state=RandomState.create(settings.value(),lookup.lookupOrThrow(Registries.NOISE),seed);var cp=new ChunkPos(-13,17);
                    var chunk=new ProtoChunk(cp,UpgradeData.EMPTY,heights,biomeRegistry,null);
                    for(int x=0;x<16;x++)for(int z=0;z<16;z++){
                        int top=40+x*5+z%3;
                        for(int y=ns.minY();y<Math.max(top,settings.value().seaLevel());y++){
                            BlockState block=y<top?settings.value().defaultBlock():settings.value().defaultFluid();
                            if(y>5&&y<10+x/3)block=Blocks.AIR.defaultBlockState();
                            chunk.setBlockState(new BlockPos(cp.getMinBlockX()+x,y,cp.getMinBlockZ()+z),block,false);
                        }
                    }
                    Heightmap.primeHeightmaps(chunk,EnumSet.of(Heightmap.Types.WORLD_SURFACE_WG,Heightmap.Types.OCEAN_FLOOR_WG));
                    var input=runs(chunk,ns.minY(),ns.height());
                    Aquifer.FluidPicker picker=(x,y,z)->y<Math.min(-54,settings.value().seaLevel())?new Aquifer.FluidStatus(-54,Blocks.LAVA.defaultBlockState()):new Aquifer.FluidStatus(settings.value().seaLevel(),settings.value().defaultFluid());
                    var noise=NoiseChunk.forChunk(chunk,state,empty(),settings.value(),picker,Blender.empty());
                    state.surfaceSystem().buildSurface(state,new BiomeManager((x,y,z)->h,0),biomeRegistry,settings.value().useLegacyRandomSource(),new WorldGenerationContext(gen,heights),chunk,noise,settings.value().surfaceRule());
                    out.write(JSON.toJson(Map.of("settings",setting,"biome","minecraft:"+name,"seed",Long.toString(seed),"origin",new int[]{cp.getMinBlockX(),ns.minY(),cp.getMinBlockZ()},"height",ns.height(),"input",input,"output",runs(chunk,ns.minY(),ns.height()))));out.newLine();
                }
            }
        }
        exportStates(root,"surface-states.json");System.out.println("Direct vanilla surface rows=42; all biome color cases exported");
    }
}
