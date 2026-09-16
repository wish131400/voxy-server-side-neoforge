param([switch]$Package, [string]$ToolsDirectory = 'build/cross-tools')
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$crate = Join-Path $PSScriptRoot 'vss-native-core'
$toolRoot = Join-Path $project $ToolsDirectory
$cargo = Join-Path $env:USERPROFILE '.cargo/bin/cargo.exe'
$zigbuild = Join-Path $toolRoot 'bin/cargo-zigbuild.exe'
if (!(Test-Path -LiteralPath $zigbuild)) {
    throw 'Install pinned build tools: python -m pip install --target build/cross-tools ziglang==0.13.0 cargo-zigbuild==0.23.4'
}
$oldPath = $env:PATH
$oldDeployment = $env:MACOSX_DEPLOYMENT_TARGET
$oldStrip = $env:CARGO_PROFILE_RELEASE_STRIP
$oldLld = $env:VSS_RUST_LLD
try {
    $env:PATH = "$(Join-Path $toolRoot 'ziglang');$(Split-Path -Parent $cargo);$oldPath"
    $env:CARGO_PROFILE_RELEASE_STRIP = 'symbols'
    # Native builds write their progress to stderr. Windows PowerShell 5.1 turns
    # a native command's stderr into a terminating NativeCommandError while
    # $ErrorActionPreference is 'Stop', and `2>&1` alone does not suppress it.
    # The preference is therefore relaxed for the duration of the builds, with
    # the explicit $LASTEXITCODE checks below carrying the failure signal. It is
    # restored before packaging, whose cmdlets must still fail loudly.
    $ErrorActionPreference = 'Continue'
    # `2>&1` on the native build calls below is required on Windows PowerShell
    # 5.1: with $ErrorActionPreference = 'Stop', a native command that writes to
    # stderr raises a terminating NativeCommandError, and cargo reports its
    # progress on stderr. The redirect keeps that output visible while leaving
    # the explicit $LASTEXITCODE checks as the real failure signal. It is
    # deliberately NOT applied to `rustc --print sysroot` below, whose stdout is
    # used as a path.
    & $cargo build --manifest-path "$crate/Cargo.toml" --locked --release --target x86_64-pc-windows-msvc 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'Windows build failed' }
    & $zigbuild zigbuild --manifest-path "$crate/Cargo.toml" --locked --release --target x86_64-unknown-linux-gnu.2.28 --target aarch64-unknown-linux-gnu.2.28 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'Linux builds failed' }
    $env:MACOSX_DEPLOYMENT_TARGET = '11.0'
    # rust-lld honors rustc's JNI export list and deployment target. Zig's
    # libSystem stubs provide system symbol definitions without an Xcode SDK.
    $sysroot = & (Join-Path (Split-Path -Parent $cargo) 'rustc.exe') --print sysroot
    $env:VSS_RUST_LLD = Join-Path $sysroot 'lib/rustlib/x86_64-pc-windows-msvc/bin/rust-lld.exe'
    $stubs = Join-Path $toolRoot 'darwin-stubs'
    New-Item -ItemType Directory -Path $stubs -Force | Out-Null
    foreach ($name in @('libSystem','libc','libm')) {
        Copy-Item -LiteralPath "$toolRoot/ziglang/lib/libc/darwin/libSystem.tbd" -Destination "$stubs/$name.tbd"
    }
    foreach ($target in @('x86_64-apple-darwin','aarch64-apple-darwin')) {
        & $cargo rustc --manifest-path "$crate/Cargo.toml" --locked --release --lib --target $target -- `
            -C "linker=$PSScriptRoot/ld64-windows.cmd" -C linker-flavor=ld64.lld -L "native=$stubs" `
            -C link-arg=-install_name -C link-arg=@rpath/libvss_native_core.dylib -C strip=symbols 2>&1
        if ($LASTEXITCODE -ne 0) { throw "macOS build failed: $target" }
    }
    $ErrorActionPreference = 'Stop'
    if ($Package) {
        $targets = @(
            @('x86_64-pc-windows-msvc','windows-x86_64','vss_native_core.dll'),
            @('x86_64-unknown-linux-gnu','linux-x86_64','libvss_native_core.so'),
            @('aarch64-unknown-linux-gnu','linux-aarch64','libvss_native_core.so'),
            @('x86_64-apple-darwin','macos-x86_64','libvss_native_core.dylib'),
            @('aarch64-apple-darwin','macos-aarch64','libvss_native_core.dylib')
        )
        foreach ($target in $targets) {
            $destination = Join-Path $project "src/main/resources/META-INF/vss-natives/$($target[1])"
            New-Item -ItemType Directory -Path $destination -Force | Out-Null
            Copy-Item -LiteralPath "$crate/target/$($target[0])/release/$($target[2])" -Destination $destination
            Get-FileHash -LiteralPath "$destination/$($target[2])" -Algorithm SHA256
        }
    }
} finally {
    $env:PATH = $oldPath
    $env:MACOSX_DEPLOYMENT_TARGET = $oldDeployment
    $env:CARGO_PROFILE_RELEASE_STRIP = $oldStrip
    $env:VSS_RUST_LLD = $oldLld
}
