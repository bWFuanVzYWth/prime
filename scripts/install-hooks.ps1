$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
Push-Location $workspace
try {
    $current = & git config --get core.hooksPath
    if ($LASTEXITCODE -notin @(0, 1)) { throw 'Cannot read repository hook configuration.' }
    if ($current -and $current -ne '.githooks') {
        throw "Repository already uses hooks at '$current'; integrate its hooks before changing core.hooksPath."
    }
    # Executability on Unix is a working-tree property; never modify the index here.
    if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
        & chmod +x .githooks/pre-commit
        if ($LASTEXITCODE -ne 0) { throw 'Cannot mark the pre-commit hook executable.' }
    }
    & git config --local core.hooksPath .githooks
    if ($LASTEXITCODE -ne 0) { throw 'Cannot install repository hooks.' }
    Write-Host 'Installed pre-commit format check for this checkout.'
} finally {
    Pop-Location
}
