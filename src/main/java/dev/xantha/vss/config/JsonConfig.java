package dev.xantha.vss.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.xantha.vss.common.VSSLogger;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraftforge.fml.loading.FMLPaths;

public abstract class JsonConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    protected abstract String getFileName();

    protected void validate() {
    }

    public void save() {
        try {
            Path path = resolvePath();
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(this));
        } catch (Exception e) {
            VSSLogger.error("Failed to save config " + getFileName(), e);
        }
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
