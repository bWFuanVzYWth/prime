param([switch]$Check)

$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
$cargo = (Get-Command cargo -ErrorAction Stop).Source
$clangFormat = (Get-Command clang-format -ErrorAction Stop).Source

Push-Location $workspace
try {
    $rustArguments = @('fmt', '--all')
    if ($Check) { $rustArguments += '--check' }
    & $cargo @rustArguments
    if ($LASTEXITCODE -ne 0) { throw 'Rust formatting failed.' }

    # Only maintained source trees; never format game saves, build output or artifacts.
    $sourcePaths = @(
        Get-ChildItem -LiteralPath 'adapters' -Directory | ForEach-Object {
            $sourceRoot = Join-Path $_.FullName 'src'
            if (Test-Path -LiteralPath $sourceRoot) {
                Get-ChildItem -LiteralPath $sourceRoot -Recurse -File -Filter '*.java'
            }
        }
        Get-ChildItem -LiteralPath 'crates' -Recurse -File -Filter '*.slang'
    ) | Sort-Object FullName | Select-Object -ExpandProperty FullName
    $clangArguments = @('--style=file', '--fallback-style=none')
    if ($Check) { $clangArguments += '--dry-run', '--Werror' }
    else { $clangArguments += '-i' }

    # Keep each invocation below the Windows command-line length limit.
    for ($offset = 0; $offset -lt $sourcePaths.Count; $offset += 32) {
        $last = [Math]::Min($offset + 31, $sourcePaths.Count - 1)
        $batch = $sourcePaths[$offset..$last]
        & $clangFormat @clangArguments @batch
        if ($LASTEXITCODE -ne 0) { throw 'Java/Slang formatting failed.' }
    }
    $action = if ($Check) { 'Checked' } else { 'Formatted' }
    Write-Host "$action Rust workspace and $($sourcePaths.Count) Java/Slang files."
} finally {
    Pop-Location
}
