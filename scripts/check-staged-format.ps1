$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar)
$snapshot = Join-Path $tempRoot ('primept-format-' + [Guid]::NewGuid().ToString('N'))
$expected = [IO.Path]::GetFullPath($snapshot)
$shell = (Get-Process -Id $PID).Path

Push-Location $workspace
try {
    New-Item -ItemType Directory -Path $snapshot | Out-Null
    # Check exactly what Git will commit, using its formatter/configuration too.
    # A clean working-tree copy must not mask an unformatted staged version, and
    # partially staged edits must not be rewritten or silently added to the commit.
    $prefix = $snapshot.Replace('\', '/') + '/'
    # Resolve attributes from the snapshot/index, not unstaged .gitattributes.
    # Commit hooks supply a relative GIT_INDEX_FILE; changing --work-tree would
    # resolve it under the snapshot and silently export an empty index.
    $previousIndex = [Environment]::GetEnvironmentVariable('GIT_INDEX_FILE')
    try {
        if ($previousIndex -and ![IO.Path]::IsPathRooted($previousIndex)) {
            $env:GIT_INDEX_FILE = [IO.Path]::GetFullPath((Join-Path $workspace $previousIndex))
        }
        & git -c core.autocrlf=false "--work-tree=$snapshot" checkout-index --all "--prefix=$prefix"
    } finally {
        [Environment]::SetEnvironmentVariable('GIT_INDEX_FILE', $previousIndex)
    }
    if ($LASTEXITCODE -ne 0) { throw 'Cannot export the staged snapshot for formatting checks.' }
    $formatter = Join-Path $snapshot 'scripts/format.ps1'
    if (!(Test-Path -LiteralPath $formatter -PathType Leaf)) {
        throw 'The staged snapshot does not contain scripts/format.ps1.'
    }
    & $shell -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $formatter -Check
    if ($LASTEXITCODE -ne 0) {
        throw 'Staged source formatting failed. Run scripts/format.ps1, review and stage the intended changes.'
    }
} finally {
    Pop-Location
    if (Test-Path -LiteralPath $snapshot) {
        $resolved = (Resolve-Path -LiteralPath $snapshot).ProviderPath
        if ($resolved -ne $expected -or !$resolved.StartsWith($tempRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing to remove an unexpected snapshot path: $resolved"
        }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
