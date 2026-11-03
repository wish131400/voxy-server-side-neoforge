package dev.xantha.vss.benchmark;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload;
import dev.xantha.vss.networking.server.session.WorldgenProfileHolder;
import dev.xantha.vss.networking.server.session.WorldgenTemplateCatalog;
import io.netty.buffer.Unpooled;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/** Opt-in isolated-world benchmark. Never registered in normal production builds. */
@EventBusSubscriber(modid = "vss")
public final class FullGenerationComparison {
    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        if (System.getProperty("vss.comparison.output") == null) return;
        MinecraftServer server = event.getServer();
        // Run on the real server thread; getChunk pumps its managed worldgen jobs.
        server.execute(() -> run(server));
    }

    private static void run(MinecraftServer server) {
        Path output = Path.of(System.getProperty("vss.comparison.output"));
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        try {
            Files.createDirectories(output);
            var level = server.overworld();
            level.noSave = true;
            level.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(0, server);
            if (System.getProperty("vss.comparison.phase", "generation").equals("prediction")) {
                dev.xantha.vss.client.prediction.PredictionGenerationComparison.run(output);
                return;
            }
            if (Files.exists(output.resolve("server-complete.txt")))
                throw new IllegalStateException("Use a fresh output directory and world for the generation phase");
            var payload = WorldgenProfileHolder.payloadFor(server, 1, net.minecraft.world.level.Level.OVERWORLD);
            var buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                WorldgenProfileS2CPayload.encode(payload, buffer);
                byte[] bytes = new byte[buffer.readableBytes()]; buffer.readBytes(bytes);
                Files.write(output.resolve("worldgen-profile.bin"), bytes);
            } finally { buffer.release(); }
            // Offline prediction receives the exact same server structure resources.
            JsonObject templates = new JsonObject();
            for (var entry : WorldgenTemplateCatalog.snapshot(server.getResourceManager()).entries().entrySet())
                templates.addProperty(entry.getKey().toString(), Base64.getEncoder().encodeToString(entry.getValue().read()));
            Files.writeString(output.resolve("structure-templates.json"), templates.toString());
            Files.writeString(output.resolve("environment.json"), new GsonBuilder().setPrettyPrinting().create().toJson(Map.of(
                    "seed", level.getSeed(), "minecraft", "1.21.1", "neoforge", "21.1.234",
                    "java", System.getProperty("java.version"), "processors", Runtime.getRuntime().availableProcessors(),
                    "sideChunks", 16, "warmupRounds", 2, "measuredRounds", 5,
                    "scope", "FULL chunks including required neighbours; startup/save/export excluded; no C2ME, shaders or players")));
            Path csv = output.resolve("full-generation.csv");
            Files.writeString(csv, "round,warmup,chunkX,chunkZ,chunks,wallMs,processCpuMs,loadedBefore,loadedAfter,lightCorrect,nonemptySections,biomes\n");
            for (int round = -2; round < 5; round++) {
                int cx = 8192 + round * 512, cz = -8192 - round * 512;
                int loadedBefore = level.getChunkSource().getLoadedChunksCount();
                long cpu = os.getProcessCpuTime(), start = System.nanoTime();
                for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    var chunk = level.getChunk(cx + x, cz + z);
                    if (chunk.getPersistedStatus() != ChunkStatus.FULL) throw new AssertionError("Incomplete real chunk");
                }
                double wall = (System.nanoTime() - start) / 1e6, cpuMs = (os.getProcessCpuTime() - cpu) / 1e6;
                int loadedAfter = level.getChunkSource().getLoadedChunksCount();
                int lit = 0, sections = 0;
                Map<String,Integer> biomes = new LinkedHashMap<>();
                for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    var chunk = level.getChunk(cx + x, cz + z);
                    if (chunk.isLightCorrect()) lit++;
                    for (var section : chunk.getSections()) if (!section.hasOnlyAir()) sections++;
                    String biome = chunk.getNoiseBiome(2, 16, 2).unwrapKey().orElseThrow().location().toString();
                    biomes.merge(biome, 1, Integer::sum);
                }
                String row = round + "," + (round < 0) + "," + cx + "," + cz + ",256," + wall + "," + cpuMs
                        + "," + loadedBefore + "," + loadedAfter + "," + lit + "," + sections + ",\"" + biomes + "\"\n";
                Files.writeString(csv, row, java.nio.file.StandardOpenOption.APPEND);
                System.out.println("VSS_FULL_COMPARISON " + row.strip());
            }
            Files.writeString(output.resolve("server-complete.txt"), "success\n");
        } catch (Throwable failure) {
            failure.printStackTrace();
            try { Files.writeString(output.resolve("server-failure.txt"), failure.toString()); } catch (Exception ignored) { }
        } finally { server.halt(false); }
    }
}
