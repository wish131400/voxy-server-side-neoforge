package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import dev.xantha.vss.mixin.client.SurfaceRulesContextAccessor;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.RandomState;

/** Real UTF condition constructors/computations with the production preview Mixins applied. */
final class UltraTerraForgedSurfaceReleasedTest {
    static void verify(ClassLoader loader, Object generatorContext) throws Exception {
        String prefix = "com.pandaismyname1.ultraterraforged.world.worldgen.";
        Object cache = generatorContext.getClass().getField("cache").get(generatorContext);
        Object generator = generatorContext.getClass().getField("generator").get(generatorContext);
        var heightmap = generator.getClass().getDeclaredField("heightmap"); heightmap.setAccessible(true);
        Object original = heightmap.get(generator);
        var batches = new AtomicInteger();
        var stateType = loader.loadClass(prefix + "UTFRandomState");
        var state = mock(RandomState.class, withSettings().extraInterfaces(stateType));
        stateType.getMethod("generatorContext").invoke(doReturn(generatorContext).when(state));
        Class<?> contextType = Class.forName("net.minecraft.world.level.levelgen.SurfaceRules$Context");
        Object context = mock(contextType, withSettings().extraInterfaces(SurfaceRulesContextAccessor.class).defaultAnswer(i -> {
            if (i.getMethod().getName().startsWith("vss$")) {
                var f = contextType.getDeclaredField(i.getMethod().getName().substring(4)); f.setAccessible(true); return f.get(i.getMock());
            }
            return RETURNS_DEFAULTS.answer(i);
        }));
        set(context,"randomState",state);
        var chunk = mock(ChunkAccess.class);
        when(chunk.getPos()).thenReturn(new ChunkPos(6301,-7412)); set(context,"chunk",chunk);
        int x = 100821, z = -118581; set(context,"blockX",x);set(context,"blockZ",z);
        var rules = new FreeTerraForgedSurfaceRules(generatorContext);
        var near = loader.loadClass(prefix + "surface.condition.NearGroundCondition");
        var nearCtor = near.getDeclaredConstructor(contextType,int.class); nearCtor.setAccessible(true);
        var nearCompute = near.getDeclaredMethod("compute"); nearCompute.setAccessible(true);
        var terrain = loader.loadClass(prefix + "surface.condition.TerrainCondition");
        var terrainCtor = terrain.getDeclaredConstructor(contextType,Set.class); terrainCtor.setAccessible(true);
        var base = terrain.getSuperclass(); var compute = base.getDeclaredMethod("compute"); compute.setAccessible(true);
        Class<?> cellType = loader.loadClass(prefix + "cell.Cell");
        var cellTest = terrain.getDeclaredMethod("test",cellType,int.class,int.class); cellTest.setAccessible(true);
        Object cell = cellType.getConstructor().newInstance();
        Object lookup = generatorContext.getClass().getField("lookup").get(generatorContext);
        lookup.getClass().getMethod("apply",cellType,int.class,int.class).invoke(lookup,cell,x,z);
        Object levels = generatorContext.getClass().getField("levels").get(generatorContext);
        int ground = (int) levels.getClass().getMethod("scale",float.class).invoke(levels,cellType.getField("height").getFloat(cell));
        Set<Object> kinds = Set.of(cellType.getField("terrain").get(cell));
        try {
            heightmap.set(generator, new ThreadLocal<>() {
                @Override public Object get() { batches.incrementAndGet(); throw new IllegalStateException("unexpected full UTF tile"); }
            });
            assertFalse(FreeTerraForgedSurfaceRules.pointRules(cache));
            long start = System.nanoTime();
            Object[] saved = new Object[2];
            rules.preview(() -> {
                try {
                    assertTrue(FreeTerraForgedSurfaceRules.pointRules(cache));
                    assertFalse(FreeTerraForgedSurfaceRules.pointRules(new Object()));
                    saved[0]=nearCtor.newInstance(context,5); saved[1]=terrainCtor.newInstance(context,kinds);
                    for (int y : new int[]{ground-6,ground-5,ground,ground+1,ground+2}) {
                        set(context,"blockY",y);
                        assertEquals(y>=ground-5 && y<=ground+1,nearCompute.invoke(saved[0]));
                        assertEquals(cellTest.invoke(saved[1],cell,x,z),compute.invoke(saved[1]));
                    }
                    return null;
                } catch (Exception e) { throw new IllegalStateException(e); }
            });
            // Cached preview rules are reused during a later column request.
            rules.preview(() -> { try { set(context,"blockY",ground); assertEquals(true,nearCompute.invoke(saved[0])); return null; }
                catch(Exception e) { throw new IllegalStateException(e); } });
            rules.preview(() -> {
                try {
                    for (int[] p : List.of(new int[]{100823,-118583},new int[]{-13,-29},new int[]{x,z})) {
                        set(context,"blockX",p[0]); set(context,"blockZ",p[1]);
                        cellType.getMethod("reset").invoke(cell);
                        lookup.getClass().getMethod("apply",cellType,int.class,int.class).invoke(lookup,cell,p[0],p[1]);
                        int h=(int)levels.getClass().getMethod("scale",float.class).invoke(levels,cellType.getField("height").getFloat(cell));
                        set(context,"blockY",h);
                        assertEquals(true,nearCompute.invoke(saved[0]),"reused preview rule follows the current column");
                        assertEquals(cellTest.invoke(saved[1],cell,p[0],p[1]),compute.invoke(saved[1]));
                    }
                    return null;
                } catch(Exception e) { throw new IllegalStateException(e); }
            });
            assertThrows(IllegalArgumentException.class, () -> rules.preview(() -> {throw new IllegalArgumentException("cancel");}));
            assertFalse(FreeTerraForgedSurfaceRules.pointRules(cache));
            assertEquals(0,batches.get(),"preview material rules must never generate a full tile");
            // Exact/server rule construction must keep requesting the original filtered chunk.
            assertThrows(java.lang.reflect.InvocationTargetException.class, () -> nearCtor.newInstance(context,5));
            assertTrue(batches.get()>0,"outside preview the original tile path remains active");
            System.out.println("Released UTF surface previews: original conditions agree with point cells, zero full-tile batches; scope/cache isolation verified; ms="+(System.nanoTime()-start)/1e6);
        } finally { heightmap.set(generator,original); }
    }
    private static void set(Object context,String name,Object value) throws Exception {
        var f=context.getClass().getSuperclass().getDeclaredField(name); f.setAccessible(true); f.set(context,value);
    }
}
