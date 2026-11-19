package dev.xantha.vss.client.prediction;

import dev.xantha.vss.client.prediction.feature.FeatureStampLevel;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.*;
import net.minecraft.world.level.levelgen.placement.*;

/** Distant appearance. Immutable captured rules are shared; every build owns its scope and scratch. */
final class PredictionFarDecoration {
    private static final ThreadLocal<PredictionFarDecoration> ACTIVE = new ThreadLocal<>();
    static PredictionFarDecoration current() { return ACTIVE.get(); }
    static boolean approximating() { return current() != null && !current().structures; }
    boolean structures;
    private java.util.function.BooleanSupplier currentBuild = () -> true;
    private PredictionVoxySource.Tile displayGround;
    private java.util.function.LongPredicate captured = ignored -> false;
    private boolean isCaptured(int x,int z) { return captured.test((long)Math.floorDiv(x,16)<<32 | Math.floorDiv(z,16)&0xffffffffL); }
    private void checkCurrent() {
        if (Thread.currentThread().isInterrupted() || !currentBuild.getAsBoolean())
            throw new java.util.concurrent.CancellationException("far decoration changed");
    }
    Scope enter(java.util.function.BooleanSupplier current, java.util.function.LongPredicate captured,
                PredictionVoxySource.Tile ground) {
        this.currentBuild=current; this.captured=captured; this.displayGround=ground;
        var previous=ACTIVE.get(); ACTIVE.set(this); return new Scope(previous);
    }
    final class Scope implements AutoCloseable {
        private final PredictionFarDecoration previous;
        Scope(PredictionFarDecoration previous) { this.previous=previous; }
        @Override public void close() { if(previous==null) ACTIVE.remove(); else ACTIVE.set(previous); }
    }
    final ClientTerrainSampler sampler;
    final PredictionFarGround anchorGround;
    final ClientTerrainSampler context;
    final boolean proxyEnvironment;
    final int environmentStep;
    final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<int[]> proxyColumns = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<ClientColumnSample> javaColumns = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    final java.nio.ByteBuffer proxyInput=java.nio.ByteBuffer.allocateDirect(8).order(java.nio.ByteOrder.LITTLE_ENDIAN);
    final java.nio.ByteBuffer proxyOutput=java.nio.ByteBuffer.allocateDirect(40).order(java.nio.ByteOrder.LITTLE_ENDIAN);
    final Set<PlacedFeature> replaced;
    final Map<Integer,List<Rule>> biomeRules;
    final Map<PlacedFeature,List<Choice>> parsed;
    final Map<PlacementModifier,Double> densities;
    final PredictionSharedCache<Long,SelectionPage> selections;
    final PredictionSharedCache<RotationKey,List<Span>> rotations;
    final Map<ConfiguredFeature<?,?>,List<Template>> templates;
    final List<Map<String,Object>> audit;
    private static final int MAX_PENDING_TEMPLATES_PER_PASS = 32;
    long anchors, selected, accepted, anchorSamples, emitted, tinyOmitted, templateAttempts, templateFailures;
    final Map<String,Long> acceptedByBiome = new TreeMap<>();
    final Map<String,Long> groundByBiome = new TreeMap<>();
    record Cell(int x,int y,int z,BlockState state) {}
    record Span(int x,int z,int bottom,int top,BlockState state) {}
    record GroundCondition(java.util.function.Predicate<BlockState> predicate,boolean expected) {
        boolean matches(BlockState state) { return predicate.test(state)==expected; }
    }
    record Template(List<Span> spans,int height,boolean woody,Set<GroundCondition> soils) {}
    record Choice(ConfiguredFeature<?,?> feature,double weight,List<Template> models) {}
    record Rule(long salt,double count,List<Choice> choices,boolean woody) {}
    record Anchor(int x,int y,int z,long id,Template model) {}
    private record SelectionCell(int biome,int selected,List<Anchor> sites) {}
    private record SelectionPage(SelectionCell[] cells,long bytes) {}
    private record RotationKey(Template template,int rotation) {
        @Override public boolean equals(Object other){return other instanceof RotationKey k && template==k.template && rotation==k.rotation;}
        @Override public int hashCode(){return System.identityHashCode(template)*31+rotation;}
    }
    record Result(List<PredictionVoxySource.Box> boxes,List<Anchor> anchors) {}
    static final class Votes {
        private final int step;
        private final long[] coverage;
        private long firstCoverage;
        private int occupied;
        private boolean containsNonLeaves;
        final it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap counts=new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap(2);
        Votes(int step) {
            this.step=step;
            // Most distant leaf cells at spacing 4 fit in one word, with no extra allocation.
            coverage=step<=4?null:new long[(step*step*step+63)/64];
        }
        void add(BlockState state,int x,int z,int bottom,int top) {
            containsNonLeaves|=!state.is(BlockTags.LEAVES);
            int weight=0,bit=(z*step+x)*step+bottom,end=(z*step+x)*step+top;
            while(bit<end) {
                int count=Math.min(end-bit,64-(bit&63));
                long mask=(-1L >>> (64-count)) << (bit&63);
                if(coverage==null) {
                    weight+=Long.bitCount(mask&~firstCoverage);firstCoverage|=mask;
                } else {
                    int word=bit>>>6;
                    weight+=Long.bitCount(mask&~coverage[word]);coverage[word]|=mask;
                }
                bit+=count;
            }
            // Overlapping crowns cannot vote multiple times for the same fine voxel.
            occupied+=weight;
            if(weight>0)counts.addTo(net.minecraft.world.level.block.Block.getId(state),weight);
        }
        boolean visible() {
            // Keep trunks/other decoration. For leaves, air participates in the coverage decision.
            // A low 1/8 threshold retains airy crowns while rejecting isolated edge fragments.
            return containsNonLeaves || occupied*8>=step*step*step;
        }
        BlockState winner() {
            if(!visible())return Blocks.AIR.defaultBlockState();
            int chosen=Integer.MAX_VALUE,max=-1;
            for(var e:counts.int2IntEntrySet())if(e.getIntValue()>max || e.getIntValue()==max && e.getIntKey()<chosen) {chosen=e.getIntKey();max=e.getIntValue();}
            return net.minecraft.world.level.block.Block.stateById(chosen);
        }
    }

    static boolean replace(PlacedFeature feature) {
        // The terrain writer already emits snow/ice from the sampled column flags.
        var active=current();
        return active!=null && (active.replaced.contains(feature)
                || active.proxyEnvironment && feature.feature().value().feature()==Feature.FREEZE_TOP_LAYER);
    }

    PredictionFarDecoration(ClientTerrainSampler sampler, boolean proxyEnvironment, int environmentStep) {
        this.sampler=sampler;
        anchorGround=new PredictionFarGround(sampler);
        selections=new PredictionSharedCache<>(4096,8L<<20,SelectionPage::bytes);
        rotations=new PredictionSharedCache<>(512,8L<<20,spans->64L+spans.size()*48L);
        this.proxyEnvironment=proxyEnvironment;
        this.environmentStep=environmentStep;
        context=sampler.decorationContext();
        replaced=Collections.newSetFromMap(new IdentityHashMap<>());
        biomeRules=new HashMap<>(); parsed=new IdentityHashMap<>(); densities=new IdentityHashMap<>(); templates=new IdentityHashMap<>(); audit=new ArrayList<>();
    }

    void initialize(java.util.function.BooleanSupplier current) {
        currentBuild = current;
        try {
            initializeBiomes();
        } finally {
            currentBuild = () -> true;
        }
    }

    private void initializeBiomes() {
        var registry=context.decorationAccess().registryOrThrow(Registries.PLACED_FEATURE);
        var names=context.cacheBiomes();
        // Let independent features request their templates in parallel. Completed rules
        // and captured templates stay in the catalog while resources are pending.
        Set<PlacedFeature> awaiting = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int index = 0; index < names.size(); index++) {
            if (biomeRules.containsKey(index)) continue;
            checkCurrent();
            var holder=context.cacheBiomeHolder(index);
            if(holder==null)continue;
            var features=context.generatorContext().getBiomeGenerationSettings(holder).features();
            var rules = new ArrayList<Rule>();
            boolean biomePending = false;
            // Keep terrain-changing features, local modifications, ores and structures on the existing path.
            // Only the biome's visible VEGETAL_DECORATION stage is eligible for distant templates.
            if(features.size()>9) for(var entry:features.get(9)) {
                PlacedFeature feature=entry.value();
                List<Choice> choices=parsed.get(feature);
                if (choices == null) {
                    if (awaiting.contains(feature)) {
                        biomePending = true;
                        continue;
                    }
                    try { choices = parsePlaced(feature, 0); }
                    catch (PredictionWorkDeferred pending) {
                        awaiting.add(feature);
                        if (awaiting.size() >= MAX_PENDING_TEMPLATES_PER_PASS) throw pending;
                        biomePending = true;
                        continue;
                    }
                    catch (java.util.concurrent.CancellationException cancelled) { throw cancelled; }
                    catch (RuntimeException unsupported) { choices = List.of(); }
                    parsed.put(feature,choices);
                    var name=registry.getKey(feature);
                    audit.add(Map.of("feature",String.valueOf(name),"replaced",!choices.isEmpty(),
                            "choices",choices.size(),"featureType",String.valueOf(BuiltInRegistries.FEATURE.getKey(feature.feature().value().feature()))));
                }
                if(choices.isEmpty())continue;
                double count=choices.stream().mapToDouble(Choice::weight).sum();
                if(count<=0)continue;
                boolean woody=choices.stream().anyMatch(c->c.models.stream().anyMatch(Template::woody));
                String name=String.valueOf(registry.getKey(feature));
                rules.add(new Rule(stringHash(name),count,choices,woody));
                replaced.add(feature);
            }
            if (!biomePending) biomeRules.put(index, List.copyOf(rules));
        }
        if (!awaiting.isEmpty()) throw new PredictionWorkDeferred();
    }

    private PredictionFarDecoration(PredictionFarDecoration model) {
        anchorGround=model.anchorGround;
        sampler=model.sampler; context=model.context; proxyEnvironment=model.proxyEnvironment; environmentStep=model.environmentStep;
        replaced=model.replaced; biomeRules=model.biomeRules; parsed=model.parsed;
        densities=model.densities;
        selections=model.selections;rotations=model.rotations;
        templates=model.templates; audit=model.audit;
    }
    PredictionFarDecoration session() { return new PredictionFarDecoration(this); }

    int[] proxyRecord(int x,int z) {
        checkCurrent();
        int ax=Math.floorDiv(x,environmentStep)*environmentStep,az=Math.floorDiv(z,environmentStep)*environmentStep;
        long key=(long)ax<<32|az&0xffffffffL;
        int[] record=proxyColumns.get(key);
        if(record==null) {
            proxyInput.clear();proxyInput.putInt(ax).putInt(az);
            if (!(sampler instanceof RustTerrainSampler rust)) throw new IllegalStateException("Native environment requested for a Java sampler");
            if(RustWorldgenBackend.decorationPoints(rust.handle(),proxyInput,proxyOutput,1)!=1)throw new AssertionError("Incomplete coarse environment");
            record=new int[10];for(int i=0;i<10;i++)record[i]=proxyOutput.getInt(i*4);
            proxyColumns.put(key,record);
        }
        return record;
    }

    ClientColumnSample proxySample(int x, int z) {
        checkCurrent();
        int ax = Math.floorDiv(x, environmentStep) * environmentStep, az = Math.floorDiv(z, environmentStep) * environmentStep;
        long key = (long) ax << 32 | az & 0xffffffffL;
        var sample = javaColumns.get(key);
        if (sample == null) { sample = anchorGround.sample(ax, az); javaColumns.put(key, sample); }
        return sample;
    }

    private List<Choice> parsePlaced(PlacedFeature feature,int depth) {
        checkCurrent();
        if(depth>12)return List.of();
        var cached=parsed.get(feature);
        if(cached!=null)return cached;
        var result=parsePlacedNew(feature,depth);
        parsed.put(feature,result);
        return result;
    }
    private List<Choice> parsePlacedNew(PlacedFeature feature,int depth) {
        double count=1;
        for(var modifier:feature.placement()) {
            Boolean dimensionAllowed = PredictionDimensionFilter.allows(modifier, context.profile().dimension());
            if (dimensionAllowed != null) {
                if (!dimensionAllowed) return List.of();
                continue;
            }
            String type=BuiltInRegistries.PLACEMENT_MODIFIER_TYPE.getKey(modifier.type()).getPath();
            if(type.equals("fast_poission") || type.equals("fast_poisson")) {
                Double density=densities.get(modifier);
                if(density==null) {
                    var sampled=PredictionFarPlacementCount.distantDensity(modifier,context.profile().seed(),this::checkCurrent);
                    if(sampled.isEmpty())return List.of();
                    density=sampled.getAsDouble();densities.put(modifier,density);
                }
                count*=density;
            } else if(type.equals("count") || type.equals("rarity_filter") || type.equals("noise_based_count") || type.equals("noise_threshold_count")) {
                Double density=densities.get(modifier);
                if(density==null) {
                int n=type.startsWith("noise_")?2048:256;
                double sum=0;
                var random=RandomSource.create(0x46524152554c4553L);
                for(int i=0;i<n;i++) {
                    if((i&63)==0)checkCurrent();
                    var pos=new BlockPos((int)mix(i*0x9e3779b97f4a7c15L),64,(int)mix(i*0xc2b2ae3d27d4eb4fL));
                    sum+=PredictionFarPlacementCount.sample(modifier,random,pos);
                }
                density=sum/n;densities.put(modifier,density);
                }
                count*=density;
            } else if(!Set.of("in_square","biome","heightmap","surface_water_depth_filter","block_predicate_filter").contains(type)) {
                return List.of();
            }
        }
        var result=parseConfigured(feature.feature().value(),depth+1);
        double rate=count;
        return result.stream().map(c->new Choice(c.feature,c.weight*rate,c.models)).toList();
    }

    private List<Choice> parseConfigured(ConfiguredFeature<?,?> feature,int depth) {
        if(depth>12)return List.of();
        var out=new ArrayList<Choice>();
        if(feature.config() instanceof RandomFeatureConfiguration c) {
            double remaining=1;
            for(var child:c.features) {
                var choices=parsePlaced(child.feature.value(),depth+1);
                if(choices.isEmpty())return List.of();
                double weight=remaining*child.chance;
                for(var choice:choices)out.add(new Choice(choice.feature,choice.weight*weight,choice.models));
                remaining*=1-child.chance;
            }
            var choices=parsePlaced(c.defaultFeature.value(),depth+1);
            if(choices.isEmpty())return List.of();
            for(var choice:choices)out.add(new Choice(choice.feature,choice.weight*remaining,choice.models));
            return out;
        }
        if(feature.config() instanceof SimpleRandomFeatureConfiguration c) {
            for(var child:c.features) {
                var choices=parsePlaced(child.value(),depth+1);
                if(choices.isEmpty())return List.of();
                for(var choice:choices)out.add(new Choice(choice.feature,choice.weight/c.features.size(),choice.models));
            }
            return out;
        }
        if(feature.config() instanceof RandomBooleanFeatureConfiguration c) {
            for(var child:List.of(c.featureTrue,c.featureFalse)) {
                var choices=parsePlaced(child.value(),depth+1);
                if(choices.isEmpty())return List.of();
                for(var choice:choices)out.add(new Choice(choice.feature,choice.weight*.5,choice.models));
            }
            return out;
        }
        List<Template> models=templates.computeIfAbsent(feature,this::capture);
        return models.isEmpty()?List.of():List.of(new Choice(feature,1,models));
    }

    private List<Template> capture(ConfiguredFeature<?,?> feature) {
        var results=new ArrayList<Template>();
        for(int variant=0;variant<3;variant++) {
            checkCurrent();
            templateAttempts++;
            boolean nbtTree = BuiltInRegistries.FEATURE.getKey(feature.feature()).getNamespace().equals("ohthetreesyoullgrow");
            long seed=mix(0x4652415254454d50L+variant);
            var soils=new LinkedHashSet<GroundCondition>();
            var level=new PredictionTemplateStampLevel(seed,context,Blocks.GRASS_BLOCK.defaultBlockState(),nbtTree) {
                @Override public net.minecraft.resources.ResourceLocation predictionDimension() { return context.profile().dimension(); }
                @Override public boolean isStateAtPosition(BlockPos p,java.util.function.Predicate<BlockState> predicate) {
                    boolean result=super.isStateAtPosition(p,predicate);
                    if(p.getY()==GROUND_Y-1 && !placed().containsKey(p))soils.add(new GroundCondition(predicate,result));
                    return result;
                }
                int writes;
                @Override public boolean setBlock(BlockPos p,BlockState state,int flags,int recursion) {
                    if((writes&255)==0)checkCurrent();
                    if(++writes>32768 || Math.abs(p.getX())>24 || Math.abs(p.getZ())>24 || p.getY()<GROUND_Y-8 || p.getY()>GROUND_Y+96)
                        throw new UnsupportedOperationException("template capture bounds");
                    return super.setBlock(p,state,flags,recursion);
                }
            };
            try {
                boolean success=PredictionConfiguredFeaturePlacement.place(feature,level,
                        context.generatorContext(),RandomSource.create(seed),new BlockPos(0,64,0));
                var cells=level.placed().entrySet().stream()
                        .filter(e->!e.getValue().isAir() && e.getKey().getY()>=64 && PredictionVegetation.renderable(e.getValue()))
                        .map(e->new Cell(e.getKey().getX(),e.getKey().getY()-64,e.getKey().getZ(),e.getValue()))
                        .sorted(Comparator.comparingInt(Cell::z).thenComparingInt(Cell::x).thenComparingInt(Cell::y)).toList();
                if(!success || cells.isEmpty()) {templateFailures++;continue;}
                var spans=new ArrayList<Span>();
                for(Cell cell:cells) {
                    if(!spans.isEmpty()) {
                        var old=spans.getLast();
                        if(old.x==cell.x && old.z==cell.z && old.top==cell.y && old.state==cell.state) {
                            spans.set(spans.size()-1,new Span(old.x,old.z,old.bottom,cell.y+1,old.state));continue;
                        }
                    }
                    spans.add(new Span(cell.x,cell.z,cell.y,cell.y+1,cell.state));
                }
                boolean woody=cells.stream().anyMatch(c->c.state.is(BlockTags.LOGS) || c.state.is(BlockTags.LEAVES));
                results.add(new Template(List.copyOf(spans),cells.stream().mapToInt(Cell::y).max().orElse(0)+1,woody,Set.copyOf(soils)));
            } catch(java.util.concurrent.CancellationException | PredictionWorkDeferred cancelled) {
                throw cancelled;
            } catch(RuntimeException unsupported) { templateFailures++; }
        }
        return List.copyOf(results);
    }

    void reset() {
        anchors=selected=accepted=anchorSamples=emitted=tinyOmitted=0;
        acceptedByBiome.clear();groundByBiome.clear();
    }

    private SelectionPage selectPage(int gx,int gz) {
        var cells=new SelectionCell[64];long bytes=64+64*40;
        for(int i=0;i<64;i++) {
            checkCurrent();int cx=gx+i%8,cz=gz+i/8;
            long root=anchorGround.root(cx,cz);
            int anchorX=anchorGround.x(cx,root),anchorZ=anchorGround.z(cz,root);
            var ground=anchorGround.at(cx,cz,this::checkCurrent);anchorSamples++;
            int biome=ground.biomeIndex(),chosen=0;
            var sites=new ArrayList<Anchor>();
            if(ground.hasSurface() && !ground.hasFluid())for(var rule:biomeRules.getOrDefault(biome,List.of())) {
                long cellHash=mix(root^rule.salt);
                int candidates=densityPlacements(rule.count*25.0/256.0,cellHash);
                for(int candidate=0;candidate<candidates;candidate++) {
                    checkCurrent();long hash=candidate==0?cellHash:mix(cellHash^candidate*0x632be59bd9b4e019L);
                    int x=candidate==0?anchorX:cx*5+(int)Long.remainderUnsigned(mix(hash^11),5)-2;
                    int z=candidate==0?anchorZ:cz*5+(int)Long.remainderUnsigned(mix(hash^17),5)-2;
                    var support=candidate==0?ground:anchorGround.sample(x,z);
                    if(candidate!=0)anchorSamples++;
                    if(!support.hasSurface() || support.hasFluid()
                            || !biomeRules.getOrDefault(support.biomeIndex(),List.of()).contains(rule))continue;
                    chosen++;
                    double value=unit(mix(hash^23))*rule.count;
                    Choice choice=rule.choices.getLast();
                    for(var c:rule.choices){value-=c.weight;if(value<0){choice=c;break;}}
                    var model=choice.models.get((int)Long.remainderUnsigned(mix(hash^31),choice.models.size()));
                    BlockState soil=BuiltInRegistries.BLOCK.byId(support.topBlockIndex()).defaultBlockState();
                    if(!model.soils.isEmpty() && model.soils.stream().anyMatch(predicate->!predicate.matches(soil)))continue;
                    sites.add(new Anchor(x,support.surfaceY(),z,hash,model));
                }
            }
            cells[i]=new SelectionCell(biome,chosen,List.copyOf(sites));bytes+=sites.size()*64L;
        }
        return new SelectionPage(cells,bytes);
    }
    private List<Span> rotated(Template model,int rotation) {
        if(rotation==0)return model.spans;
        return rotations.get(new RotationKey(model,rotation),()->{
            var result=new ArrayList<Span>(model.spans.size());
            for(var s:model.spans) {
                int x=s.x,z=s.z;
                if(rotation==1){x=-s.z;z=s.x;}else if(rotation==2){x=-s.x;z=-s.z;}else{x=s.z;z=-s.x;}
                result.add(new Span(x,z,s.bottom,s.top,s.state.rotate(net.minecraft.world.level.block.Rotation.values()[rotation])));
            }
            return List.copyOf(result);
        },this::checkCurrent);
    }

    Result generate(int bx,int bz,int span,int step,boolean coarse) {
        reset();
        var boxes=new PredictionVoxyBoxes.Builder();
        var coarseVoxels=coarse?new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Votes>():null;
        var sites=new ArrayList<Anchor>();
        // Fixed 5-block world grid, including template radius on all sides. Coordinates never depend on target LOD.
        int minX=Math.floorDiv(bx-26,5),maxX=Math.floorDiv(bx+span+25,5);
        int minZ=Math.floorDiv(bz-26,5),maxZ=Math.floorDiv(bz+span+25,5);
        long pageKey=Long.MIN_VALUE;
        SelectionPage page=null;
        for(int gz=minZ;gz<=maxZ;gz++)for(int gx=minX;gx<=maxX;gx++) {
            checkCurrent(); anchors++;
            long root=anchorGround.root(gx,gz);
            int anchorX=anchorGround.x(gx,root),anchorZ=anchorGround.z(gz,root);
            if(!PredictionDecorationRange.placementPoint(anchorX,anchorZ) || isCaptured(anchorX,anchorZ))continue;
            int px=Math.floorDiv(gx,8),pz=Math.floorDiv(gz,8);
            long selectionKey=(long)px<<32 | pz&0xffffffffL;
            if(page==null || pageKey!=selectionKey) {
                final int pageX=px,pageZ=pz;
                page=selections.get(selectionKey,()->selectPage(pageX*8,pageZ*8),this::checkCurrent);pageKey=selectionKey;
            }
            var selection=page.cells[Math.floorMod(gz,8)*8+Math.floorMod(gx,8)];
            String biome=sampler.cacheBiomes().get(selection.biome);
            groundByBiome.merge(biome,1L,Long::sum);selected+=selection.selected;
            for(var site:selection.sites) {
                int x=site.x,z=site.z;
                if(!PredictionDecorationRange.placementPoint(x,z) || isCaptured(x,z))continue;
                var model=site.model;
                if(coarse && step>=4 && model.height<=2 && !model.woody){tinyOmitted++;continue;}
                accepted++;
                if(model.woody)acceptedByBiome.merge(biome,1L,Long::sum);
                int rotation=(int)mix(site.id^41)&3;
                sites.add(site);
                for(var s:rotated(model,rotation)) {
                    int wx=x+s.x,wz=z+s.z;
                    int localX=wx-bx,localZ=wz-bz;
                    if(localX<0||localZ<0||localX>=span||localZ>=span || isCaptured(wx,wz))continue;
                    // Accumulate real coverage; a thin leaf edge must not automatically fill a coarse cell.
                    int sx=coarse?Math.floorDiv(localX,step)*step:localX;
                    int sz=coarse?Math.floorDiv(localZ,step)*step:localZ;
                    int width=coarse?step:1;
                    BlockState rotated=s.state;
                    int bottom=site.y+s.bottom,top=site.y+s.top;
                    if(displayGround!=null && s.bottom==0 && s.state.is(BlockTags.LOGS)) {
                        int cellX=Math.min(displayGround.axis()-1,localX/displayGround.step());
                        int cellZ=Math.min(displayGround.axis()-1,localZ/displayGround.step());
                        int support=Integer.MIN_VALUE;
                        for(var run:displayGround.columns()[cellZ*displayGround.axis()+cellX].runs())
                            if(!run.state().isAir() && run.state().getFluidState().isEmpty())
                                support=Math.max(support,run.top());
                        // Only bridge to the visible surface, never an underground material boundary.
                        if(support!=Integer.MIN_VALUE && bottom-support<=step)
                            bottom=Math.min(bottom,support);
                    }
                    if(coarse) {
                        for(int y=Math.floorDiv(bottom,step)*step;y<top;y+=step) {
                            long key=BlockPos.asLong(sx,y,sz);
                            var votes=coarseVoxels.get(key);
                            if(votes==null){votes=new Votes(step);coarseVoxels.put(key,votes);}
                            votes.add(rotated,localX-sx,localZ-sz,Math.max(y,bottom)-y,Math.min(y+step,top)-y);
                        }
                    } else boxes.add(sx,bottom,sz,Math.min(span,sx+width),top,Math.min(span,sz+width),rotated);
                    emitted++;
                }
            }
        }
        if(coarse) {
            long[] keys=coarseVoxels.keySet().toLongArray();Arrays.sort(keys);
            // Publish compact target-resolution occupancy. Avoid a full-resolution block map followed by downsampling.
            for(int i=0;i<keys.length;) {
                long p=keys[i++];int x=BlockPos.getX(p),z=BlockPos.getZ(p),bottom=BlockPos.getY(p),top=bottom+step;
                BlockState state=coarseVoxels.get(p).winner();
                if(state.isAir())continue;
                while(i<keys.length && BlockPos.getX(keys[i])==x && BlockPos.getZ(keys[i])==z
                        && BlockPos.getY(keys[i])==top && coarseVoxels.get(keys[i]).winner()==state) {top+=step;i++;}
                boxes.add(x,bottom,z,Math.min(span,x+step),top,Math.min(span,z+step),state);
            }
        }
        return new Result(boxes.build(),List.copyOf(sites));
    }
    Map<String,Object> stats() {
        return Map.of("anchors",anchors,"selected",selected,"accepted",accepted,"anchorSamples",anchorSamples,
                "rawSpans",emitted,"tinyOmitted",tinyOmitted,"woodyByBiome",Map.copyOf(acceptedByBiome),"groundByBiome",Map.copyOf(groundByBiome),
                "selectionCache",selections.statistics(),"rotationCache",rotations.statistics());
    }
    Map<String,Object> initialization() {
        return Map.of("biomes",biomeRules.size(),"replacedFeatures",replaced.size(),"parsedFeatures",parsed.size(),
                "templateAttempts",templateAttempts,"templateFailures",templateFailures,
                "templates",templates.values().stream().mapToInt(List::size).sum(),"features",audit);
    }
    /** Stable stochastic rounding preserves expected biome density above one placement per grid cell. */
    static int densityPlacements(double expected,long hash) {
        int whole=(int)Math.floor(expected);
        return whole+(unit(hash)<expected-whole?1:0);
    }
    static long mix(long h) { h=(h^(h>>>30))*0xbf58476d1ce4e5b9L;h=(h^(h>>>27))*0x94d049bb133111ebL;return h^(h>>>31); }
    static double unit(long h) { return (h>>>11)*0x1.0p-53; }
    static long stringHash(String s) { long h=0xcbf29ce484222325L;for(int i=0;i<s.length();i++)h=(h^s.charAt(i))*0x100000001b3L;return mix(h); }
}
