param(
    [string]$OutputDirectory = ('build/generation-comparison-' + (Get-Date -Format 'yyyyMMdd-HHmmss')),
    [string]$JavaHome,
    [string]$Python = 'python'
)
$ErrorActionPreference = 'Stop'
$comparisonProject = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../..')).Path
$comparisonOutput = if ([IO.Path]::IsPathRooted($OutputDirectory)) {
    [IO.Path]::GetFullPath($OutputDirectory)
} else { [IO.Path]::GetFullPath((Join-Path $comparisonProject $OutputDirectory)) }
if (Test-Path -LiteralPath $comparisonOutput) { throw 'Use a new output directory; existing chunks invalidate the cold-generation comparison.' }
$comparisonServer = New-Item -ItemType Directory -Path (Join-Path $comparisonOutput 'server')
Set-Content -LiteralPath (Join-Path $comparisonServer 'eula.txt') -Value 'eula=true' -Encoding ascii
@'
server-ip=127.0.0.1
server-port=0
online-mode=false
level-seed=42
level-name=benchmark-world
view-distance=2
simulation-distance=2
spawn-protection=0
max-players=1
sync-chunk-writes=false
max-tick-time=-1
allow-nether=false
spawn-monsters=false
spawn-animals=false
spawn-npcs=false
'@ | Set-Content -LiteralPath (Join-Path $comparisonServer 'server.properties') -Encoding ascii
$comparisonArgs = @('--offline', '-I', (Join-Path $PSScriptRoot 'benchmark.gradle'),
    "-PvssComparisonOutput=$comparisonOutput", '--console=plain', '--max-workers=2')
if ($JavaHome) { $comparisonArgs = @("-Dorg.gradle.java.home=$JavaHome") + $comparisonArgs }
Push-Location -LiteralPath $comparisonProject
try {
    & .\gradlew.bat @comparisonArgs '-PvssComparisonPhase=generation' runServer *> (Join-Path $comparisonOutput 'server-launch.log')
    if ($LASTEXITCODE -ne 0 -or !(Test-Path -LiteralPath (Join-Path $comparisonOutput 'server-complete.txt'))) { throw 'Real generation failed; inspect server-launch.log.' }
    & .\gradlew.bat @comparisonArgs '-PvssComparisonPhase=prediction' runServer *> (Join-Path $comparisonOutput 'prediction-server-launch.log')
    if ($LASTEXITCODE -ne 0 -or !(Test-Path -LiteralPath (Join-Path $comparisonOutput 'prediction-complete.txt'))) { throw 'Prediction failed; inspect prediction-server-launch.log.' }
    & $Python (Join-Path $PSScriptRoot 'summarize.py') $comparisonOutput
    if ($LASTEXITCODE -ne 0) { throw 'Benchmark validation failed.' }
} finally { Pop-Location }
