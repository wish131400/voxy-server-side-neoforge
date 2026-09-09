package dev.xantha.vss.client.prediction;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.levelgen.feature.SpikeFeature;
import net.minecraft.world.level.levelgen.feature.configurations.SpikeConfiguration;

/** Vanilla spike block geometry without spawning entities in a live ServerLevel. */
final class PredictionEndSpikes {
    private PredictionEndSpikes() { }

    static void place(PredictionDecorationLevel level, RandomSource random, SpikeConfiguration config, BlockPos origin) {
        List<SpikeFeature.EndSpike> spikes = config.getSpikes().isEmpty() ? SpikeFeature.getSpikesForLevel(level) : config.getSpikes();
        for (var spike : spikes) {
            if (!spike.isCenterWithinChunk(origin)) continue;
            int radius = spike.getRadius(), cx = spike.getCenterX(), cz = spike.getCenterZ(), height = spike.getHeight();
            for (var pos : BlockPos.betweenClosed(new BlockPos(cx-radius,level.getMinBuildHeight(),cz-radius),
                    new BlockPos(cx+radius,height+10,cz+radius))) {
                if (pos.distToLowCornerSqr(cx,pos.getY(),cz) <= radius*radius+1 && pos.getY() < height)
                    level.setBlock(pos,Blocks.OBSIDIAN.defaultBlockState(),2,0);
                else if (pos.getY() > 65) level.setBlock(pos,Blocks.AIR.defaultBlockState(),2,0);
            }
            if (spike.isGuarded()) for (int x=-2;x<=2;x++) for (int z=-2;z<=2;z++) for (int y=0;y<=3;y++) {
                if (Math.abs(x)!=2 && Math.abs(z)!=2 && y!=3) continue;
                boolean alongX = Math.abs(x)==2 || y==3, alongZ = Math.abs(z)==2 || y==3;
                var bars = Blocks.IRON_BARS.defaultBlockState()
                        .setValue(IronBarsBlock.NORTH,alongX && z!=-2).setValue(IronBarsBlock.SOUTH,alongX && z!=2)
                        .setValue(IronBarsBlock.WEST,alongZ && x!=-2).setValue(IronBarsBlock.EAST,alongZ && x!=2);
                level.setBlock(new BlockPos(cx+x,height+y,cz+z),bars,2,0);
            }
            // Preserve the orientation draw used by vanilla's crystal spawn.
            // Entity models and beams are not part of the block prediction mesh.
            random.nextFloat();
            level.setBlock(new BlockPos(cx,height,cz),Blocks.BEDROCK.defaultBlockState(),2,0);
            level.setBlock(new BlockPos(cx,height+1,cz),Blocks.FIRE.defaultBlockState(),2,0);
        }
    }
}
