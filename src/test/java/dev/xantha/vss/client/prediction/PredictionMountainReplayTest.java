package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.*;
import org.junit.jupiter.api.*;

/** Optional actual-world generator replay. No live world writes or changes to game threads. */
class PredictionMountainReplayTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void actualMountainGenerationAndWarmCacheCost() throws Exception {
        String document=System.getProperty("vss.mountainDocument");
        Assumptions.assumeTrue(document!=null,"Requires the read-only worldgen snapshot");
        long seed=5052304137288917019L;
        assertTrue(RustTerrainSampler.available());
        var doc=JsonParser.parseString(Files.readString(Path.of(document))).getAsJsonObject();
        // The snapshot includes every installed mod's decorative state. This replay only uses
        // terrain; keep its exact density graph, but omit states absent from the test registry.
        doc.remove("input_states");
        doc.getAsJsonObject("block_definitions").entrySet().removeIf(e -> !e.getKey().startsWith("minecraft:"));
        var lookup=net.minecraft.data.registries.VanillaRegistries.createLookup();
        var ops=lookup.createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE);
        var settings=NoiseGeneratorSettings.DIRECT_CODEC.parse(ops,doc.get("settings")).getOrThrow();
        var source=new net.minecraft.world.level.biome.FixedBiomeSource(lookup.lookupOrThrow(Registries.BIOME)
                .getOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS));
        var generator=new net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator(source,net.minecraft.core.Holder.direct(settings));
        var random=RandomState.create(settings,lookup.lookupOrThrow(Registries.NOISE),seed);
        var profile=new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                net.minecraft.resources.ResourceLocation.withDefaultNamespace("overworld"),-64,384,"noise","minecraft:overworld",0);
        var javaSampler=new ClientTerrainSampler(seed,profile,generator,random,
                net.minecraft.world.level.LevelHeightAccessor.create(-64,384),63,List.of());
        try(var rust=new RustTerrainSampler(RustWorldgenBackend.create(seed,0,doc.toString()),profile,javaSampler)) {
            var legacyRust = new ClientTerrainSampler(seed, profile) {
                @Override PredictionColumnVolume exteriorColumn(int x, int z) { return rust.exteriorColumn(x, z); }
            };
            var legacyJava = new ClientTerrainSampler(seed, profile) {
                @Override PredictionColumnVolume exteriorColumn(int x, int z) { return javaSampler.exteriorColumn(x, z); }
            };
            System.out.println("JAVA_EXTERIOR ordered="+new PredictionJavaExterior(generator,random,net.minecraft.world.level.LevelHeightAccessor.create(-64,384)).ordered());
            JsonObject corpus;
            try(var in=getClass().getResourceAsStream("/prediction/mountain-5052304137288917019.json")) {
                corpus=JsonParser.parseReader(new java.io.InputStreamReader(in,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            }
            int air=0;
            for(var value:corpus.getAsJsonArray("columns")) {
                var row=value.getAsJsonObject();int x=row.get("x").getAsInt(),z=row.get("z").getAsInt();
                var nativeColumn=rust.exteriorColumn(x,z);var javaColumn=javaSampler.exteriorColumn(x,z);
                for(var valueGap:row.getAsJsonArray("exteriorGaps")) {
                    var gap=valueGap.getAsJsonArray();
                    for(int y=gap.get(0).getAsInt();y<gap.get(1).getAsInt();y++) {
                        assertFalse(nativeColumn.occupied(y,false),"Rust fills saved mountain air "+x+","+y+","+z);
                        assertFalse(javaColumn.occupied(y,false),"Java fills saved mountain air "+x+","+y+","+z);air++;
                    }
                }
            }
            System.out.println("MOUNTAIN_SAVED_AIR verified="+air+" columns=52 backend=Java+Rust");
            for(int[] position:List.of(new int[]{-4588,-1531},new int[]{1605,-1456})) for(int step:new int[]{1,2,4}) {
                int grid=66,baseX=Math.floorDiv(position[0],64*step)*64*step,baseZ=Math.floorDiv(position[1],64*step)*64*step;
                var original=new ClientColumnSample[grid*grid];
                for(int z=0;z<grid;z++)for(int x=0;x<grid;x++)
                    original[z*grid+x]=rust.sample(baseX+(x-1)*step,baseZ+(z-1)*step);
                ClientColumnSample[] reference = null;
                for(int round=0;round<3;round++) for(var sampler:round==0 ? List.of(legacyRust,rust,legacyJava,javaSampler)
                        : round==1 ? List.of(javaSampler,legacyJava) : List.of(legacyJava,javaSampler)) {
                    var samples=original.clone();long start=System.nanoTime();
                    int changed=PredictionExteriorColumns.enrich(samples,grid,step,baseX,baseZ,sampler,()->true);
                    long elapsed=System.nanoTime()-start;
                    if(reference==null) reference=samples.clone();
                    else assertArrayEquals(reference,samples,"all heights, occupancy runs and flags must match full-column proof");
                    long profiles=Arrays.stream(samples).filter(PredictionExteriorColumns::profiled).count();
                    long bytes=PredictionSampleCompaction.volumeBytes(samples);
                    start=System.nanoTime();int warm=PredictionExteriorColumns.enrich(samples,grid,step,baseX,baseZ,sampler,()->true);
                    long warmNs=System.nanoTime()-start;assertEquals(0,warm);
                    System.out.println("MOUNTAIN_ENRICH backend="+(sampler==legacyRust?"LegacyRust":sampler==legacyJava?"LegacyJava":sampler.getClass().getSimpleName())+" x="+position[0]+" round="+round+" step="+step+" checked="+changed
                            +" multiRun="+profiles+" bytes="+bytes+" coldMs="+elapsed/1e6+" warmMs="+warmNs/1e6);
                    if(step==1)assertTrue(profiles>0,"actual problem tile must receive multi-run geometry");
                    var mesh=PredictionMeshBuilder.build(samples,null,63,0,step,grid,false);
                    System.out.println("MOUNTAIN_MESH step="+step+" vertices="+mesh.vertexCount());
                }
            }
        }
    }
}
