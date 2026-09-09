package dev.xantha.vss.client.prediction;
import com.google.gson.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Executes ABI 2 against the fixtures made by the actual Minecraft oracle. */
public final class NativeWorldgenReference {
    static final Path ROOT=Path.of("tools/rust/vss-native-core/tests/fixtures/worldgen");
    static final Gson JSON=new Gson();
    static JsonElement read(String name)throws Exception{return JsonParser.parseString(Files.readString(ROOT.resolve(name)));}
    static ByteBuffer direct(int bytes){return ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN);}
    static void require(boolean valid,String message){if(!valid)throw new AssertionError(message);}
    static void rejects(Runnable action){try{action.run();throw new AssertionError("Expected rejected ABI input");}catch(IllegalArgumentException expected){}}
    static JsonObject document(String settings)throws Exception{
        var doc=read(settings+".json").getAsJsonObject();doc.add("block_definitions",read("blocks.json"));doc.add("biomes",read("biomes.json"));
        doc.add("grass_colormap",read("grass.json"));doc.add("foliage_colormap",read("foliage.json"));doc.add("input_states",read("vegetation-states.json"));
        read("features.json").getAsJsonObject().asMap().forEach(doc::add);
        doc.add("possible_biomes",JsonParser.parseString(Files.readAllLines(ROOT.resolve("feature-order.jsonl")).getFirst()).getAsJsonObject().get("possible_biomes"));return doc;
    }
    static JsonArray states(long handle){return JsonParser.parseString(RustWorldgenBackend.describe(handle)).getAsJsonObject().getAsJsonArray("states");}
    static int named(JsonArray table,String name){for(int i=0;i<table.size();i++)if(table.get(i).getAsJsonObject().get("Name").getAsString().equals(name))return i;throw new AssertionError("Missing block "+name);}
    public static void main(String[] args)throws Exception{
        RustWorldgenBackend.load(Path.of(args[0]));long densityValues=0,columnValues=0;
        for(String line:Files.readAllLines(ROOT.resolve("reference.jsonl"))){var row=JsonParser.parseString(line).getAsJsonObject();long seed=Long.parseLong(row.get("seed").getAsString());var doc=document(row.get("settings").getAsString());
            long world=RustWorldgenBackend.create(seed,Long.parseLong(row.get("zoom_seed").getAsString()),JSON.toJson(doc));
            try{
                var points=row.getAsJsonArray("points");var input=direct(points.size()*12);for(var point:points)for(var n:point.getAsJsonArray())input.putInt(n.getAsInt());input.clear();
                var output=direct(points.size()*8);require(RustWorldgenBackend.density(world,"final_density",input,output,points.size())==points.size(),"density count");
                var expected=row.getAsJsonObject("density").getAsJsonArray("final_density");for(int i=0;i<points.size();i++){require(output.getLong(i*8)==Long.parseUnsignedLong(expected.get(i).getAsString(),16),"JNI density");densityValues++;}
                var columns=row.getAsJsonArray("columns");int height=doc.getAsJsonObject("settings").getAsJsonObject("noise").get("height").getAsInt();var xz=direct(columns.size()*8);for(var c:columns){xz.putInt(c.getAsJsonObject().get("x").getAsInt()).putInt(c.getAsJsonObject().get("z").getAsInt());}xz.clear();
                var columnOut=direct(columns.size()*(16+height*4));require(RustWorldgenBackend.columns(world,xz,columnOut,columns.size())==columns.size(),"column count");var table=states(world);
                for(int i=0;i<columns.size();i++){var blocks=columns.get(i).getAsJsonObject().getAsJsonArray("blocks");for(int y=0;y<height;y++){
                    int id=columnOut.getInt(i*(16+height*4)+16+y*4);require(table.get(id).getAsJsonObject().get("Name").getAsString().equals(blocks.get(y).getAsString()),"JNI column block");columnValues++;}}
                rejects(()->RustWorldgenBackend.columns(world,xz,direct(1),1));rejects(()->RustWorldgenBackend.columns(world,xz,columnOut.asReadOnlyBuffer(),1));rejects(()->RustWorldgenBackend.density(world,"final_density",ByteBuffer.allocate(12),direct(8),1));
                var sentinel=direct(16);sentinel.putLong(0,0x1122334455667788L);var invalid=direct(24);invalid.putInt(12,Integer.MIN_VALUE);
                rejects(()->RustWorldgenBackend.density(world,"final_density",invalid,sentinel,2));require(sentinel.getLong(0)==0x1122334455667788L,"invalid batch changed output");
            }finally{RustWorldgenBackend.close(world);}rejects(()->RustWorldgenBackend.describe(world));
        }
        var doc=document("overworld");long world=RustWorldgenBackend.create(0,0,JSON.toJson(doc));int cases=0;
        try{
            require(!JsonParser.parseString(RustWorldgenBackend.support(world,"\"minecraft:birch_bees_005\"",0)).getAsJsonObject().get("supported").getAsBoolean(),"unsupported decorator advertised");
            var worldStates=states(world);int air=named(worldStates,"minecraft:air"),grass=named(worldStates,"minecraft:grass_block"),dirt=named(worldStates,"minecraft:dirt");var fixtureStates=read("vegetation-states.json").getAsJsonArray();
            Map<JsonElement,Integer> worldIds=new HashMap<>();for(int i=0;i<worldStates.size();i++)worldIds.put(worldStates.get(i),i);
            int cells=64*384*64;var input=direct(cells*4);var output=direct(cells*4);int[] expected=new int[cells];
            for(String line:Files.readAllLines(ROOT.resolve("vegetation.jsonl"))){var row=JsonParser.parseString(line).getAsJsonObject();if(!row.get("seed").getAsString().equals("0")||row.get("pattern").getAsInt()!=0)continue;
                input.clear();for(int x=0;x<64;x++)for(int z=0;z<64;z++)for(int y=0;y<384;y++){int id=y<127?dirt:y==127?grass:air;input.putInt(id);expected[(x*64+z)*384+y]=id;}
                for(var b:row.getAsJsonArray("output")){var v=b.getAsJsonArray();int index=((v.get(0).getAsInt()+32)*64+v.get(2).getAsInt()+32)*384+v.get(1).getAsInt()+64;expected[index]=worldIds.get(fixtureStates.get(v.get(3).getAsInt()));}
                input.clear();long volume=RustWorldgenBackend.createVolume(world,-32,-64,-32,64,384,64,input);
                try{var result=JsonParser.parseString(RustWorldgenBackend.feature(volume,JSON.toJson(row.get("feature")),0,0,64,0)).getAsJsonObject();require(result.get("placed").equals(row.get("placed")),"JNI feature result");require(result.get("after").equals(row.get("after")),"JNI feature random stream");
                    require(RustWorldgenBackend.readVolume(volume,output)==cells,"volume count");var resultStates=states(volume);
                    for(int i=0;i<cells;i++)require(resultStates.get(output.getInt(i*4)).equals(worldStates.get(expected[i])),"JNI vegetation state at "+i);
                    if(!row.getAsJsonArray("output").isEmpty()) rejects(()->RustWorldgenBackend.readEdits(volume,direct(1)));
                    var edits=direct(262144*16);int count=RustWorldgenBackend.readEdits(volume,edits);
                    require(count>=row.getAsJsonArray("output").size(),"Rejected edit buffer lost pending output");
                    require(RustWorldgenBackend.readEdits(volume,direct(4))==0,"Native edits were not drained");cases++;
                }finally{RustWorldgenBackend.close(volume);}
            }
            long volume=RustWorldgenBackend.surfaceRegion(world,0,0,1);RustWorldgenBackend.close(world);
            try{var descriptor=JsonParser.parseString(RustWorldgenBackend.describe(volume)).getAsJsonObject();require(descriptor.getAsJsonArray("size").get(1).getAsInt()==384,"surface region height");require(RustWorldgenBackend.readVolume(volume,direct(16*16*384*4))==16*16*384,"parent close broke result lease");}finally{RustWorldgenBackend.close(volume);}
        }finally{RustWorldgenBackend.close(world);}
        System.out.println("JNI ABI 2: density="+densityValues+", base blocks="+columnValues+", vegetation cases="+cases+", invalid inputs/closed handles/result ownership passed");
    }
}
