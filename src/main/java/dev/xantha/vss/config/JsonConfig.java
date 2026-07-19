package dev.xantha.vss.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.xantha.vss.common.VSSLogger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import net.neoforged.fml.loading.FMLPaths;

public abstract class JsonConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    protected abstract String getFileName();

    protected void validate() {
    }

    protected Map<String, String> getConfigHelp() {
        return Map.of();
    }

    public void save() {
        try {
            Path path = resolvePath();
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(configWithHelp()));
        } catch (Exception e) {
            VSSLogger.error("Failed to save config " + getFileName(), e);
        }
    }

    private JsonObject configWithHelp() {
        JsonObject result = new JsonObject();
        Map<String, String> helpEntries = getConfigHelp();
        if (!helpEntries.isEmpty()) {
            JsonObject help = new JsonObject();
            helpEntries.forEach(help::addProperty);
            result.add("_help", help);
        }
        GSON.toJsonTree(this).getAsJsonObject().entrySet()
                .forEach(entry -> result.add(entry.getKey(), entry.getValue()));
        return result;
    }

    private Path resolvePath() {
        return FMLPaths.CONFIGDIR.get().resolve(getFileName());
    }

    protected static <T extends JsonConfig> T load(Class<T> type, String fileName) {
        Path path = FMLPaths.CONFIGDIR.get().resolve(fileName);
        boolean exists = Files.isRegularFile(path);
        if (exists) {
            try {
                T config = GSON.fromJson(Files.readString(path), type);
                if (config != null) {
                    config.validate();
                    config.save();
                    return config;
                }
                VSSLogger.warn("Config " + fileName + " was empty or invalid, using defaults");
            } catch (Exception e) {
                VSSLogger.error("Failed to read config " + fileName + ", using defaults", e);
            }
        }

        try {
            T config = type.getDeclaredConstructor().newInstance();
            config.validate();
            if (!exists) {
                config.save();
            }
            return config;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Cannot instantiate config " + type.getName(), e);
        }
    }
}
