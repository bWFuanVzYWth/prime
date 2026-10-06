function Read-CargoTestNames([string[]]$Lines) {
    $names = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($line in $Lines) {
        if ($line -match '^(.+): test$' -and !$names.Add($Matches[1])) {
            throw "Duplicate discovered test: $($Matches[1])"
        }
    }
    return ,@($names | Sort-Object)
}

function Read-CargoTestRun([string[]]$Selected, [string[]]$Lines) {
    $records = [Collections.Generic.Dictionary[string,string]]::new([StringComparer]::Ordinal)
    $pending = ''
    $summary = $null
    foreach ($line in $Lines) {
        if ($line -match '^test (\S+) \.\.\. (.*)$') {
            if ($pending) { throw "No completion for test $pending" }
            $pending = $Matches[1]
            $suffix = $Matches[2]
        } else {
            $suffix = $line
        }
        # --nocapture prints the test's output after its start marker, then status on its own line.
        if ($pending -and $suffix -match '^(ok|FAILED|ignored)(?:,.*)?$') {
            if ($records.ContainsKey($pending)) { throw "Duplicate result for test $pending" }
            $records.Add($pending, $Matches[1])
            $pending = ''
        }
        if ($line -match '^test result: (?:ok|FAILED)\. (\d+) passed; (\d+) failed; (\d+) ignored;') {
            if ($summary) { throw 'Expected one Cargo library/integration test target' }
            $summary = [ordered]@{ passed = [int]$Matches[1]; failed = [int]$Matches[2]; ignored = [int]$Matches[3] }
        }
    }
    if (!$summary -or $pending) { throw 'Missing complete test results' }
    $difference = @(Compare-Object -CaseSensitive @($Selected | Sort-Object) @($records.Keys | Sort-Object))
    if ($difference.Count) { throw "Discovered/executed test names differ: $($difference | Out-String)" }
    $passed = @($records.Keys | Where-Object { $records[$_] -eq 'ok' })
    $failed = @($records.Keys | Where-Object { $records[$_] -eq 'FAILED' })
    $ignored = @($records.Keys | Where-Object { $records[$_] -eq 'ignored' })
    if ($summary.passed -ne $passed.Count -or $summary.failed -ne $failed.Count -or $summary.ignored -ne $ignored.Count) {
        throw 'Test summary does not match individual results'
    }
    return [pscustomobject]@{ passed = $passed; failed = $failed; ignored = $ignored; counts = $summary }
}

function Assert-CargoTestRun([string[]]$Selected, [string[]]$Lines, [int]$Minimum = 1) {
    $result = Read-CargoTestRun $Selected $Lines
    if ($result.failed.Count -or $result.passed.Count -lt $Minimum) {
        throw "Required at least $Minimum executed passing tests; passed=$($result.passed.Count), failed=$($result.failed.Count), ignored=$($result.ignored.Count)"
    }
    return $result
}
