param([string]$OutputDirectory = 'artifacts/pbr-delta-cpu')
$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
$output = if ([IO.Path]::IsPathRooted($OutputDirectory)) {
    [IO.Path]::GetFullPath($OutputDirectory)
} else {
    [IO.Path]::GetFullPath((Join-Path $workspace $OutputDirectory))
}
New-Item -ItemType Directory -Force $output | Out-Null
$slangCompiler = if ($env:SLANGC) { $env:SLANGC }
elseif ($env:VULKAN_SDK) { Join-Path $env:VULKAN_SDK 'Bin/slangc.exe' }
else { (Get-Command slangc -ErrorAction Stop).Source }
$clangCompiler = (Get-Command clang++ -ErrorAction Stop).Source
Push-Location (Join-Path $workspace 'crates/prime-vulkan')
try {
    & $slangCompiler tests/shaders/pbr_delta.slang -I shaders -entry main -stage compute -target cpp -O3 -o (Join-Path $output 'pbr_delta.generated.cpp')
    if ($LASTEXITCODE -ne 0) { throw 'Slang delta/guide CPU compilation failed' }
    & $clangCompiler -std=c++17 -O2 -ffp-contract=off -I $output tests/cpu/pbr_delta.cpp -o (Join-Path $output 'pbr-delta-cpu.exe')
    if ($LASTEXITCODE -ne 0) { throw 'C++ delta/guide compilation failed' }
    & (Join-Path $output 'pbr-delta-cpu.exe')
    if ($LASTEXITCODE -ne 0) { throw 'CPU delta/guide contracts failed' }
} finally {
    Pop-Location
}
