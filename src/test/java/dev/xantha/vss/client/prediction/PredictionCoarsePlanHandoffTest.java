package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Field;
import java.util.*;
import dev.xantha.vss.client.prediction.PredictionTileManager.*;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.*;

class PredictionCoarsePlanHandoffTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }





    static Field field(String name) throws Exception {
        var field = PredictionTileManager.class.getDeclaredField(name); field.setAccessible(true); return field;
    }
}
