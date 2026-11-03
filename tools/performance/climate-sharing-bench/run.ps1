param(
    [Parameter(Mandatory=$true)][string]$Output,
    [string]$PythonExe='python',
    [string]$JavaHome=$env:JAVA_HOME,
    [int]$Rounds=5,
    [int]$Warmups=2,
    [switch]$Reverse,
    [switch]$Verify
)
$ErrorActionPreference='Stop'
$benchRoot=Resolve-Path (Join-Path $PSScriptRoot '../../..')
$benchOldJavaHome=$env:JAVA_HOME
Push-Location $benchRoot
try {
    if(-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) {throw 'Provide a Java 21 installation with -JavaHome.'}
    & $PythonExe tools/performance/climate-sharing-bench/prepare.py $Output
    if($LASTEXITCODE -ne 0){throw 'Snapshot preparation failed.'}
    & cargo build --release --offline --locked --manifest-path "$Output/native/Cargo.toml" *> "$Output/native-build.log"
    if($LASTEXITCODE -ne 0){throw "Native compilation failed; see $Output/native-build.log."}
    Copy-Item -LiteralPath "$Output/native/target/release/vss_native_core.dll" -Destination "$Output/main-resources/META-INF/vss-natives/windows-x86_64/vss_native_core.dll"
    $benchModes=if($Reverse){'baseline,cross,java_native,native,java,baseline_tail'}else{'baseline,java,native,java_native,cross,baseline_tail'}
    if($Verify){$benchModes+=',cross_verify'}
    $env:JAVA_HOME=$JavaHome
    & .\gradlew.bat "-Dorg.gradle.java.home=$JavaHome" --offline --project-cache-dir "$Output/project-cache" -I tools/performance/decoration-experiment/experiment.gradle "-PvssExperimentRoot=$Output" "-PvssExperimentRounds=$Rounds" "-PvssExperimentWarmups=$Warmups" -PvssExperimentSide=16 "-PvssExperimentModes=$benchModes" --console=plain --max-workers=2 runServer *> "$Output/launch.log"
    if($LASTEXITCODE -ne 0){throw "Runtime failed; see $Output/launch.log."}
    if((Get-Content -LiteralPath "$Output/prediction-complete.txt" -Raw).Trim() -ne 'success' -or (Test-Path -LiteralPath "$Output/server-failure.txt")){throw 'Generation did not complete.'}
    Write-Output "Completed $Output; summarize both run orders and verify all output hashes."
} finally {
    $env:JAVA_HOME=$benchOldJavaHome
    Pop-Location
}
