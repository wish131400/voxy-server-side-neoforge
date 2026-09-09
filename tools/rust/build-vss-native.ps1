param([ValidateSet('debug','release')][string]$Profile = 'release', [switch]$Package)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$crate = Join-Path $root 'vss-native-core'
$cargo = (Get-Command cargo -ErrorAction SilentlyContinue).Source
if (-not $cargo -and (Test-Path "$env:USERPROFILE/.cargo/bin/cargo.exe")) {
    $cargo = "$env:USERPROFILE/.cargo/bin/cargo.exe"
}
if (-not $cargo) {
    throw 'Rust toolchain is required. Install rustup/cargo before building the native core.'
}
if ($Package -and ($Profile -ne 'release' -or $env:OS -ne 'Windows_NT' -or $env:PROCESSOR_ARCHITECTURE -ne 'AMD64')) {
    throw 'Packaging is currently verified only for a release build on Windows x86_64.'
}
Push-Location $crate
try {
    if ($Profile -eq 'release') { & $cargo test --locked --release } else { & $cargo test --locked }
    if ($LASTEXITCODE -ne 0) { throw 'Native core tests failed.' }
    if ($Profile -eq 'release') { & $cargo build --locked --release } else { & $cargo build --locked }
    if ($LASTEXITCODE -ne 0) { throw 'Native core build failed.' }
} finally { Pop-Location }
if ($Package) {
    $projectRoot = Split-Path -Parent (Split-Path -Parent $root)
    $nativeDestination = Join-Path $projectRoot 'src/main/resources/META-INF/vss-natives/windows-x86_64/vss_native_core.dll'
    Copy-Item -LiteralPath (Join-Path $crate 'target/release/vss_native_core.dll') -Destination $nativeDestination
    Get-FileHash -LiteralPath $nativeDestination -Algorithm SHA256
}
