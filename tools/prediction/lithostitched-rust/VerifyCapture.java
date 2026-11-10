package dev.xantha.vss.client.prediction;
import com.google.gson.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.io.*;
import java.security.MessageDigest;

/** Offline graph/JNI replay. The isolated surface fixture below is never packaged in the mod. */
public class VerifyCapture {
    static JsonObject read(Path p) throws Exception {return JsonParser.parseString(Files.readString(p)).getAsJsonObject();}
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]);RustWorldgenBackend.load(Path.of(args[1]));
        var manifest=read(root.resolve("manifest.json"));
        var generator=read(root.resolve("generator.json"));
        if(PredictionWorldgenCapabilities.nativeTerrainRejection(generator,read(root.resolve("registries.json")))!=null)
            throw new AssertionError("Actual capability gate still rejects capture");
        var doc=RustBiomeSourceProjection.generator(generator);
        var registries=read(root.resolve("registries.json"));
        for(String key:List.of("density_functions","noises","biomes","configured_features","placed_features","custom_registries","registry_tags"))
            doc.add(key,registries.get(key));
        doc.add("block_definitions",read(root.resolve("block-definitions.json")));
        doc.addProperty("vss_java_surface_dimension",manifest.get("dimension").getAsString());
        for(String key:List.of("grass","foliage")) {
            var image=javax.imageio.ImageIO.read(root.resolve(key+"-colormap.png").toFile());var values=new JsonArray();
            for(int rgb:image.getRGB(0,0,256,256,null,0,256))values.add(Integer.toUnsignedLong(rgb));
            doc.add(key+"_colormap",values);
        }
        long seed=manifest.get("seed").getAsLong();
        byte[] hashed=MessageDigest.getInstance("SHA-256").digest(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(seed).array());
        long zoom=ByteBuffer.wrap(hashed).order(ByteOrder.LITTLE_ENDIAN).getLong();
        long world=RustWorldgenBackend.create(seed,zoom,doc.toString());
        try {
            var rows=new ArrayList<JsonObject>();
            try(var reader=new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(root.resolve("density.jsonl.gz")))))) {
                String row;while((row=reader.readLine())!=null)rows.add(JsonParser.parseString(row).getAsJsonObject());
            }
            Map<String,String> names=Map.ofEntries(Map.entry("barrierNoise","barrier"),Map.entry("fluidLevelFloodednessNoise","fluid_level_floodedness"),
                Map.entry("fluidLevelSpreadNoise","fluid_level_spread"),Map.entry("lavaNoise","lava"),Map.entry("temperature","temperature"),
                Map.entry("vegetation","vegetation"),Map.entry("continents","continents"),Map.entry("erosion","erosion"),Map.entry("depth","depth"),
                Map.entry("ridges","ridges"),Map.entry("initialDensityWithoutJaggedness","initial_density_without_jaggedness"),
                Map.entry("finalDensity","final_density"),Map.entry("veinToggle","vein_toggle"),Map.entry("veinRidged","vein_ridged"),Map.entry("veinGap","vein_gap"));
            var input=ByteBuffer.allocateDirect(rows.size()*12).order(ByteOrder.LITTLE_ENDIAN);
            var output=ByteBuffer.allocateDirect(rows.size()*8).order(ByteOrder.LITTLE_ENDIAN);
            for(var row:rows)input.putInt(row.get("x").getAsInt()).putInt(row.get("y").getAsInt()).putInt(row.get("z").getAsInt());
            double max=0;int compared=0,mismatches=0;
            for(var name:names.entrySet()) {
                RustWorldgenBackend.density(world,name.getValue(),input,output,rows.size());
                for(int i=0;i<rows.size();i++) {
                    double expected=rows.get(i).get(name.getKey()).getAsDouble(),actual=output.getDouble(i*8);
                    double error=Math.abs(actual-expected);max=Math.max(max,error);compared++;
                    if(!Double.isFinite(actual)||error>1e-8)mismatches++;
                }
            }
            System.out.println(new Gson().toJson(Map.of("rows",rows.size(),"values",compared,"mismatchesAt1e-8",mismatches,
                "maxAbsoluteError",max,"surfaceAdapter","isolated controlled fixture; full mod runtime not exercised")));
            if(mismatches!=0)throw new AssertionError("Density parity mismatch");
        } finally {RustWorldgenBackend.close(world);}
    }
}
