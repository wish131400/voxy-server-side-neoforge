package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.structure.*;
import org.junit.jupiter.api.*;

class PredictionNetherStructuresTest {
    @BeforeAll static void bootstrap() { PredictionNetherDecorationTest.bootstrap(); }
    @AfterAll static void restore() { PredictionNetherDecorationTest.restore(); }

    @Test void actualFortressAndBastionPiecesSurviveOnJavaAndNativeTerrain() throws Exception {
        assertTrue(RustTerrainSampler.available());
        var lookup=PredictionNetherDecorationTest.lookup;
        var ops=lookup.createSerializationContext(JsonOps.INSTANCE);
        var sourceDoc=PredictionNetherDecorationTest.nativeDocument("minecraft:nether_wastes");
        var root=new JsonObject();root.add("noises",new JsonObject());root.add("density_functions",new JsonObject());
        var biome=Biome.DIRECT_CODEC.encodeStart(ops,lookup.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.NETHER_WASTES).value()).getOrThrow().getAsJsonObject();
        biome.add("features",new JsonArray());biome.add("carvers",new JsonObject());
        var biomes=new JsonObject();biomes.add("minecraft:nether_wastes",biome);biomes.add("minecraft:plains",biome.deepCopy());root.add("biomes",biomes);
        var pools=new JsonObject();
        lookup.lookupOrThrow(Registries.TEMPLATE_POOL).listElements().filter(h->h.key().location().getPath().startsWith("bastion/")||h.key().location().getPath().equals("empty"))
                .forEach(h->pools.add(h.key().location().toString(),net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool.DIRECT_CODEC.encodeStart(ops,h.value()).getOrThrow()));
        root.add("template_pools",pools);
        var processors=new JsonObject();
        lookup.lookupOrThrow(Registries.PROCESSOR_LIST).listElements().filter(h->pools.toString().contains("\""+h.key().location()+"\"")).forEach(h->processors.add(h.key().location().toString(),
                net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType.DIRECT_CODEC.encodeStart(ops,h.value()).getOrThrow()));
        root.add("processor_lists",processors);
        var templates=loadBastionTemplates();root.add("structure_templates",templates);
        for(var structureKey:List.of(BuiltinStructures.FORTRESS,BuiltinStructures.BASTION_REMNANT)) {
            String name=structureKey.location().toString();
            var structure=Structure.DIRECT_CODEC.encodeStart(ops,lookup.lookupOrThrow(Registries.STRUCTURE).getOrThrow(structureKey).value()).getOrThrow().getAsJsonObject();
            structure.add("biomes",JsonParser.parseString("[\"minecraft:nether_wastes\"]"));
            var structures=new JsonObject();structures.add(name,structure);root.add("structures",structures);
            root.add("structure_sets",JsonParser.parseString("{\"vss:test\":{\"structures\":[{\"structure\":\""+name+"\",\"weight\":1}],"
                    +"\"placement\":{\"type\":\"minecraft:random_spread\",\"spacing\":32,\"separation\":8,\"salt\":30084232}}}"));
            var previousDebug=dev.xantha.vss.config.VSSClientConfig.CONFIG.debugLogging;
            RegistryAccess access;
            try { dev.xantha.vss.config.VSSClientConfig.CONFIG.debugLogging=true;
                access=ClientWorldgenRegistries.decode(root,RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)).access();
            } finally {dev.xantha.vss.config.VSSClientConfig.CONFIG.debugLogging=previousDebug;}
            var settings=lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.NETHER);
            var source=new FixedBiomeSource(access.registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.NETHER_WASTES));
            var generator=new NoiseBasedChunkGenerator(source,settings);
            var random=RandomState.create(settings.value(),lookup.lookupOrThrow(Registries.NOISE),42);
            var profile=new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(Level.NETHER.location(),42,0,256,"noise","minecraft:nether",1);
            var javaTerrain=new ClientTerrainSampler(42,profile,generator,random,LevelHeightAccessor.create(0,256),32,List.of(),null,access) {
                @Override JsonObject structureTemplates() { return templates; }
            };
            try(var nativeTerrain=new RustTerrainSampler(RustWorldgenBackend.create(42,0,sourceDoc.toString()),profile,javaTerrain)) {
                var expected=place(javaTerrain,javaTerrain,access);
                var actual=place(nativeTerrain,javaTerrain,access);
                assertTrue(expected.size()>100,name+" must place a real building");
                assertEquals(expected,actual,name+" layout and all air/solid edits must agree between backends");
                assertTrue(actual.values().stream().anyMatch(BlockState::isAir),name+" interior cuts must persist");
                assertTrue(actual.values().stream().anyMatch(s->s.is(Blocks.NETHER_BRICKS)||s.is(Blocks.POLISHED_BLACKSTONE_BRICKS)||s.is(Blocks.BLACKSTONE)),name);
                System.out.println("NETHER_STRUCTURE_PARITY "+name+" writes="+actual.size());
            }
        }
    }

    private static Map<BlockPos,BlockState> place(ClientTerrainSampler terrain,ClientTerrainSampler context,RegistryAccess access) {
        var structures=new PredictionSurfaceStructures(context);
        assertTrue(structures.available(),structures.diagnostics());
        var spread=(net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement)access.registryOrThrow(Registries.STRUCTURE_SET).iterator().next().placement();
        var candidate=spread.getPotentialStructureChunk(42,0,0);
        var all=new HashMap<BlockPos,BlockState>();
        for(int x=candidate.x-1;x<=candidate.x+2;x++) for(int z=candidate.z-1;z<=candidate.z+2;z++) {
            var level=new PredictionDecorationLevel(terrain,context,access,x,z);
            structures.place(level,x,z,0,access.registryOrThrow(Registries.STRUCTURE).iterator().next().step().ordinal());
            all.putAll(PredictionVegetation.surfaceBlocks(level));
        }
        assertTrue(structures.diagnostics().contains("skippedStructures=0,"),structures.diagnostics());
        return all;
    }

    private static JsonObject loadBastionTemplates() throws Exception {
        var result=new JsonObject();
        var probe=PredictionNetherStructuresTest.class.getResource("/data/minecraft/structure/village/plains/houses/plains_small_house_1.nbt");
        assertNotNull(probe);
        if(probe.openConnection() instanceof java.net.JarURLConnection connection) {
            var jar=connection.getJarFile();
            for(var entry:Collections.list(jar.entries())) {
                String prefix="data/minecraft/structure/";
                if(entry.getName().startsWith(prefix+"bastion/")&&entry.getName().endsWith(".nbt")) {
                    try(var input=jar.getInputStream(entry)) {
                        result.addProperty("minecraft:"+entry.getName().substring(prefix.length(),entry.getName().length()-4),Base64.getEncoder().encodeToString(input.readAllBytes()));
                    }
                }
            }
        } else {
            var base=java.nio.file.Path.of(probe.toURI()).getParent().getParent().getParent().getParent();
            try(var paths=java.nio.file.Files.walk(base.resolve("bastion"))) {
                for(var path:paths.filter(p->p.toString().endsWith(".nbt")).toList()) {
                    String id=base.relativize(path).toString().replace('\\','/');
                    result.addProperty("minecraft:"+id.substring(0,id.length()-4),Base64.getEncoder().encodeToString(java.nio.file.Files.readAllBytes(path)));
                }
            }
        }
        assertTrue(result.size()>20,"load original bastion templates from Minecraft dependency");
        return result;
    }
}
