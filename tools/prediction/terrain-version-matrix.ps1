param(
    [string]$Fixtures = 'build/compat-fixtures',
    [string]$Reports = 'build/reports/terrain-version-matrix',
    [string]$Filter = '*',
    [switch]$Download
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Push-Location $root
try {
    New-Item -ItemType Directory -Force $Reports | Out-Null
    New-Item -ItemType Directory -Force $Fixtures | Out-Null
    $releases = Get-Content (Join-Path $PSScriptRoot 'terrain-releases.json') -Raw | ConvertFrom-Json
    foreach ($release in $releases) {
        $path = Join-Path $Fixtures $release.filename
        if (!(Test-Path -LiteralPath $path) -and $Download) {
            Invoke-WebRequest -Uri $release.url -OutFile $path
        }
        if (!(Test-Path -LiteralPath $path)) { throw "Missing $path; use -Download to fetch release fixtures." }
        if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $release.sha256) {
            throw "Release checksum mismatch: $path"
        }
    }
    $cases = @()
    foreach ($jar in Get-ChildItem "$Fixtures/reterraforged-*.jar") {
        $cases += @{ Name = $jar.BaseName; Property = "-PvssFreeTerraForgedJar=$($jar.FullName)"; Tests = @(
            '*FreeTerraForgedCompatTest', '*FreeTerraForgedNativeFiltersTest', '*FreeTerraForgedNativeNoiseTest') }
    }
    foreach ($jar in Get-ChildItem "$Fixtures/tectonic-*.jar") {
        $cases += @{ Name = $jar.BaseName; Property = "-PvssTectonicJar=$($jar.FullName)"; Tests = @(
            '*TectonicNativeTest', '*DensityFunctionSnapshotTest.installedTectonicDefaultConfigFunctionsRoundTrip',
            '*TerrainParityTest.tectonicPackMatchesMinecraftColumns') }
    }
    foreach ($jar in Get-ChildItem "$Fixtures/lithostitched*.jar") {
        $tests = @('*DensityFunctionSnapshotTest.installedLithostitchedAppliedGraphRoundTrips')
        if ($jar.Name -notlike '*1.4.11*') { $tests += '*LithostitchedNativeTest' }
        $cases += @{ Name = $jar.BaseName; Property = "-PvssLithostitchedJar=$($jar.FullName)"; Tests = $tests }
    }
    $cases += @{ Name = 'epic-terrain'; Property = "-PvssEpicTerrainJars=$Fixtures/epicterrain-0.1.4.jar;$Fixtures/epic-terrain_compatible-1.0.3.jar";
        Tests = @('*EpicTerrainCompatTest') }
    $results = @()
    foreach ($case in $cases | Where-Object { $_.Name -like $Filter }) {
        $destination = Join-Path $Reports $case.Name
        New-Item -ItemType Directory -Force $destination | Out-Null
        $arguments = @('-I', 'tools/prediction/compat-tests.gradle', $case.Property, 'test', '--rerun')
        foreach ($test in $case.Tests) { $arguments += @('--tests', $test) }
        $started = [datetime]::UtcNow
        & ./gradlew.bat @arguments *> (Join-Path $destination 'gradle.log')
        $code = $LASTEXITCODE
        $count = 0; $failed = 0; $skipped = 0
        if (Test-Path 'build/test-results/test') {
            foreach ($file in Get-ChildItem 'build/test-results/test/TEST-*.xml' | Where-Object { $_.LastWriteTimeUtc -ge $started }) {
                Copy-Item -LiteralPath $file.FullName -Destination $destination -Force
                [xml]$xml = Get-Content -LiteralPath $file.FullName
                $count += [int]$xml.testsuite.tests
                $failed += [int]$xml.testsuite.failures + [int]$xml.testsuite.errors
                $skipped += [int]$xml.testsuite.skipped
            }
        }
        $result = [pscustomobject]@{ Release = $case.Name; ExitCode = $code; Tests = $count; Failed = $failed; Skipped = $skipped }
        $results += $result
        $result | Format-Table -AutoSize
        $results | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $Reports 'results.json')
    }
    if (@($results | Where-Object { $_.ExitCode -ne 0 -or $_.Failed -ne 0 -or $_.Skipped -ne 0 -or $_.Tests -eq 0 }).Count) {
        throw 'Terrain release matrix failed; see per-release reports.'
    }
} finally {
    Pop-Location
}
