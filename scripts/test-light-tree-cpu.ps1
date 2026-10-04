# Compile and execute the production Slang tree sampler on CPU, without Vulkan or a window.
param(
    [string]$Output = 'artifacts/light-sampler-restoration/shader-cpu',
    [string]$Slangc = $env:SLANGC,
    [string]$Compiler = 'clang++',
    [string]$Python = 'python',
    [string]$SpirvValidator = ''
)

$ErrorActionPreference = 'Stop'
$taskWorkspace = Split-Path -Parent $PSScriptRoot
if (-not $Slangc) { $Slangc = 'slangc' }
$taskSlang = (Get-Command $Slangc -ErrorAction Stop).Source
$taskClang = (Get-Command $Compiler -ErrorAction Stop).Source
$taskPython = (Get-Command $Python -ErrorAction Stop).Source
if (-not $SpirvValidator) {
    $SpirvValidator = Join-Path (Split-Path -Parent $taskSlang) 'spirv-val.exe'
    if (-not (Test-Path -LiteralPath $SpirvValidator)) { $SpirvValidator = 'spirv-val' }
}
$taskValidator = (Get-Command $SpirvValidator -ErrorAction Stop).Source
$taskOutput = [System.IO.Path]::GetFullPath((Join-Path $taskWorkspace $Output))
New-Item -ItemType Directory -Force -Path $taskOutput | Out-Null
$taskGenerated = Join-Path $taskOutput 'light_tree.generated.cpp'
$taskExecutable = Join-Path $taskOutput 'light-tree-cpu.exe'
$taskSpirv = Join-Path $taskOutput 'light_tree.spv'

Push-Location (Join-Path $taskWorkspace 'crates/prime-vulkan')
try {
    & $taskSlang 'tests/shaders/light_tree.slang' -I shaders -entry main -stage compute -target cpp -O3 -o $taskGenerated
    if ($LASTEXITCODE -ne 0) { throw 'Slang CPU compilation failed.' }
    & $taskClang -std=c++17 -O2 -ffp-contract=off -I $taskOutput 'tests/cpu/light_tree.cpp' -o $taskExecutable
    if ($LASTEXITCODE -ne 0) { throw 'Slang CPU harness compilation failed.' }
    & $taskExecutable
    if ($LASTEXITCODE -ne 0) { throw 'Light tree CPU behavior checks failed.' }
    & $taskSlang 'tests/shaders/light_tree.slang' -I shaders -entry main -stage compute -target spirv -profile sm_6_6 -emit-spirv-directly -O3 -g3 -o $taskSpirv
    if ($LASTEXITCODE -ne 0) { throw 'Slang SPIR-V compilation failed.' }
    & $taskValidator --target-env vulkan1.3 --scalar-block-layout $taskSpirv
    if ($LASTEXITCODE -ne 0) { throw 'Distance-tree SPIR-V validation failed.' }
    & $taskPython (Join-Path $taskWorkspace 'scripts/check-light-tree-layout.py') $taskSpirv
    if ($LASTEXITCODE -ne 0) { throw 'Distance-tree binary ABI checks failed.' }
} finally {
    Pop-Location
}
