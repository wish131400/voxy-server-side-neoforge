package dev.xantha.vss.common;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.world.level.Level;

public final class BlockEntityTickerCompactor {
    private static final Set<Level> PENDING_LEVELS = Collections.newSetFromMap(new WeakHashMap<>());

    private BlockEntityTickerCompactor() {
    }

    public static synchronized void request(Level level) {
        if (level != null) {
            PENDING_LEVELS.add(level);
        }
    }

    public static synchronized boolean consume(Level level) {
        return level != null && PENDING_LEVELS.remove(level);
    }
}
