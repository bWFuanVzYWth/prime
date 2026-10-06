$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'cargo-test-contract.ps1')
function Must-Reject([scriptblock]$Action) {
    $rejected = $false
    try { & $Action | Out-Null } catch { $rejected = $true }
    if (!$rejected) { throw 'Invalid test run was accepted' }
}
$names = Read-CargoTestNames @('compiler output', 'a::one: test', 'a::two: test', '2 tests, 0 benchmarks')
if ($names.Count -ne 2) { throw 'Discovery count' }
Must-Reject { Read-CargoTestNames @('a::one: test', 'a::one: test') }
$normal = @('running 2 tests', 'test a::one ... ok', 'test a::two ... ignored, needs GPU',
            'test result: ok. 1 passed; 0 failed; 1 ignored; 0 measured; 0 filtered out; finished in 0.01s')
$result = Assert-CargoTestRun $names $normal
if ($result.passed[0] -ne 'a::one' -or $result.ignored[0] -ne 'a::two') { throw 'Result names' }
Must-Reject { Assert-CargoTestRun $names $normal 2 }
Must-Reject { Assert-CargoTestRun @('a::three') $normal }
Must-Reject { Assert-CargoTestRun @('A::one', 'a::two') $normal }
Must-Reject { Assert-CargoTestRun $names @('test a::one ... ok') }
Must-Reject { Assert-CargoTestRun $names ($normal -replace '1 passed', '2 passed') }
Must-Reject { Assert-CargoTestRun @('a::two') @('test a::two ... ignored, needs GPU', 'test result: ok. 0 passed; 0 failed; 1 ignored;') }
Must-Reject { Assert-CargoTestRun @() @('test result: ok. 0 passed; 0 failed; 0 ignored;') }
Must-Reject { Assert-CargoTestRun $names ($normal + 'test result: ok. 1 passed; 0 failed; 1 ignored;') }
$captured = @('test a::one ... driver setup', 'GPU log', 'ok', 'test a::two ... ok',
              'test result: ok. 2 passed; 0 failed; 0 ignored; 0 measured;')
$result = Assert-CargoTestRun $names $captured 2
if ($result.passed.Count -ne 2) { throw 'Nocapture results' }
Must-Reject { Assert-CargoTestRun $names @('test a::one ... log', 'test a::two ... ok', 'test result: ok. 2 passed; 0 failed; 0 ignored;') }
Must-Reject { Assert-CargoTestRun @('a::one') @('test a::one ... FAILED', 'test result: FAILED. 0 passed; 1 failed; 0 ignored;') }
Must-Reject { Assert-CargoTestRun @('a::one') @('test a::one ... ok', 'test a::one ... ok', 'test result: ok. 1 passed; 0 failed; 0 ignored;') }
Write-Host 'Cargo test contract: discovery, execution, ignored, failure, duplicate and nocapture cases passed'
