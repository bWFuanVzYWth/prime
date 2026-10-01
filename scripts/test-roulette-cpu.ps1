param([string]$OutputDirectory = 'artifacts/roulette-cpu')
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
    & $slangCompiler tests/shaders/roulette.slang -I shaders -entry main -stage compute -target cpp -O3 -o (Join-Path $output 'roulette.generated.cpp')
    if ($LASTEXITCODE -ne 0) { throw 'Slang CPU compilation failed' }
    & $clangCompiler -std=c++17 -O2 -ffp-contract=off -I $output tests/cpu/roulette.cpp -o (Join-Path $output 'roulette-cpu.exe')
    if ($LASTEXITCODE -ne 0) { throw 'C++ compilation failed' }
    & (Join-Path $output 'roulette-cpu.exe')
    if ($LASTEXITCODE -ne 0) { throw 'CPU transport contracts failed' }
} finally {
    Pop-Location
}
