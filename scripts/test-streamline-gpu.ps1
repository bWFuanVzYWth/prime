param(
    [string]$Compiler = 'clang++',
    [ValidateSet('1.2', '1.3')]
    [string]$ApiVersion = '1.2',
    [switch]$InitializationOnly,
    [switch]$OmitWriteWithoutFormat,
    [switch]$AbortOnValidationError,
    [switch]$ReportOnlyValidation,
    [switch]$OmitSpecularDistance,
    [switch]$OmitSynchronization2,
    [switch]$Interposed,
    [string]$Output
)

$ErrorActionPreference = 'Stop'
if ($AbortOnValidationError -and $ReportOnlyValidation) {
    throw 'AbortOnValidationError and ReportOnlyValidation are mutually exclusive.'
}
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (!$Output) {
    $Output = Join-Path $repoRoot ('artifacts/streamline-gpu/' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
}
$Output = [IO.Path]::GetFullPath($Output)
if ((Test-Path -LiteralPath $Output) -and (Get-ChildItem -LiteralPath $Output -Force)) {
    throw 'Use an empty output directory to preserve existing test evidence.'
}
New-Item -ItemType Directory -Path $Output -Force | Out-Null
& (Join-Path $PSScriptRoot 'fetch-streamline-sdk.ps1') -VerifyOnly
foreach ($name in @('sl.interposer.dll', 'sl.common.dll', 'sl.dlss_d.dll', 'nvngx_dlssd.dll',
                    'sl.dlss_g.dll', 'sl.pcl.dll', 'sl.reflex.dll', 'nvngx_dlssg.dll', 'NvLowLatencyVk.dll')) {
    Copy-Item -LiteralPath (Join-Path $repoRoot "third_party/streamline/bin/x64/$name") -Destination $Output
}
$executable = Join-Path $Output 'prime_streamline_gpu_test.exe'
& $Compiler '-std=c++17' '-Wno-ignored-pragmas' `
    '-I' (Join-Path $repoRoot 'third_party/streamline/include') `
    '-I' (Join-Path $repoRoot 'third_party/streamline/vulkan-headers/include') `
    (Join-Path $repoRoot 'native/streamline/prime_streamline_gpu_test.cpp') '-o' $executable
if ($LASTEXITCODE -ne 0) { throw 'Streamline GPU test compilation failed' }
$arguments = @()
if ($ApiVersion -eq '1.3') { $arguments += '--vulkan13' }
if ($InitializationOnly) { $arguments += '--initialization-only' }
if ($OmitWriteWithoutFormat) { $arguments += '--omit-write-without-format' }
if ($AbortOnValidationError) { $arguments += '--abort-on-validation-error' }
if ($ReportOnlyValidation) { $arguments += '--report-only-validation' }
if ($OmitSpecularDistance) { $arguments += '--omit-specular-distance' }
if ($OmitSynchronization2) { $arguments += '--omit-synchronization2' }
if ($Interposed) { $arguments += '--interposed' }
$metadata = [ordered]@{
    started_utc = [DateTime]::UtcNow.ToString('o')
    api_version = $ApiVersion
    initialization_only = [bool]$InitializationOnly
    omit_write_without_format = [bool]$OmitWriteWithoutFormat
    abort_on_validation_error = !$ReportOnlyValidation
    omit_specular_distance = [bool]$OmitSpecularDistance
    validation = $true
    synchronization_validation = $true
    synchronization_method = if ($Interposed) { 'VK_LAYER_VALIDATE_SYNC=1 (Khronos layer setting)' } else { 'VkValidationFeaturesEXT' }
    effective_api_version = if ($Interposed) { '1.3 (SDK interposer minimum)' } else { $ApiVersion }
    synchronization2 = !$OmitSynchronization2
    interposed = [bool]$Interposed
    compiler = (& $Compiler --version | Out-String).Trim()
    executable_sha256 = (Get-FileHash -LiteralPath $executable).Hash
    sdk_lock_sha256 = (Get-FileHash -LiteralPath (Join-Path $repoRoot 'third_party/streamline/sdk-lock.json')).Hash
    arguments = $arguments
}
& $executable @arguments 2>&1 | Tee-Object -FilePath (Join-Path $Output 'output.log')
$exitCode = $LASTEXITCODE
$metadata.exit_code = $exitCode
$metadata.status = if ($exitCode -eq 0) { 'passed' } else { 'failed' }
$metadata.finished_utc = [DateTime]::UtcNow.ToString('o')
$metadata | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $Output 'run.json') -Encoding utf8
if ($exitCode -ne 0) {
    throw "Streamline GPU test failed (exit $exitCode); see $Output/output.log. Validation errors are never ignored."
}
Write-Output "Streamline GPU test passed; evidence: $Output"
