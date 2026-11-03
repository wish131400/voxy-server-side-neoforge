param(
    [string]$OutputDirectory = ('build/decoration-experiment-' + (Get-Date -Format 'yyyyMMdd-HHmmss')),
    [string]$Inputs = 'build/generation-comparison-20261031',
    [string]$Python = 'python',
    [string]$JavaHome = 'C:/Program Files/Java/jdk-21',
    [ValidateRange(1,100)][int]$Rounds = 5,
    [ValidateRange(0,100)][int]$Warmups = 2,
    [ValidateSet(8,16)][int]$Side = 16,
    [string]$Modes = 'baseline,baseline_repeat,biome,page2,page4,scan,combined4,page4_scan,baseline_tail',
    [switch]$VerifyBiomes,
    [switch]$Native,
    [switch]$RawWorkspace,
    [switch]$LeanMetadata
)
$ErrorActionPreference = 'Stop'
$experimentModes = @($Modes.Split(',') | ForEach-Object { $_.Trim() })
$experimentKnownModes = @('baseline', 'baseline_repeat', 'baseline_tail', 'biome', 'page2', 'page4',
    'scan', 'combined', 'combined4', 'page4_scan', 'single', 'scratch', 'scratch_single',
    'lean', 'lean_scratch_single', 'raw', 'raw_single', 'lean_raw_single')
if ($experimentModes[0] -ne 'baseline' -or ($experimentModes | Select-Object -Unique).Count -ne $experimentModes.Count) {
    throw 'Modes must start with baseline and contain no duplicates.'
}
foreach ($experimentMode in $experimentModes) {
    if ($experimentMode -notin $experimentKnownModes) { throw "Unknown experiment mode: $experimentMode" }
    if ($experimentMode.Contains('raw') -and -not $RawWorkspace) { throw 'Raw modes require -RawWorkspace.' }
    if ($experimentMode.StartsWith('lean') -and -not $LeanMetadata) { throw 'Lean modes require -LeanMetadata.' }
    if (($experimentMode.Contains('scratch') -or $experimentMode.EndsWith('single')) -and -not ($Native -or $RawWorkspace)) {
        throw 'Native modes require -Native or -RawWorkspace.'
    }
}
$Modes = $experimentModes -join ','
$experimentProject = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../..')).Path
Push-Location -LiteralPath $experimentProject
try {
    & $Python (Join-Path $PSScriptRoot 'prepare.py') $OutputDirectory --inputs $Inputs
    if ($LASTEXITCODE -ne 0) { throw 'Experiment snapshot preparation failed.' }
    $experimentOutput = (Resolve-Path -LiteralPath $OutputDirectory).Path
    if ($RawWorkspace) { $Native = $true }
    if ($Native) {
        & $Python (Join-Path $PSScriptRoot 'prepare_native.py') $experimentOutput
        if ($LASTEXITCODE -ne 0) { throw 'Native snapshot preparation failed.' }
        if ($RawWorkspace) {
            & $Python (Join-Path $PSScriptRoot 'prepare_raw.py') $experimentOutput
            if ($LASTEXITCODE -ne 0) { throw 'Raw workspace patch failed.' }
        }
        $experimentCargo = Join-Path $experimentOutput 'native-source/Cargo.toml'
        & cargo test --manifest-path $experimentCargo --locked --offline --release --lib *> (Join-Path $experimentOutput 'native-test.log')
        if ($LASTEXITCODE -ne 0) { throw 'Native experiment tests failed.' }
        & cargo build --manifest-path $experimentCargo --locked --offline --release *> (Join-Path $experimentOutput 'native-build.log')
        if ($LASTEXITCODE -ne 0) { throw 'Native experiment build failed.' }
        $experimentDll = Join-Path $experimentOutput 'native-source/target/release/vss_native_core.dll'
        Copy-Item -LiteralPath $experimentDll -Destination (Join-Path $experimentOutput 'main-resources/META-INF/vss-natives/windows-x86_64/vss_native_core.dll')
        $experimentManifestPath = Join-Path $experimentOutput 'input-manifest.json'
        $experimentManifest = Get-Content -LiteralPath $experimentManifestPath -Raw | ConvertFrom-Json
        $experimentManifest | Add-Member -NotePropertyName experimentDllSha256 -NotePropertyValue ((Get-FileHash -LiteralPath $experimentDll -Algorithm SHA256).Hash.ToLowerInvariant())
        $experimentManifest | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $experimentManifestPath -Encoding utf8NoBOM
    }
    if ($LeanMetadata) {
        & $Python (Join-Path $PSScriptRoot 'prepare_lean.py') $experimentOutput
        if ($LASTEXITCODE -ne 0) { throw 'Lean metadata patch failed.' }
    }
    $experimentArguments = @("-Dorg.gradle.java.home=$JavaHome", '--offline', '-I',
        (Join-Path $PSScriptRoot 'experiment.gradle'), "-PvssExperimentRoot=$experimentOutput",
        "-PvssExperimentRounds=$Rounds", "-PvssExperimentWarmups=$Warmups", "-PvssExperimentSide=$Side",
        "-PvssExperimentModes=$Modes", "-PvssExperimentVerifyBiomes=$($VerifyBiomes.IsPresent.ToString().ToLowerInvariant())",
        '--console=plain', '--max-workers=2', 'runServer')
    & .\gradlew.bat @experimentArguments *> (Join-Path $experimentOutput 'launch.log')
    if ($LASTEXITCODE -ne 0) { throw 'Experiment runtime failed; inspect launch.log.' }
    & $Python (Join-Path $PSScriptRoot 'summarize.py') $experimentOutput
    if ($LASTEXITCODE -ne 0) { throw 'Experiment validation failed.' }
} finally { Pop-Location }
