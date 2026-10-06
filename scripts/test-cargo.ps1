param(
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9_-]+$')][string]$Package,
    [string]$Features = '',
    [switch]$NoDefaultFeatures,
    [switch]$Release,
    [string]$Test = '',
    [string]$Filter = '',
    [switch]$Exact,
    [switch]$Ignored,
    [switch]$IncludeIgnored,
    [switch]$NoCapture,
    [ValidateRange(1, 1000000)][int]$Minimum = 1,
    [string]$Output = ''
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'cargo-test-contract.ps1')
if ($Ignored -and $IncludeIgnored) { throw 'Choose Ignored or IncludeIgnored' }
if ($Exact -and !$Filter) { throw 'Exact requires a full test name' }
$workspace = Split-Path -Parent $PSScriptRoot
if (!$Output) { $Output = Join-Path $workspace "artifacts/test-runs/$Package-$([Guid]::NewGuid().ToString('N'))" }
$Output = [IO.Path]::GetFullPath($Output)
if ((Test-Path -LiteralPath $Output) -and (Get-ChildItem -LiteralPath $Output -Force | Select-Object -First 1)) {
    throw "Use a new output directory: $Output"
}
New-Item -ItemType Directory -Force -Path $Output | Out-Null
$cargo = @('test', '-p', $Package, '--locked')
if ($Test) { $cargo += @('--test', $Test) } else { $cargo += '--lib' }
if ($Features) { $cargo += @('--features', $Features) }
if ($NoDefaultFeatures) { $cargo += '--no-default-features' }
if ($Release) { $cargo += '--release' }
$harness = @('--test-threads=1')
if ($Filter) { $harness += $Filter }
if ($Exact) { $harness += '--exact' }
if ($Ignored) { $harness += '--ignored' }
if ($IncludeIgnored) { $harness += '--include-ignored' }
if ($NoCapture) { $harness += '--nocapture' }
$listCommand = $cargo + @('--') + $harness + @('--list')
$runCommand = $cargo + @('--') + $harness
$metadata = [ordered]@{
    format = 1; status = 'running'; startedUtc = [DateTime]::UtcNow.ToString('o')
    package = $Package; filter = $Filter; minimum = $Minimum
    listCommand = $listCommand; runCommand = $runCommand
    selected = @(); passed = @(); failed = @(); ignored = @()
}
function Save-TestRun { $metadata | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $Output 'run.json') -Encoding utf8 }
Push-Location $workspace
try {
    $metadata.commit = (& git rev-parse HEAD | Out-String).Trim()
    $metadata.workingTree = @(& git status --short)
    $metadata.rustc = (& rustc -Vv | Out-String).Trim()
    Save-TestRun
    & cargo @listCommand > (Join-Path $Output 'list.log') 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Cargo discovery failed; see $Output/list.log" }
    $metadata.selected = Read-CargoTestNames @(Get-Content -LiteralPath (Join-Path $Output 'list.log'))
    if ($metadata.selected.Count -eq 0) { throw 'Zero matching tests: check package, target, feature and full name' }
    Save-TestRun
    Write-Host "Cargo selected $($metadata.selected.Count) test(s); evidence: $Output"
    & cargo @runCommand > (Join-Path $Output 'test.log') 2>&1
    $exitCode = $LASTEXITCODE
    $lines = @(Get-Content -LiteralPath (Join-Path $Output 'test.log'))
    $lines | Select-Object -Last 4 | ForEach-Object { Write-Host $_ }
    $result = Read-CargoTestRun $metadata.selected $lines
    $metadata.passed = $result.passed; $metadata.failed = $result.failed; $metadata.ignored = $result.ignored
    $metadata.counts = $result.counts
    if ($exitCode -ne 0 -or $result.failed.Count -or $result.passed.Count -lt $Minimum) {
        throw "Cargo contract failed: exit=$exitCode, passed=$($result.passed.Count), failed=$($result.failed.Count), ignored=$($result.ignored.Count), minimum=$Minimum; see $Output/test.log"
    }
    $metadata.status = 'passed'
} catch {
    $metadata.status = 'failed'; $metadata.error = $_.Exception.Message
    throw
} finally {
    $metadata.finishedUtc = [DateTime]::UtcNow.ToString('o')
    Save-TestRun
    Pop-Location
}
