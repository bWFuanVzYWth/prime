# Compile and execute the production Slang tree sampler on CPU, without Vulkan or a window.
param([string]$Output = 'artifacts/light-sampler-restoration/shader-cpu')

$ErrorActionPreference = 'Stop'
$taskWorkspace = Split-Path -Parent $PSScriptRoot
$taskSlang = (Get-Command slangc -ErrorAction Stop).Source
$taskClang = (Get-Command clang++ -ErrorAction Stop).Source
$taskOutput = [System.IO.Path]::GetFullPath((Join-Path $taskWorkspace $Output))
New-Item -ItemType Directory -Force -Path $taskOutput | Out-Null
$taskGenerated = Join-Path $taskOutput 'light_tree.generated.cpp'
$taskExecutable = Join-Path $taskOutput 'light-tree-cpu.exe'

Push-Location (Join-Path $taskWorkspace 'crates/prime-vulkan')
try {
    & $taskSlang 'tests/shaders/light_tree.slang' -I shaders -entry main -stage compute -target cpp -O3 -o $taskGenerated
    if ($LASTEXITCODE -ne 0) { throw 'Slang CPU compilation failed.' }
    & $taskClang -std=c++17 -O2 -ffp-contract=off -I $taskOutput 'tests/cpu/light_tree.cpp' -o $taskExecutable
    if ($LASTEXITCODE -ne 0) { throw 'Slang CPU harness compilation failed.' }
    & $taskExecutable
    if ($LASTEXITCODE -ne 0) { throw 'Light tree CPU behavior checks failed.' }
} finally {
    Pop-Location
}
