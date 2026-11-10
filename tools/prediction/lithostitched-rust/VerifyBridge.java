package dev.xantha.vss.client.prediction;
import com.google.gson.*;
import java.nio.*;
import java.nio.file.*;
public class VerifyBridge {
    static JsonElement read(Path p) throws Exception {return JsonParser.parseString(Files.readString(p));}
    static void check(boolean ok,String message) {if(!ok)throw new AssertionError(message);}
    public static void main(String[] args) throws Exception {
        RustWorldgenBackend.load(Path.of(args[0]));Path root=Path.of(args[1]);
        var doc=read(root.resolve("overworld.json")).getAsJsonObject();
        for(String[] pair:new String[][]{{"block_definitions","blocks"},{"biomes","biomes"},{"grass_colormap","grass"},{"foliage_colormap","foliage"}})
            doc.add(pair[0],read(root.resolve(pair[1]+".json")));
        doc.add("biome_source",JsonParser.parseString("{\"type\":\"minecraft:fixed\",\"biome\":\"minecraft:plains\"}"));
        doc.addProperty("vss_java_surface_dimension","minecraft:overworld");
        var settings=doc.getAsJsonObject("settings");settings.addProperty("aquifers_enabled",false);settings.addProperty("sea_level",-64);
        settings.getAsJsonObject("noise_router").add("final_density",JsonParser.parseString("{\"type\":\"minecraft:y_clamped_gradient\",\"from_y\":20,\"to_y\":21,\"from_value\":1,\"to_value\":-1}"));
        settings.add("surface_rule",JsonParser.parseString("""
            {"type":"minecraft:sequence","sequence":[
              {"type":"minecraft:condition","if_true":{"type":"alexscaves:ac_biome","rarity_offset":3},
               "then_run":{"type":"minecraft:block","result_state":{"Name":"minecraft:granite"}}},
              {"type":"minecraft:block","result_state":{"Name":"minecraft:stone"}}]}
            """));
        JavaSurfaceConditions.verifyFailure=true;
        try {RustWorldgenBackend.create(1234,0,doc.toString());throw new AssertionError("verification must reject");}
        catch(IllegalStateException expected) {check(expected.getMessage().contains("verification"),"original verification exception");}
        JavaSurfaceConditions.verifyFailure=false;JavaSurfaceConditions.allowSurface=true;
        long world=RustWorldgenBackend.create(1234,0,doc.toString());
        var input=ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN);input.putInt(-1).putInt(-5);
        var output=ByteBuffer.allocateDirect(40).order(ByteOrder.LITTLE_ENDIAN);
        try {
            RustWorldgenBackend.surfacePoints(world,input,output,1);
            check(JavaSurfaceConditions.calls>0,"JNI callback reached");check(JavaSurfaceConditions.lastSeed==1234,"seed");
            check(JavaSurfaceConditions.lastX==-1 && JavaSurfaceConditions.lastZ==-5,"coordinates");
            check(JavaSurfaceConditions.lastY<=50 && JavaSurfaceConditions.lastOffset==3,"condition arguments");
            var desc=JsonParser.parseString(RustWorldgenBackend.describe(world)).getAsJsonObject();
            check(desc.getAsJsonArray("states").get(output.getInt(16)).getAsJsonObject().get("Name").getAsString().equals("minecraft:granite"),"callback material");
        } finally {check(RustWorldgenBackend.close(world)==1,"close world");}
        world=RustWorldgenBackend.create(1234,0,doc.toString());JavaSurfaceConditions.evaluationFailure=true;
        try {RustWorldgenBackend.surfacePoints(world,input,output,1);throw new AssertionError("evaluation must reject");}
        catch(IllegalStateException expected) {check(expected.getMessage().contains("evaluation"),"original evaluation exception");}
        finally {check(RustWorldgenBackend.close(world)==1,"close failed world");JavaSurfaceConditions.evaluationFailure=false;}
        int before=JavaSurfaceConditions.calls;doc.addProperty("vss_java_surface_dimension","minecraft:the_end");
        world=RustWorldgenBackend.create(1234,0,doc.toString());
        try {RustWorldgenBackend.surfacePoints(world,input,output,1);check(JavaSurfaceConditions.calls==before,"other dimension must not call");}
        finally {check(RustWorldgenBackend.close(world)==1,"close other dimension");}
        System.out.println("{\"jniBridge\":\"passed\",\"initializationFailure\":\"propagated\",\"evaluationFailure\":\"propagated\",\"dimensionGuard\":\"passed\",\"worldHandles\":\"closed\"}");
    }
}
