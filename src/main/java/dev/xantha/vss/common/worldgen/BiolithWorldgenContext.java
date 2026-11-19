package dev.xantha.vss.common.worldgen;

import com.google.gson.*;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.*;
import java.util.*;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;

/** Frozen Biolith routing. Never initializes or replaces the mod's process-wide server state. */
public final class BiolithWorldgenContext {
    private static final String PREFIX="com.terraformersmc.biolith.";
    private BiolithWorldgenContext() { }

    public static JsonObject capture(BiomeSource source, DynamicOps<JsonElement> ops) {
        Method dimension;
        try { dimension=source.getClass().getMethod("biolith$getDimensionType"); }
        catch (NoSuchMethodException absent) { return null; }
        if (!(source instanceof MultiNoiseBiomeSource)) return null;
        try {
            String id=key((ResourceKey<?>)dimension.invoke(source));
            String field=switch(id) {
                case "minecraft:overworld" -> "OVERWORLD";
                case "minecraft:the_nether" -> "NETHER";
                default -> null;
            };
            if(field==null) return null; // End routing has a different contract.
            Object placement=Class.forName(PREFIX+"impl.biome.BiomeCoordinator").getField(field).get(null);
            JsonObject result=new JsonObject();result.addProperty("dimension_type",id);
            result.addProperty("placement",field);
            JsonArray scales=new JsonArray();
            for(double scale:(double[])get(placement,"scale")) scales.add(scale);
            result.add("scale",scales);
            // Invoke the transformed parameter getter: the vanilla codec stores the
            // original Either, which omits Biolith's injected and removed entries.
            Method parameters=Arrays.stream(MultiNoiseBiomeSource.class.getDeclaredMethods())
                    .filter(m->m.getParameterCount()==0 && m.getReturnType()==Climate.ParameterList.class
                            && !m.getName().contains("$"))
                    .findFirst().orElseThrow();parameters.setAccessible(true);
            @SuppressWarnings("unchecked") var entries=(Climate.ParameterList<Holder<Biome>>)parameters.invoke(source);
            result.add("entries",MultiNoiseBiomeSource.DIRECT_CODEC.codec().encodeStart(ops,entries).getOrThrow());
            for(String kind:List.of("replacementRequests","subBiomeRequests")) {
                JsonObject sets=new JsonObject();
                for(var entry:map(placement,kind).entrySet()) {
                    JsonArray requests=new JsonArray();
                    for(Object request:(List<?>)get(entry.getValue(),"requests")) {
                        JsonObject row=new JsonObject();
                        row.addProperty("biome",key((ResourceKey<?>)get(request,"biome")));
                        if(kind.equals("replacementRequests")) {
                            for(String number:List.of("rate","start","end"))row.addProperty(number,(Number)get(request,number));
                        } else row.add("criterion",criterionCodec().encodeStart(ops,get(request,"criterion")).getOrThrow());
                        requests.add(row);
                    }
                    sets.add(key((ResourceKey<?>)entry.getKey()),requests);
                }
                result.add(kind,sets);
            }
            return result;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Biolith runtime snapshot unavailable",failure);
        }
    }

    /** Routing can emit replacement biomes absent from the climate parameter list. */
    public static List<Holder<Biome>> additionalBiomes(JsonObject snapshot, Registry<Biome> biomes) {
        var result=new LinkedHashSet<Holder<Biome>>();
        for(String collection:List.of("replacementRequests","subBiomeRequests"))
            for(var entry:snapshot.getAsJsonObject(collection).entrySet())
                for(var element:entry.getValue().getAsJsonArray()) {
                    String id=element.getAsJsonObject().get("biome").getAsString();
                    if(!id.equals("biolith:vanilla")) result.add(biomes.getHolderOrThrow(biomeKey(id)));
                }
        return List.copyOf(result);
    }

    public interface Routing { Holder<Biome> sample(int x,int y,int z,Climate.TargetPoint target); }

    public static Routing restore(JsonObject snapshot, DynamicOps<JsonElement> ops,
                                  Registry<Biome> biomes, long seed) {
        try {
            String kind=snapshot.get("placement").getAsString();
            String type=switch(kind) {
                case "OVERWORLD" -> "OverworldBiomePlacement";
                case "NETHER" -> "NetherBiomePlacement";
                default -> throw new IllegalArgumentException("Unsupported Biolith routing "+kind);
            };
            Object placement=Class.forName(PREFIX+"impl.biome."+type).getConstructor().newInstance();
            double[] scales=(double[])get(placement,"scale");
            var savedScales=snapshot.getAsJsonArray("scale");
            if(savedScales.size()!=scales.length) throw new IllegalArgumentException("Biolith noise scale changed");
            for(int i=0;i<scales.length;i++)scales[i]=savedScales.get(i).getAsDouble();
            set(placement,"replacementNoise",Class.forName(PREFIX+"impl.noise.OpenSimplexNoise2")
                    .getConstructor(long.class).newInstance(seed));
            set(placement,"seedRandom",new Random(seed));
            int[] seedlets=(int[])get(placement,"seedlets");
            for(int i=0;i<seedlets.length;i++)seedlets[i]=(int)(seed>>(i*8)&255);
            for(String collection:List.of("replacementRequests","subBiomeRequests")) {
                boolean replacement=collection.equals("replacementRequests");
                Class<?> setType=Class.forName(PREFIX+"impl.biome.DimensionBiomePlacement$"
                        +(replacement?"ReplacementRequestSet":"SubBiomeRequestSet"));
                Class<?> requestType=Class.forName(PREFIX+"impl.biome.DimensionBiomePlacement$"
                        +(replacement?"ReplacementRequest":"SubBiomeRequest"));
                Constructor<?> groupConstructor=setType.getDeclaredConstructors()[0];groupConstructor.setAccessible(true);
                Constructor<?> rowConstructor=Arrays.stream(requestType.getDeclaredConstructors())
                        .filter(c->c.getParameterCount()==(replacement?6:4)).findFirst().orElseThrow();
                rowConstructor.setAccessible(true);
                for(var entry:snapshot.getAsJsonObject(collection).entrySet()) {
                    ResourceKey<Biome> target=biomeKey(entry.getKey());
                    Object group=groupConstructor.newInstance(placement,target);
                    List<Object> rows=new ArrayList<>();
                    for(JsonElement element:entry.getValue().getAsJsonArray()) {
                        var row=element.getAsJsonObject();ResourceKey<Biome> key=biomeKey(row.get("biome").getAsString());
                        Holder<Biome> holder=biomes.getHolder(key).orElse(null);
                        if(replacement)rows.add(rowConstructor.newInstance(key,row.get("rate").getAsDouble(),holder,
                                row.get("start").getAsDouble(),row.get("end").getAsDouble(),false));
                        else {
                            Object criterion=criterionCodec().parse(ops,row.get("criterion")).getOrThrow();
                            Class.forName(PREFIX+"api.biome.sub.Criterion").getMethod("complete",net.minecraft.core.HolderGetter.class)
                                    .invoke(criterion,biomes);
                            rows.add(rowConstructor.newInstance(key,criterion,holder,false));
                        }
                    }
                    set(group,"requests",List.copyOf(rows));set(group,"finalized",true);
                    map(placement,collection).put(target,group);
                }
            }
            var entries=MultiNoiseBiomeSource.DIRECT_CODEC.codec().parse(ops,snapshot.get("entries")).getOrThrow();
            Object tree=null;
            for(Field field:Climate.ParameterList.class.getDeclaredFields())if(Arrays.stream(field.getType().getMethods()).anyMatch(m->m.getName().equals("biolith$searchTreeGet"))) {
                field.setAccessible(true);tree=field.get(entries);break;
            }
            if(tree==null)throw new IllegalStateException("Biolith search index unavailable");
            Method search=Arrays.stream(tree.getClass().getMethods()).filter(m->m.getName().equals("biolith$searchTreeGet"))
                    .findFirst().orElseThrow();
            Class<?> metricType=search.getParameterTypes()[1];
            Method distanceContract=Arrays.stream(metricType.getMethods()).filter(m->Modifier.isAbstract(m.getModifiers()))
                    .findFirst().orElseThrow();
            Class<?> node=distanceContract.getParameterTypes()[0];
            Method distance=Arrays.stream(node.getDeclaredMethods()).filter(m->m.getReturnType()==long.class
                    && Arrays.equals(m.getParameterTypes(),new Class<?>[]{long[].class})).findFirst().orElseThrow();
            distance.setAccessible(true);MethodHandle distanceCall=MethodHandles.lookup().unreflect(distance);
            Object metric=Proxy.newProxyInstance(metricType.getClassLoader(),new Class<?>[]{metricType},(proxy,method,args)-> {
                if(method.equals(distanceContract))return distanceCall.invoke(args[0],args[1]);
                if(method.getName().equals("toString"))return "VSS Biolith distance";
                if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);
                return proxy==args[0];
            });
            Object index=tree;
            search.setAccessible(true);
            MethodHandle searchCall=MethodHandles.lookup().unreflect(search).bindTo(index);
            MethodHandle noise=MethodHandles.lookup().unreflect(placement.getClass().getMethod("getLocalNoise",int.class,int.class,int.class)).bindTo(placement);
            Class<?> nodesType=search.getReturnType();
            Method ultimateMethod=nodesType.getMethod("ultimate");
            MethodHandle ultimate=MethodHandles.lookup().unreflect(ultimateMethod);
            Field leafValue=Arrays.stream(ultimateMethod.getReturnType().getDeclaredFields())
                    .filter(f->f.getType()==Object.class).findFirst().orElseThrow();
            leafValue.setAccessible(true);
            MethodHandle value=MethodHandles.lookup().unreflectGetter(leafValue);
            Map<Object,Object> replacementsByBiome=Map.copyOf(map(placement,"replacementRequests"));
            Map<Object,Object> subBiomes=Map.copyOf(map(placement,"subBiomeRequests"));
            Class<?> replacementSet=Class.forName(PREFIX+"impl.biome.DimensionBiomePlacement$ReplacementRequestSet");
            Class<?> replacementType=Class.forName(PREFIX+"impl.biome.DimensionBiomePlacement$ReplacementRequest");
            Class<?> subSet=Class.forName(PREFIX+"impl.biome.DimensionBiomePlacement$SubBiomeRequestSet");
            Class<?> subType=Class.forName(PREFIX+"impl.biome.DimensionBiomePlacement$SubBiomeRequest");
            MethodHandle selectReplacement=method(replacementSet,"selectReplacement");
            MethodHandle rangeCall=method(replacementType,"range");
            MethodHandle replacementBiome=MethodHandles.lookup().unreflectGetter(field(replacementType,"biomeEntry"));
            MethodHandle replacementKey=MethodHandles.lookup().unreflectGetter(field(replacementType,"biome"));
            MethodHandle selectSub=method(subSet,"selectSubBiome");
            MethodHandle subBiome=MethodHandles.lookup().unreflectGetter(field(subType,"biomeEntry"));
            @SuppressWarnings("unchecked") ThreadLocal<Object> position=(ThreadLocal<Object>)getStatic(placement.getClass(),"EVALUATING_BIOME_POS");
            @SuppressWarnings("unchecked") ThreadLocal<Object> world=(ThreadLocal<Object>)getStatic(placement.getClass(),"EVALUATING_WORLD");
            return (x,y,z,target)-> {
                try {
                    Object nodes=searchCall.invoke(target,metric);
                    @SuppressWarnings("unchecked") Holder<Biome> chosen=(Holder<Biome>)value.invoke(ultimate.invoke(nodes));
                    if(chosen==null)throw new IllegalStateException("Biolith nearest biome unavailable");
                    Object key=chosen.unwrapKey().orElseThrow();double local=-1;Object range=null;
                    Object replacements=replacementsByBiome.get(key);
                    if(replacements!=null) {
                        local=(double)noise.invoke(x,y,z);
                        Object replacement=selectReplacement.invoke(replacements,local);
                        if(replacement!=null && replacementBiome.invoke(replacement)!=null) {
                            range=rangeCall.invoke(replacement);
                            if(!key((ResourceKey<?>)replacementKey.invoke(replacement)).equals("biolith:vanilla")) {
                                @SuppressWarnings("unchecked") var holder=(Holder<Biome>)replacementBiome.invoke(replacement);
                                chosen=holder;key=replacementKey.invoke(replacement);
                            }
                        }
                    }
                    Object sub=subBiomes.get(key);
                    if(sub!=null) {
                        if(local<0)local=(double)noise.invoke(x,y,z);
                        Object before=position.get(),previousWorld=world.get();position.set(new net.minecraft.core.Vec3i(x,y,z));world.remove();
                        try {
                            Object replacement=selectSub.invoke(sub,nodes,target,range,local);
                            if(replacement!=null && subBiome.invoke(replacement)!=null) {
                                @SuppressWarnings("unchecked") var holder=(Holder<Biome>)subBiome.invoke(replacement);chosen=holder;
                            }
                        } finally { if(before==null)position.remove();else position.set(before);if(previousWorld==null)world.remove();else world.set(previousWorld); }
                    }
                    return chosen;
                } catch (Throwable failure) {
                    if(failure instanceof java.util.concurrent.CancellationException cancelled)throw cancelled;
                    throw new IllegalStateException("Biolith runtime biome evaluation failed",failure);
                }
            };
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Biolith runtime restore unavailable",failure); }
    }

    private static MethodHandle method(Class<?> type,String name) throws ReflectiveOperationException {
        Method method=Arrays.stream(type.getDeclaredMethods()).filter(m->m.getName().equals(name)).findFirst().orElseThrow();
        method.setAccessible(true);
        return MethodHandles.lookup().unreflect(method);
    }

    @SuppressWarnings("unchecked") private static Codec<Object> criterionCodec() throws ReflectiveOperationException {
        return (Codec<Object>)Class.forName(PREFIX+"api.biome.sub.Criterion").getField("CODEC").get(null);
    }
    private static Field field(Class<?> type,String name) throws NoSuchFieldException {
        for(Class<?> current=type;current!=null;current=current.getSuperclass())try {
            Field field=current.getDeclaredField(name);field.setAccessible(true);return field;
        } catch(NoSuchFieldException absent) { }
        throw new NoSuchFieldException(type.getName()+"."+name);
    }
    private static Object get(Object owner,String name) throws ReflectiveOperationException { return field(owner.getClass(),name).get(owner); }
    private static Object getStatic(Class<?> type,String name) throws ReflectiveOperationException { return field(type,name).get(null); }
    private static void set(Object owner,String name,Object value) throws ReflectiveOperationException { field(owner.getClass(),name).set(owner,value); }
    @SuppressWarnings("unchecked") private static Map<Object,Object> map(Object owner,String name) throws ReflectiveOperationException {
        return (Map<Object,Object>)get(owner,name);
    }
    private static String key(ResourceKey<?> key) { return key.location().toString(); }
    private static ResourceKey<Biome> biomeKey(String id) {
        return ResourceKey.create(net.minecraft.core.registries.Registries.BIOME,net.minecraft.resources.ResourceLocation.parse(id));
    }
}
