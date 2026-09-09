param(
    [string]$Jdk = 'C:/Program Files/Java/jdk-21',
    [string]$GradleCache = "$env:USERPROFILE/.gradle/caches/modules-2/files-2.1/org.lwjgl",
    [string]$MinecraftJar = "$env:USERPROFILE/.gradle/caches/neoformruntime/artifacts/minecraft_1.21.1_client.jar"
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$report = Join-Path $repo 'build/reports/prediction-visual'
$dependencies = foreach ($module in @('lwjgl', 'lwjgl-glfw', 'lwjgl-opengl')) {
    Get-ChildItem (Join-Path $GradleCache "$module/3.3.3") -Recurse -Filter '*.jar' |
        Where-Object { $_.Name -eq "$module-3.3.3.jar" -or $_.Name -eq "$module-3.3.3-natives-windows.jar" }
}
if (@($dependencies).Count -ne 6) { throw 'LWJGL 3.3.3 Java and Windows native jars are required.' }
$classpath = (@($dependencies.FullName) + $report) -join [IO.Path]::PathSeparator
New-Item -ItemType Directory -Force -Path $report | Out-Null
Push-Location $repo
try {
    & "$Jdk/bin/javac.exe" -encoding UTF-8 -cp $classpath -d $report "$PSScriptRoot/ShaderProbe.java"
    if ($LASTEXITCODE -ne 0) { throw 'Shader probe compilation failed.' }
    & "$Jdk/bin/java.exe" -cp $classpath ShaderProbe --minecraft-client $MinecraftJar 2>&1 |
        Tee-Object -FilePath (Join-Path $report 'voxy-style-shader.log')
    if ($LASTEXITCODE -ne 0) { throw 'Shader probe failed.' }
} finally {
    Pop-Location
}
