package dev.xantha.vss.client.prediction;

import com.google.gson.GsonBuilder;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Correctness fixtures in the real decoded prediction environment; not a performance benchmark. */
final class DiskModuleFixtures {
    static void run(Path output, RustTerrainSampler sampler, ClientTerrainSampler context) throws Exception {
        var report = new ArrayList<Map<String, Object>>();
        for (String name : List.of("disk_sand", "disk_gravel", "disk_clay", "disk_grass", "ice_patch")) {
            var placed = context.decorationAccess().registryOrThrow(Registries.PLACED_FEATURE)
                    .get(ResourceLocation.parse("minecraft:"+name));
            if (placed == null) throw new AssertionError("Missing actual registry feature "+name);
            var configured = placed.feature().value();
            for (int sign : new int[]{-1, 1}) for (int scenario=0; scenario<3; scenario++) for (int seed=0; seed<4; seed++) {
                int cx=sign*17, cz=sign*23;
                var origin=new BlockPos(cx*16+15,64,cz*16+15);
                var reference=prepare(sampler,context,cx,cz,origin,name,scenario);
                RandomSource expected=RandomSource.create(seed);
                reference.beginFeature();
                configured.place(reference,context.generatorContext(),expected,origin);
                configured.place(reference,context.generatorContext(),expected,origin);
                long tail=expected.nextLong();
                reference.endFeature(true);
                for (String mode:List.of("templates","pages","compact")) {
                    var actual=prepare(sampler,context,cx,cz,origin,name,scenario);
                    RandomSource random=RandomSource.create(seed);
                    DiskModuleExperiment.begin(mode);
                    actual.beginFeature();
                    for(int repeat=0;repeat<2;repeat++)
                        if(!DiskModuleExperiment.tryPlace(placed,actual,context.generatorContext(),random,origin))
                            throw new AssertionError("Fixture did not enter module "+name);
                    long actualTail=random.nextLong();
                    actual.endFeature(true);
                    if(!reference.placed().equals(actual.placed()) || tail!=actualTail
                            || !new ArrayList<>(reference.pendingUploads().entrySet()).equals(
                                    new ArrayList<>(actual.pendingUploads().entrySet())))
                        throw new AssertionError("Disk fixture mismatch "+name+" "+sign+" "+scenario+" "+seed+" "+mode);
                    report.add(Map.of("feature",name,"sign",sign,"scenario",scenario,"seed",seed,
                            "mode",mode,"moduleEntered",true,"matched",true));
                }
            }
        }
        Files.writeString(output.resolve("module-fixtures.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));
    }

    private static PredictionDecorationLevel prepare(RustTerrainSampler sampler,ClientTerrainSampler context,
            int cx,int cz,BlockPos origin,String name,int scenario) {
        var level=new PredictionDecorationLevel(sampler,context,context.decorationAccess(),cx,cz);
        BlockState soil=name.equals("disk_grass")?Blocks.MUD.defaultBlockState():
                name.equals("ice_patch")?Blocks.SNOW_BLOCK.defaultBlockState():Blocks.DIRT.defaultBlockState();
        for(int z=-7;z<=7;z++)for(int x=-7;x<=7;x++)for(int y=-4;y<=4;y++) {
            BlockState state=y<=0?soil:Blocks.AIR.defaultBlockState();
            if(scenario==1) {
                if((x+z)%3==0&&y==-1)state=Blocks.AIR.defaultBlockState();
                if(x==0&&y==1)state=Blocks.WATER.defaultBlockState();
                if(z==0&&y==0)state=Blocks.STONE.defaultBlockState();
            } else if(scenario==2&&y<=0)state=Blocks.STONE.defaultBlockState();
            level.setBlock(origin.offset(x,y,z),state,2);
        }
        return level;
    }
}
