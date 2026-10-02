param([string]$Compiler = 'clang++')

$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$output = Join-Path $repoRoot 'artifacts/streamline-bridge-tests'
New-Item -ItemType Directory -Path $output -Force | Out-Null
$executable = Join-Path $output 'prime_streamline_test.exe'
& $Compiler '-std=c++17' '-Wno-ignored-pragmas' `
    '-I' (Join-Path $repoRoot 'third_party/streamline/include') `
    '-I' (Join-Path $repoRoot 'third_party/streamline/vulkan-headers/include') `
    (Join-Path $repoRoot 'native/streamline/prime_streamline_test.cpp') '-o' $executable
if ($LASTEXITCODE -ne 0) { throw 'Streamline bridge test compilation failed' }
& $executable
if ($LASTEXITCODE -ne 0) { throw 'Streamline bridge contract tests failed' }
