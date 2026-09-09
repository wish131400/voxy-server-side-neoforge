param(
    [Parameter(Mandatory=$true)][string]$Before,
    [Parameter(Mandatory=$true)][string]$After,
    [Parameter(Mandatory=$true)][string]$Document,
    [Parameter(Mandatory=$true)][string]$OutputDirectory,
    [int]$Runs = 3
)
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$rows = [System.Collections.Generic.List[object]]::new()
for ($run = 1; $run -le $Runs; $run++) {
    foreach ($spacing in @(4, 1024)) {
        $order = if ($run % 2 -eq 1) { @('before', 'after') } else { @('after', 'before') }
        foreach ($variant in $order) {
            $exe = if ($variant -eq 'before') { $Before } else { $After }
            $mode = if ($variant -eq 'before' -and $spacing -eq 4) { 'dense' } else { 'sparse' }
            $output = & $exe $Document $spacing $mode
            if ($LASTEXITCODE -ne 0) { throw "Benchmark failed: $variant" }
            $output | Set-Content -Encoding utf8 (Join-Path $OutputDirectory "$run-$spacing-$variant.txt")
            if ($output -notmatch 'total_ms=([0-9.]+), longest_batch_ms=([0-9.]+), checksum=([a-f0-9]+)') { throw 'Missing timing' }
            $row = [pscustomobject]@{run=$run; spacing=$spacing; variant=$variant; mode=$mode;
                milliseconds=[double]::Parse($Matches[1],[cultureinfo]::InvariantCulture);
                longestBatchMilliseconds=[double]::Parse($Matches[2],[cultureinfo]::InvariantCulture); checksum=$Matches[3]}
            $rows.Add($row)
            Write-Output "$run $variant $output"
        }
        $pair = @($rows | Where-Object { $_.run -eq $run -and $_.spacing -eq $spacing })
        if ($pair[0].checksum -ne $pair[1].checksum) { throw 'Terrain/material/tint output changed' }
    }
}
$rows | ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $OutputDirectory 'results.json')
