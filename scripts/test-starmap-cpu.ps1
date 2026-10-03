param([string]$OutputDirectory = 'artifacts/bugfix-20261003/starmap-cpu')
$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
$output = if ([IO.Path]::IsPathRooted($OutputDirectory)) { [IO.Path]::GetFullPath($OutputDirectory) } else { [IO.Path]::GetFullPath((Join-Path $workspace $OutputDirectory)) }
New-Item -ItemType Directory -Force $output | Out-Null
$slangCompiler = if ($env:SLANGC) { $env:SLANGC } elseif ($env:VULKAN_SDK) { Join-Path $env:VULKAN_SDK 'Bin/slangc.exe' } else { (Get-Command slangc -ErrorAction Stop).Source }
$clangCompiler = (Get-Command clang++ -ErrorAction Stop).Source
Push-Location (Join-Path $workspace 'crates/prime-vulkan')
try {
    & $slangCompiler tests/shaders/starmap_poles.slang -I shaders -entry main -stage compute -target cpp -O3 -o (Join-Path $output 'starmap_poles.generated.cpp')
    if ($LASTEXITCODE -ne 0) { throw 'Slang starmap CPU compilation failed' }
    & $clangCompiler -std=c++17 -O2 -ffp-contract=off -I $output tests/cpu/starmap_poles.cpp -o (Join-Path $output 'starmap-poles-cpu.exe')
    if ($LASTEXITCODE -ne 0) { throw 'Starmap C++ compilation failed' }
    & (Join-Path $output 'starmap-poles-cpu.exe')
    if ($LASTEXITCODE -ne 0) { throw 'Starmap CPU contracts failed' }
    & $slangCompiler shaders/display/stars.slang -I shaders -entry main -stage compute -target cpp -O3 -o (Join-Path $output 'stars.generated.cpp')
    if ($LASTEXITCODE -ne 0) { throw 'Slang production stars CPU compilation failed' }
    & $clangCompiler -std=c++17 -O2 -ffp-contract=off -I $output tests/cpu/starmap_stars.cpp -o (Join-Path $output 'starmap-stars-cpu.exe')
    if ($LASTEXITCODE -ne 0) { throw 'Production stars C++ compilation failed' }
    & (Join-Path $output 'starmap-stars-cpu.exe')
    if ($LASTEXITCODE -ne 0) { throw 'Production stars CPU contracts failed' }
} finally { Pop-Location }
