package dev.xantha.vss.networking.server.compat;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class LostCityHintServiceApiTest {
    @Test
    void queriesActualLostCitiesApiWithBoundedNegativeRegion() throws Exception {
        String jar = System.getProperty("vss.lostCitiesJar");
        org.junit.jupiter.api.Assumptions.assumeTrue(jar != null, "Supply -PvssLostCitiesJar to verify a release API");
        try (var loader = new URLClassLoader(new java.net.URL[] {Path.of(jar).toUri().toURL()}, getClass().getClassLoader())) {
            var chunkType = loader.loadClass("mcjty.lostcities.api.ILostChunkInfo");
            var infoType = loader.loadClass("mcjty.lostcities.api.ILostCityInformation");
            AtomicInteger count = new AtomicInteger();
            Object info = Proxy.newProxyInstance(loader, new Class<?>[] {infoType}, (proxy, method, args) -> {
                if (method.getName().equals("getRealHeight")) return 64 + (int) args[0] * 6;
                if (!method.getName().equals("getChunkInfo")) throw new AssertionError(method.getName());
                int x = (int) args[0], z = (int) args[1];
                assertTrue(x >= -8 && x < 0 && z >= -16 && z < -8);
                count.incrementAndGet();
                boolean building = x == -8 && z == -16;
                return Proxy.newProxyInstance(loader, new Class<?>[] {chunkType}, (p, m, a) -> switch (m.getName()) {
                    case "getBuildingId" -> building ? ResourceLocation.parse("lostcities:building1") : null;
                    case "isCity" -> building;
                    case "getCityLevel" -> 2;
                    case "getNumFloors" -> 4;
                    case "getCityStyle" -> null;
                    default -> throw new AssertionError(m.getName());
                });
            });
            var summary = LostCityHintService.query(info, -1, -2);
            assertEquals(64, count.get());
            assertEquals(64, summary.size());
            assertEquals(2, summary.get(0).kind());
            assertEquals(76, summary.get(0).ground());
            assertTrue(summary.get(0).floors().isEmpty(),
                    "the API proxy has no BuildingInfo template methods, so it should use the bounded surface summary");
            for (int i = 1; i < summary.size(); i++) assertEquals(0, summary.get(i).kind());
        }
    }
}
