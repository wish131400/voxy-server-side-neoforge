package dev.xantha.vss.client.prediction;
import com.google.gson.Gson;
import java.nio.file.*;
import java.util.*;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.pools.*;
public final class VanillaBeardReference {
    public static void main(String[] args)throws Exception {
        net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(Path.of("build/tmp/rust-reference"));
        net.neoforged.fml.loading.LoadingModList.of(List.of(),List.of(),List.of(),List.of(),Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        var json=new Gson();int count=0;
        try(var out=Files.newBufferedWriter(Path.of(args[0]))){
            for(var adjustment:TerrainAdjustment.values()){
                var pieces=new ObjectArrayList<Beardifier.Rigid>();var junctions=new ObjectArrayList<JigsawJunction>();
                pieces.add(new Beardifier.Rigid(new BoundingBox(-8,60,-8,8,73,8),adjustment,2));
                pieces.add(new Beardifier.Rigid(new BoundingBox(-21,45,11,-15,60,17),adjustment,-3));
                junctions.add(new JigsawJunction(11,62,-3,0,StructureTemplatePool.Projection.RIGID));
                var beard=new Beardifier(pieces.iterator(),junctions.iterator());
                var input=Map.of("pieces",List.of(Map.of("box",new int[]{-8,60,-8,8,73,8},"ground_delta",2,"adjustment",adjustment.getSerializedName()),Map.of("box",new int[]{-21,45,11,-15,60,17},"ground_delta",-3,"adjustment",adjustment.getSerializedName())),"junctions",List.of(new int[]{11,62,-3}));
                List<Object> samples=new ArrayList<>();
                for(int x=-24;x<=24;x+=3)for(int z=-24;z<=24;z+=3)for(int y=40;y<=84;y+=2){
                    double value=beard.compute(new DensityFunction.SinglePointContext(x,y,z));samples.add(Map.of("pos",new int[]{x,y,z},"bits",Long.toUnsignedString(Double.doubleToRawLongBits(value),16)));count++;
                }
                out.write(json.toJson(Map.of("context",input,"samples",samples)));out.newLine();
            }
        }
        System.out.println("Vanilla structure terrain adjustment values="+count);
    }
}
