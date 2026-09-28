param(
    [switch]$Bench,
    [string]$Output = '',
    [ValidateRange(1, 1024)][int]$Threads = 8,
    [ValidateRange(1, 10000)][int]$Warmup = 8,
    [ValidateRange(3, 10000)][int]$Samples = 25,
    [ValidateRange(1, 100)][int]$Rounds = 3
)

$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
if (!$Output) { $Output = Join-Path $workspace ('artifacts/section-suite/' + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
$Output = [IO.Path]::GetFullPath($Output)
if ((Test-Path -LiteralPath $Output) -and (Get-ChildItem -LiteralPath $Output -Force | Select-Object -First 1)) {
    throw "Use a new output directory to keep previous evidence intact: $Output"
}
New-Item -ItemType Directory -Path $Output -Force | Out-Null
$previousRoot = $env:PRIME_SECTION_SUITE_ROOT
$previousThreads = $env:PRIME_CPU_THREADS
$metadata = [ordered]@{
    format = 1; status = 'running'; startedUtc = [DateTime]::UtcNow.ToString('o')
    threads = $Threads; warmup = $Warmup; samples = $Samples; rounds = $Rounds; bench = [bool]$Bench
    scope = 'CPU only; actual MC SectionCompiler vs production Java/FFM/Rust source pipeline; controlled baked resources; neutral lighting; no game/window/GPU'
    processor = $env:PROCESSOR_IDENTIFIER; logicalProcessors = [Environment]::ProcessorCount
    os = [Environment]::OSVersion.VersionString
}
function Save-Metadata { $metadata | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $Output 'run.json') -Encoding utf8 }
function Run-Checked([string]$Name, [string]$Program, [string[]]$Arguments) {
    Write-Host "Section suite: $Name"
    & $Program @Arguments > (Join-Path $Output "$Name.log") 2>&1
    if ($LASTEXITCODE -ne 0) { throw "$Name failed; see $Output/$Name.log" }
}
Push-Location $workspace
try {
    $env:PRIME_SECTION_SUITE_ROOT = $Output
    $env:PRIME_CPU_THREADS = "$Threads"
    $metadata.processorDetails = @(Get-CimInstance Win32_Processor | Select-Object Name, NumberOfCores, NumberOfLogicalProcessors)
    $metadata.commit = (& git rev-parse HEAD | Out-String).Trim()
    $metadata.workingTree = @(& git status --short)
    $metadata.rustc = (& rustc -Vv | Out-String).Trim()
    $metadata.java = (& java -version 2>&1 | Out-String).Trim()
    & git diff --binary > (Join-Path $Output 'working-tree.patch')
    $untracked = @(& git ls-files --others --exclude-standard)
    foreach ($relative in $untracked) {
        $copy = Join-Path $Output "untracked/$relative"
        New-Item -ItemType Directory -Path (Split-Path -Parent $copy) -Force | Out-Null
        Copy-Item -LiteralPath (Join-Path $workspace $relative) -Destination $copy
    }
    Save-Metadata
    $gradleArgs = @(':mc-26.2:cpuSmoke', ':mc-26.3:cpuSmoke', '--no-parallel', '-PprimeptSectionSuite=true', "-PprimeptSectionThreads=$Threads", "-PprimeptSectionOutput=$Output")
    Run-Checked 'generate' '.\gradlew.bat' $gradleArgs
    $dll = Join-Path $workspace 'build/section-bench-native/release/prime_engine.dll'
    $metadata.nativeSha256 = (Get-FileHash -LiteralPath $dll -Algorithm SHA256).Hash
    $tests = @('test', '-p', 'prime_minecraft', '--release', '--locked', '--lib', 'oracle', '--', '--include-ignored', '--test-threads=1')
    Run-Checked 'compare' 'cargo' $tests
    if ($Bench) {
        for ($round = 1; $round -le $Rounds; ++$round) {
            $roundName = 'round-{0:d2}' -f $round
            $roundRoot = Join-Path $Output $roundName
            $roundArgs = $gradleArgs | Where-Object { !$_.StartsWith('-PprimeptSectionOutput=') }
            Run-Checked "bench-$roundName" '.\gradlew.bat' ($roundArgs + @("-PprimeptSectionOutput=$roundRoot", '-PprimeptSectionBench=true', "-PprimeptSectionWarmup=$Warmup", "-PprimeptSectionSamples=$Samples"))
            # Each measurement uses fresh JVMs and regenerates its own inputs. Check those inputs too.
            $env:PRIME_SECTION_SUITE_ROOT = $roundRoot
            Run-Checked "compare-$roundName" 'cargo' $tests
        }
        $env:PRIME_SECTION_SUITE_ROOT = $Output
        Run-Checked 'report' 'python' @('-X', 'utf8', 'scripts/section-bench-report.py', $Output)
    }
    if ((Get-FileHash -LiteralPath $dll -Algorithm SHA256).Hash -ne $metadata.nativeSha256) { throw 'Native library changed during the run' }
    $files = Get-ChildItem -LiteralPath $Output -Recurse -File | Where-Object { $_.Directory.Name -eq 'section-oracle' } | Sort-Object FullName
    $files | ForEach-Object { [pscustomobject]@{ file = [IO.Path]::GetRelativePath($Output, $_.FullName); sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash } } |
        Export-Csv -LiteralPath (Join-Path $Output 'inputs-and-results.csv') -NoTypeInformation -Encoding utf8
    $metadata.status = 'passed'
} catch {
    $metadata.status = 'failed'
    $metadata.error = $_.Exception.Message
    throw
} finally {
    $metadata.finishedUtc = [DateTime]::UtcNow.ToString('o')
    Save-Metadata
    $env:PRIME_SECTION_SUITE_ROOT = $previousRoot
    $env:PRIME_CPU_THREADS = $previousThreads
    Pop-Location
}
Write-Host "Section suite passed: $Output"
