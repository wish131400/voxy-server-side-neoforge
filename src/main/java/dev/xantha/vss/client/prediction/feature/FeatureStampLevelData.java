package dev.xantha.vss.client.prediction.feature;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.storage.LevelData;

final class FeatureStampLevelData implements LevelData {
    private final GameRules gameRules = new GameRules();

    @Override public BlockPos getSpawnPos() { return BlockPos.ZERO; }
    @Override public float getSpawnAngle() { return 0.0F; }
    @Override public long getGameTime() { return 0L; }
    @Override public long getDayTime() { return 0L; }
    @Override public boolean isThundering() { return false; }
    @Override public boolean isRaining() { return false; }
    @Override public void setRaining(boolean raining) { }
    @Override public boolean isHardcore() { return false; }
    @Override public GameRules getGameRules() { return gameRules; }
    @Override public Difficulty getDifficulty() { return Difficulty.NORMAL; }
    @Override public boolean isDifficultyLocked() { return false; }
}
