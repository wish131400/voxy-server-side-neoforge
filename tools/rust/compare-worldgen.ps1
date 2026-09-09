param(
    [Parameter(Mandatory)][string]$Before,
    [Parameter(Mandatory)][string]$After,
    [Parameter(Mandatory)][string]$Document,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [ValidateRange(1, 20)][int]$Runs = 5,
    [long]$Seed = 0
)
$ErrorActionPreference = 'Stop'
$beforeExe = (Resolve-Path -LiteralPath $Before).Path
$afterExe = (Resolve-Path -LiteralPath $After).Path
$documentPath = (Resolve-Path -LiteralPath $Document).Path
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$outputPath = (Resolve-Path -LiteralPath $OutputDirectory).Path
$profileFlag = $env:VSS_PROFILE_SURFACE
$env:VSS_PROFILE_SURFACE = $null
$measurements = [System.Collections.Generic.List[object]]::new()
$referenceChecksums = $null
try {
    for ($round = 1; $round -le $Runs; $round++) {
        # Alternate processes sequentially; competing benchmarks would measure
        # CPU contention instead of native throughput.
        $order = if ($round % 2) { @('before', 'after') } else { @('after', 'before') }
        foreach ($variant in $order) {
            $executable = if ($variant -eq 'before') { $beforeExe } else { $afterExe }
            $lines = @(& $executable $documentPath $Seed 2>&1 | ForEach-Object { "$_" })
            $exitCode = $LASTEXITCODE
            $lines | Set-Content -LiteralPath (Join-Path $outputPath "$variant-$round.log") -Encoding utf8
            if ($exitCode -ne 0) { throw "$variant round $round failed with exit code $exitCode" }
            $checksums = @($lines | Where-Object { $_ -match 'checksum' }) -join "`n"
            if (@($lines | Where-Object { $_ -match 'checksum' }).Count -ne 5) {
                throw 'Both benchmark binaries must emit three base, one point and one proxy checksum.'
            }
            if ($null -eq $referenceChecksums) { $referenceChecksums = $checksums }
            if ($referenceChecksums -cne $checksums) { throw "$variant round $round changed native output" }
            foreach ($line in $lines) {
                if ($line -match '^(.+): ([0-9.]+) ms') {
                    $measurements.Add([pscustomobject]@{
                        variant = $variant; round = $round; metric = $Matches[1]
                        milliseconds = [double]::Parse($Matches[2], [Globalization.CultureInfo]::InvariantCulture)
                    })
                }
            }
            Write-Output "$variant round $round completed; all output checksums match."
        }
    }
} finally { $env:VSS_PROFILE_SURFACE = $profileFlag }
function Median($values) {
    $sorted = @($values | Sort-Object)
    $mid = [int][Math]::Floor($sorted.Count / 2)
    if ($sorted.Count % 2) { return $sorted[$mid] }
    return ($sorted[$mid - 1] + $sorted[$mid]) / 2
}
$summary = @($measurements.metric | Select-Object -Unique | ForEach-Object {
    $metric = $_
    $old = @($measurements | Where-Object { $_.metric -eq $metric -and $_.variant -eq 'before' })
    $new = @($measurements | Where-Object { $_.metric -eq $metric -and $_.variant -eq 'after' })
    if ($old.Count -ne $Runs -or $new.Count -ne $Runs) { throw "Missing measurements: $metric" }
    $a = Median $old.milliseconds
    $b = Median $new.milliseconds
    [pscustomobject]@{
        metric = $metric; before_median_ms = $a; after_median_ms = $b
        time_reduction_percent = if ($a -gt 0) { [Math]::Round((1 - $b / $a) * 100, 2) } else { $null }
        speedup = if ($b -gt 0) { [Math]::Round($a / $b, 3) } else { $null }
    }
})
[ordered]@{
    seed = $Seed; rounds = $Runs; checksums = $referenceChecksums
    document_sha256 = (Get-FileHash -LiteralPath $documentPath -Algorithm SHA256).Hash
    before_sha256 = (Get-FileHash -LiteralPath $beforeExe -Algorithm SHA256).Hash
    after_sha256 = (Get-FileHash -LiteralPath $afterExe -Algorithm SHA256).Hash
    measurements = @($measurements.ToArray()); summary = $summary
} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $outputPath 'results.json') -Encoding utf8
$summary | Format-Table -AutoSize
