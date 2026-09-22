param([string]$ForgeRoot = 'C:/Users/Administrator/Desktop/voxyserverside')
$ErrorActionPreference = 'Stop'
$sourceRoot = (Resolve-Path "$PSScriptRoot/../..").Path
$main = @('PredictionMeshResources','PredictionMeshCodec','PredictionTerrainArena','PredictionIndirectBatch',
    'PredictionDiskCache','PredictionMesh','PredictionSeamMesh','PredictionPackedMesh','PredictionGpuTile',
    'PredictionTileManager','PredictionTerrainProgram','PredictionRenderer','VssLodSpriteTable')
$tests = @('PredictionMeshCodecTest','PredictionProductionBatchGpuTest','PredictionMeshCacheIntegrationBenchmark','PredictionSpriteTableTest')
foreach ($entry in @(@{Folder='main';Names=$main},@{Folder='test';Names=$tests})) {
    foreach ($name in $entry.Names) {
        $relative = "src/$($entry.Folder)/java/dev/xantha/vss/client/prediction/$name.java"
        $code = [IO.File]::ReadAllText((Join-Path $sourceRoot $relative)).Replace("`r`n", "`n")
        $code = $code.Replace('.getFirst()', '.get(0)').Replace('Blocks.SHORT_GRASS','Blocks.GRASS')
        $code = $code.Replace('net.neoforged.neoforge.client.event.RenderLevelStageEvent','net.minecraftforge.client.event.RenderLevelStageEvent')
        $code = $code.Replace('net.neoforged.neoforge.client.event.RenderFrameEvent.Pre','net.minecraftforge.event.TickEvent.RenderTickEvent')
        $code = $code.Replace('event.getModelViewMatrix()', 'event.getPoseStack().last().pose()')
        $code = $code.Replace('.getOrThrow()', '.getOrThrow(false, message -> { throw new IllegalArgumentException(message); })')
        $code = $code.Replace('ResourceLocation.withDefaultNamespace(', 'new ResourceLocation("minecraft", ')
        if ($name -eq 'PredictionSpriteTableTest') {
            $code = $code.Replace('ResourceMetadata.EMPTY)', 'net.minecraft.client.resources.metadata.animation.AnimationMetadataSection.EMPTY)')
        }
        $code = $code.Replace('Math.clamp((int) Math.floor(x / tile.spacingBlocks()), 0, tile.cellAxis())','Math.max(0, Math.min(tile.cellAxis(), (int) Math.floor(x / tile.spacingBlocks())))')
        $code = $code.Replace('Math.clamp((int) Math.floor(z / tile.spacingBlocks()), 0, tile.cellAxis())','Math.max(0, Math.min(tile.cellAxis(), (int) Math.floor(z / tile.spacingBlocks())))')
        $code = $code.Replace('Math.clamp((int) Math.floor(sample.fluidY() - y), 0, 15)','Math.max(0, Math.min(15, (int) Math.floor(sample.fluidY() - y)))')
        if ($name -eq 'PredictionMesh') {
            $code = $code.Replace('consumer.addVertex(', 'consumer.vertex(').Replace('poseStack.last(),','poseStack.last().pose(),').Replace('.setColor(','.color(').Replace('.setNormal(','.normal(')
            $code = $code.Replace('.normal(normalX(index), normalY(index), normalZ(index));','.normal(normalX(index), normalY(index), normalZ(index)).endVertex();')
            $code = $code.Replace('.normal(waterNormals[position], waterNormals[position + 1], waterNormals[position + 2]);','.normal(waterNormals[position], waterNormals[position + 1], waterNormals[position + 2]).endVertex();')
        }
        [IO.File]::WriteAllText((Join-Path $ForgeRoot $relative),$code,[Text.UTF8Encoding]::new($false))
    }
}
Copy-Item -LiteralPath (Join-Path $sourceRoot 'tools/prediction/meridian-experiments.gradle') -Destination (Join-Path $ForgeRoot 'tools/prediction/meridian-experiments.gradle')
Write-Output 'Synchronized finished mesh cache and guarded indirect rendering with Forge API adaptations.'
