package dev.xantha.vss.client.prediction;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import dev.xantha.vss.client.prediction.feature.FeatureStampLevel;

/** Controls only Collections.shuffle's independent entropy, then calls vanilla's decorator. */
public final class VanillaBeehiveReference {
    public static void main(String[] args)throws Exception {
        net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(Path.of("build/tmp/rust-reference"));
        net.neoforged.fml.loading.LoadingModList.of(List.of(),List.of(),List.of(),List.of(),Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        RustReferenceMain.loadTags();RustReferenceMain.loadColors();
        var root=Path.of(args[0]);var lookup=VanillaRegistries.createLookup();
        var generator=new NoiseBasedChunkGenerator(new FixedBiomeSource(lookup.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS)),lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD));
        var tree=VanillaWorldgenReference.resource("worldgen/configured_feature/oak").getAsJsonObject();
        tree.getAsJsonObject("config").add("decorators",JsonParser.parseString("[{\"type\":\"minecraft:beehive\",\"probability\":1.0}]"));
        var feature=ConfiguredFeature.DIRECT_CODEC.parse(JsonOps.INSTANCE,tree).getOrThrow();
        var field=Collections.class.getDeclaredField("r");field.setAccessible(true);
        int count=0;
        try(var out=Files.newBufferedWriter(root.resolve("beehive.jsonl"))) {
            for(long seed:new long[]{0,-1,693280690516334765L})for(long shuffle:new long[]{0,42,-987654321L}) {
                field.set(null,new Random(shuffle));
                var level=new FeatureStampLevel(seed,RegistryAccess.EMPTY,Blocks.GRASS_BLOCK.defaultBlockState());
                var r=VanillaKernelReference.random(seed,3);
                boolean placed=feature.place(level,generator,r,new BlockPos(0,64,0));
                out.write(new Gson().toJson(Map.of("feature",tree,"seed",Long.toString(seed),"entropy",Long.toString(shuffle),"placed",placed,"after",Long.toString(r.nextLong()),"output",VanillaVegetationReference.blocks(level.placed()))));out.newLine();count++;
            }
        }
        VanillaSurfaceReference.exportStates(root,"beehive-states.json");
        System.out.println("Direct vanilla bee nest fixtures with explicit shuffle entropy="+count);
    }
}
